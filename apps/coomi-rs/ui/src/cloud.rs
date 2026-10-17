//! 云端访问：把手机上的会话通过服务器反代给外部浏览器看。
//!
//! ## 拓扑
//!
//! ```text
//! 浏览器 ──https──▶ 服务器 nginx ──▶ 127.0.0.1:22336
//!                                      ▲
//!                                      │ SSH 反向隧道（手机主动拨出）
//!                                      │
//!                          手机 127.0.0.1:8765（本机引擎）
//! ```
//!
//! 手机在运营商 NAT 后面，服务器**无法**主动连手机，所以隧道必须由手机发起——
//! 这是这里用 `ssh -R` 而不是 nginx 直接 proxy_pass 到手机公网 IP 的唯一原因。
//!
//! ## 权限边界（用户选定：可对话、不可改配置）
//!
//! 隧道一通，任何拿到地址的人都能连上引擎。引擎默认只监听 127.0.0.1 并校验
//! 来源 Origin，所以这里做两件事：
//!   1. 把配置的域名加进 Origin 白名单（否则浏览器连 WS 都会被 403）；
//!   2. 对来自云端的请求，只放行「读会话 + 发消息」，所有改配置的写操作一律拒绝。
//!
//! 第 2 条是真正的安全边界：云端凭据一旦泄露，攻击者能看会话、能对话，
//! 但改不了 API Key、删不了会话、开不了控制模式。

use anyhow::Context;
use anyhow::Result;
use serde::Deserialize;
use serde::Serialize;
use serde_json::Value;
use serde_json::json;
use std::path::Path;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::Mutex as StdMutex;
use std::time::Duration;
use std::time::SystemTime;
use std::time::UNIX_EPOCH;

/// 服务器上给隧道用的固定端口（用户指定）。
pub const TUNNEL_PORT: u16 = 22336;
const CONFIG_FILE: &str = "cloud-access.json";
const PASSWORD_FILE: &str = ".cloud-access.pw";
/// 云端可用的接口前缀：只读会话 + 引擎自身健康检查。
/// 其余一切（供应商、设置、文件、维护、工作流…）都在云端关闭。
const CLOUD_ALLOWED_PREFIXES: &[&str] = &[
    "/api/sessions",
    "/api/runtime/health",
    "/api/runtime/port",
    "/api/tasks",
    "/api/task-details",
    "/api/balance",
    "/api/model-pricing",
    "/api/usage",
    "/api/cloud-access",
    "/api/notes",
    "/api/memory",
    "/ws/",
];

fn now_ts() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

/// 落盘的云端访问配置。密码单独存一个 0600 文件，不进主配置。
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct CloudSettings {
    #[serde(default)]
    pub enabled: bool,
    /// 服务器公网 IP 或主机名。
    #[serde(default)]
    pub host: String,
    /// SSH 登录用户名。
    #[serde(default)]
    pub user: String,
    /// 选填：配置了就用它当访问地址，没填就用公网 IP 直连。
    #[serde(default)]
    pub domain: String,
    /// 反向隧道在服务器上占用的端口。
    #[serde(default = "default_tunnel_port")]
    pub tunnel_port: u16,
    #[serde(default)]
    pub updated_at: u64,
}

const fn default_tunnel_port() -> u16 {
    TUNNEL_PORT
}

impl CloudSettings {
    fn path(home: &Path) -> PathBuf {
        home.join("config").join(CONFIG_FILE)
    }

    fn password_path(home: &Path) -> PathBuf {
        home.join("config").join(PASSWORD_FILE)
    }

    pub fn load(home: &Path) -> Self {
        std::fs::read(Self::path(home))
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default()
    }

    pub fn save(home: &Path, settings: &CloudSettings) -> Result<()> {
        let path = Self::path(home);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(
            path,
            serde_json::to_vec_pretty(settings).context("serialize cloud settings")?,
        )?;
        Ok(())
    }

    /// 密码写进独立文件并收紧权限：`sshpass -p` 会把口令暴露在 `ps` 里，用 `-f` 读文件。
    pub fn save_password(home: &Path, password: &str) -> Result<()> {
        let path = Self::password_path(home);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&path, password)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600))?;
        }
        Ok(())
    }

    pub fn read_password(home: &Path) -> String {
        std::fs::read_to_string(Self::password_path(home))
            .map(|value| value.trim_end_matches(['\n', '\r']).to_owned())
            .unwrap_or_default()
    }

    pub fn has_password(home: &Path) -> bool {
        !Self::read_password(home).is_empty()
    }

    /// 浏览器实际要打开的地址。域名优先，没填就退回公网 IP 直连。
    pub fn access_url(&self, token: &str) -> String {
        if self.host.trim().is_empty() {
            return String::new();
        }
        let scheme_and_host = if self.domain.trim().is_empty() {
            format!("http://{}:{}", self.host.trim(), self.tunnel_port)
        } else {
            format!("https://{}", self.domain.trim())
        };
        if token.is_empty() {
            scheme_and_host
        } else {
            format!("{scheme_and_host}/?token={token}")
        }
    }
}

/// 云端来源判定 + 写操作拦截。
///
/// `host_header` 是请求的 Host；`origin` 是浏览器的 Origin。
/// 只要命中配置的域名/公网 IP，就把这个请求当「云端请求」对待。
#[derive(Clone, Debug, Default)]
pub struct CloudGuard {
    hosts: Vec<String>,
}

impl CloudGuard {
    pub fn from_settings(settings: &CloudSettings, engine_port: u16) -> Self {
        let mut hosts = Vec::new();
        if settings.enabled {
            for candidate in [&settings.domain, &settings.host] {
                let trimmed = candidate.trim().trim_end_matches('/');
                if trimmed.is_empty() {
                    continue;
                }
                let bare = trimmed
                    .trim_start_matches("https://")
                    .trim_start_matches("http://")
                    .split(':')
                    .next()
                    .unwrap_or("")
                    .to_ascii_lowercase();
                if !bare.is_empty() {
                    hosts.push(bare.clone());
                    // 带隧道端口的直连写法（http://ip:22336）也要认出来
                    hosts.push(format!("{}:{}", bare, settings.tunnel_port));
                }
            }
        }
        // 本机永远是本机：即使配了同一个域名也不会误判
        let local = vec![
            "127.0.0.1".to_owned(),
            "localhost".to_owned(),
            format!("127.0.0.1:{engine_port}"),
            format!("localhost:{engine_port}"),
        ];
        hosts.retain(|host| !local.contains(host));
        Self { hosts }
    }

    pub fn is_empty(&self) -> bool {
        self.hosts.is_empty()
    }

    /// 这个请求是不是从云端来的。
    pub fn is_cloud_request(&self, host_header: Option<&str>, origin: Option<&str>) -> bool {
        if self.hosts.is_empty() {
            return false;
        }
        for value in [host_header, origin].into_iter().flatten() {
            let stripped = value
                .trim()
                .trim_start_matches("https://")
                .trim_start_matches("http://")
                .to_ascii_lowercase();
            let bare = stripped.split('/').next().unwrap_or("").to_owned();
            if bare.is_empty() {
                continue;
            }
            if self.hosts.iter().any(|host| host == &bare) {
                return true;
            }
        }
        false
    }

    /// WS 握手用的 Origin 是否放行。
    pub fn origin_allowed(&self, origin: &str, engine_port: u16) -> bool {
        let allowed_local = [
            format!("http://127.0.0.1:{engine_port}"),
            format!("http://localhost:{engine_port}"),
        ];
        if allowed_local.iter().any(|item| item == origin) {
            return true;
        }
        let stripped = origin
            .trim_start_matches("https://")
            .trim_start_matches("http://")
            .to_ascii_lowercase();
        let bare = stripped.split('/').next().unwrap_or("");
        self.hosts.iter().any(|host| host == bare)
    }
}

/// 云端请求能否访问这个接口。
///
/// 读操作（GET/HEAD）只受前缀白名单限制；写操作（POST/PUT/DELETE/PATCH）
/// 对云端**一律拒绝** —— 这正是用户要的「可对话，但不能改配置」。
pub fn cloud_request_allowed(method: &str, path: &str) -> bool {
    let allowed_path = CLOUD_ALLOWED_PREFIXES
        .iter()
        .any(|prefix| path.starts_with(prefix));
    if !allowed_path {
        return false;
    }
    let read_only = matches!(method, "GET" | "HEAD" | "OPTIONS");
    if read_only {
        return true;
    }
    // 写操作里唯一放行的：云端访问自己的开关（否则用户没法在云端关掉它）
    // 以及会话消息的写入 —— 但会话消息走 WS（GET 升级），不走 POST。
    path.starts_with("/api/cloud-access")
}

// ---------------------------------------------------------------------------
// 隧道状态
// ---------------------------------------------------------------------------

/// 隧道对外可见的状态。
///
/// 没有 pid：隧道现在跑在引擎进程内的一个任务里（见 cloud_tunnel.rs），
/// 不再 spawn ssh 子进程 —— 手机侧的运行环境里根本没有 ssh。
#[derive(Clone, Debug, Default, Serialize)]
pub struct TunnelStatus {
    /// 用户是否要求连接（点过「连接」且没点「断开」）。
    pub desired: bool,
    /// 当前是否已经连上服务器。
    pub running: bool,
    pub last_error: String,
    pub connected_at: u64,
}

pub struct TunnelState {
    desired: std::sync::atomic::AtomicBool,
    running: std::sync::atomic::AtomicBool,
    inner: StdMutex<TunnelInner>,
}

#[derive(Default)]
struct TunnelInner {
    last_error: String,
    connected_at: u64,
}

impl Default for TunnelState {
    fn default() -> Self {
        Self {
            desired: std::sync::atomic::AtomicBool::new(false),
            running: std::sync::atomic::AtomicBool::new(false),
            inner: StdMutex::new(TunnelInner::default()),
        }
    }
}

impl TunnelState {
    pub fn status(&self) -> TunnelStatus {
        let inner = self.inner.lock().unwrap_or_else(|p| p.into_inner());
        TunnelStatus {
            desired: self.desired.load(std::sync::atomic::Ordering::SeqCst),
            running: self.running.load(std::sync::atomic::Ordering::SeqCst),
            last_error: inner.last_error.clone(),
            connected_at: inner.connected_at,
        }
    }

    fn set_error(&self, message: impl Into<String>) {
        let mut inner = self.inner.lock().unwrap_or_else(|p| p.into_inner());
        inner.last_error = message.into();
    }

    pub fn record_error(&self, message: impl Into<String>) {
        self.set_error(message);
    }

    /// 用户点了「连接」：让看护任务开始拨号。
    pub fn request_connect(&self) {
        self.desired.store(true, std::sync::atomic::Ordering::SeqCst);
        self.set_error("");
    }

    /// 用户点了「断开」：停在这里，不要自作主张重连。
    pub fn request_stop(&self) {
        self.desired.store(false, std::sync::atomic::Ordering::SeqCst);
        self.running.store(false, std::sync::atomic::Ordering::SeqCst);
        self.set_error("");
    }

    pub fn should_connect(&self) -> bool {
        self.desired.load(std::sync::atomic::Ordering::SeqCst)
    }

    pub fn mark_connecting(&self) {
        let mut inner = self.inner.lock().unwrap_or_else(|p| p.into_inner());
        inner.last_error.clear();
        inner.connected_at = 0;
    }

    pub fn mark_connected(&self) {
        self.running.store(true, std::sync::atomic::Ordering::SeqCst);
        let mut inner = self.inner.lock().unwrap_or_else(|p| p.into_inner());
        inner.last_error.clear();
        inner.connected_at = now_ts();
    }

    pub fn mark_stopped(&self) {
        self.running.store(false, std::sync::atomic::Ordering::SeqCst);
    }
}

// ---------------------------------------------------------------------------
// 服务器端脚本
// ---------------------------------------------------------------------------

/// 服务器侧一键部署脚本（用户在服务器上跑）。
///
/// 脚本做四件事，每一件都幂等，且**只新增自己的文件**，不动服务器上已有的站点：
///   1. 把中继脚本与配置写到 /opt/comax-cloud/；
///   2. 注册并启动 systemd 服务（中继常驻，掉线自动重启）；
///   3. 新增一个 nginx vhost 反代到 127.0.0.1:<端口>；
///   4. 重载 nginx 并自检。
///
/// 用户只需要在 Comax 里填服务器地址 + 用户名 + 密码，然后把这段脚本贴到服务器上跑一次。
pub fn server_script(settings: &CloudSettings, token: &str, relay_source: &str) -> String {
    let port = settings.tunnel_port;
    let host = settings.host.trim();
    let domain = settings.domain.trim();
    // nginx 的 server_name：配了域名就用域名，否则用公网 IP，再不然兜底 _
    let server_name = if !domain.is_empty() {
        domain.to_owned()
    } else if !host.is_empty() {
        host.to_owned()
    } else {
        "_".to_owned()
    };
    let access_url = settings.access_url(token);
    format!(
        r#"#!/bin/bash
# Comax 云端访问 · 服务器端部署脚本
#
# 在服务器上以 root 执行一次：  bash comax-cloud-setup.sh
#
# 它会把「手机 → 服务器 → 浏览器」这条链路搭起来：
#   · 中继服务：监听 0.0.0.0:{port}，手机主动拨进来，浏览器也从这个口进
#   · nginx：把域名反代到 127.0.0.1:{port}（WebSocket 升级已配好）
#
# 只新增 /opt/comax-cloud/ 与一个 nginx conf，不动你已有的站点。
set -euo pipefail

PORT="{port}"
SERVER_NAME="{server_name}"
RELAY_USER="{relay_user}"
RELAY_PASS="{relay_pass}"
ACCESS_URL="{access_url}"
DIR=/opt/comax-cloud

echo "==> 0/5 前置检查"
if [ "$(id -u)" != "0" ]; then echo "请用 root 运行（sudo bash $0）" >&2; exit 1; fi
NODE_BIN="$(command -v node || true)"
if [ -z "$NODE_BIN" ]; then echo "没有找到 node，请先安装（apt install nodejs）" >&2; exit 1; fi
echo "    node: $NODE_BIN"

echo "==> 1/5 写入中继脚本与配置"
mkdir -p "$DIR"
cat > "$DIR/relay.cjs" <<'COMAX_RELAY_EOF'
{relay_source}
COMAX_RELAY_EOF
cat > "$DIR/config.json" <<COMAX_CONF_EOF
{{
  "port": $PORT,
  "user": "$RELAY_USER",
  "password": "$RELAY_PASS"
}}
COMAX_CONF_EOF
chmod 600 "$DIR/config.json"
echo "    已写入 $DIR"

echo "==> 2/5 注册 systemd 服务"
cat > /etc/systemd/system/comax-cloud.service <<COMAX_UNIT_EOF
[Unit]
Description=Comax cloud access relay
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$DIR
Environment=COMAX_RELAY_CONFIG=$DIR/config.json
ExecStart=$NODE_BIN $DIR/relay.cjs
Restart=always
RestartSec=3
User=root

[Install]
WantedBy=multi-user.target
COMAX_UNIT_EOF
systemctl daemon-reload
systemctl enable comax-cloud >/dev/null 2>&1 || true
systemctl restart comax-cloud
sleep 1
if systemctl is-active --quiet comax-cloud; then
  echo "    中继已启动"
else
  echo "    中继启动失败，看日志：journalctl -u comax-cloud -n 50" >&2
  exit 1
fi

echo "==> 3/5 写 nginx vhost"
if [ -d /etc/nginx/sites-available ] && [ -d /etc/nginx/sites-enabled ]; then
  NGINX_CONF=/etc/nginx/sites-available/comax-cloud.conf
  LINK=/etc/nginx/sites-enabled/comax-cloud.conf
else
  NGINX_CONF=/etc/nginx/conf.d/comax-cloud.conf
  LINK=""
fi
cat > "$NGINX_CONF" <<'COMAX_NGINX_EOF'
server {{
    listen 80;
    server_name {server_name};

    # 访问地址里带着引擎令牌，别把它写进访问日志
    access_log off;
    client_max_body_size 64m;

    location / {{
        proxy_pass http://127.0.0.1:{port};
        proxy_http_version 1.1;
        # 注意：这是 nginx 配置，$host 这类变量不能加反斜杠
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # WebSocket 升级：会话流式输出全靠它
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        # 流式响应不能被缓冲
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }}
}}
COMAX_NGINX_EOF
if [ -n "$LINK" ]; then ln -sf "$NGINX_CONF" "$LINK"; fi

echo "==> 4/5 校验并重载 nginx"
if nginx -t 2>&1; then
  systemctl reload nginx 2>/dev/null || nginx -s reload
  echo "    nginx 已重载"
else
  echo "nginx 配置校验失败，请检查 $NGINX_CONF" >&2
  exit 1
fi

echo "==> 5/5 自检"
curl -sS -m 5 -o /dev/null -w "    中继端口 {port} 状态码 %{{http_code}}\n" "http://127.0.0.1:{port}/" || true
echo
echo "═══════════════════════════════════════════════════"
echo "  部署完成"
echo "  1) 确认云服务商安全组已放行 TCP {port}"
echo "  2) 回到 Comax →「云端访问」→ 点「连接」"
echo "  3) 在外面用浏览器打开："
echo "     {access_url}"
echo
echo "  中继账号：$RELAY_USER（密码就是你在 Comax 里填的那个）"
echo "  看日志：journalctl -u comax-cloud -f"
echo "═══════════════════════════════════════════════════"
"#,
        port = port,
        server_name = server_name,
        relay_user = if settings.user.trim().is_empty() {
            "comax"
        } else {
            settings.user.trim()
        },
        relay_pass = "（Comax 里填的密码）",
        relay_source = relay_source,
        access_url = access_url,
    )
}

/// 内嵌的中继脚本（部署脚本会把它写到服务器上）。
pub const RELAY_SOURCE: &str = include_str!("cloud_relay.cjs");

/// 给前端的状态快照（不含密码）。
pub fn status_payload(
    home: &Path,
    settings: &CloudSettings,
    tunnel: &TunnelStatus,
    token: &str,
) -> Value {
    json!({
        "enabled": settings.enabled,
        "host": settings.host,
        "user": settings.user,
        "domain": settings.domain,
        "tunnel_port": settings.tunnel_port,
        "has_password": CloudSettings::has_password(home),
        "access_url": settings.access_url(token),
        "tunnel": tunnel,
        "updated_at": settings.updated_at,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn settings(domain: &str, host: &str) -> CloudSettings {
        CloudSettings {
            enabled: true,
            host: host.to_owned(),
            user: "root".into(),
            domain: domain.to_owned(),
            tunnel_port: TUNNEL_PORT,
            updated_at: 0,
        }
    }

    #[test]
    fn cloud_guard_matches_configured_domain_but_not_localhost() {
        let guard = CloudGuard::from_settings(&settings("comax.example.com", "1.2.3.4"), 8765);
        assert!(guard.is_cloud_request(Some("comax.example.com"), None));
        assert!(guard.is_cloud_request(None, Some("https://comax.example.com")));
        assert!(guard.is_cloud_request(Some("1.2.3.4:22336"), None));
        // 本机永远不算云端，否则安卓 WebView 里的正常操作会被自己的策略挡掉
        assert!(!guard.is_cloud_request(Some("127.0.0.1:8765"), None));
        assert!(!guard.is_cloud_request(Some("localhost:8765"), None));
        assert!(!guard.is_cloud_request(Some("other.example.com"), None));
    }

    #[test]
    fn cloud_guard_is_inert_when_disabled() {
        let mut config = settings("comax.example.com", "1.2.3.4");
        config.enabled = false;
        let guard = CloudGuard::from_settings(&config, 8765);
        assert!(guard.is_empty());
        assert!(!guard.is_cloud_request(Some("comax.example.com"), None));
    }

    #[test]
    fn cloud_clients_can_read_sessions_but_never_write_config() {
        // 读：放行
        assert!(cloud_request_allowed("GET", "/api/sessions"));
        assert!(cloud_request_allowed("GET", "/api/runtime/health"));
        assert!(cloud_request_allowed("GET", "/api/sessions/abc/billing"));
        // 写：一律拒绝 —— 这是「可对话但不能改配置」的落点
        assert!(!cloud_request_allowed("POST", "/api/providers"));
        assert!(!cloud_request_allowed("PUT", "/api/settings/connection"));
        assert!(!cloud_request_allowed("DELETE", "/api/sessions/abc"));
        assert!(!cloud_request_allowed("POST", "/api/fs/write"));
        assert!(!cloud_request_allowed("POST", "/api/runtime/custom-prompt"));
        // 云端访问自己的开关要能改，否则用户在云端关不掉它
        assert!(cloud_request_allowed("PUT", "/api/cloud-access"));
        // 白名单外的读也不行（比如维护、工作流、群聊）
        assert!(!cloud_request_allowed("GET", "/api/maintenance/scan"));
    }

    #[test]
    fn access_url_prefers_domain_and_falls_back_to_ip() {
        let with_domain = settings("comax.example.com", "1.2.3.4");
        assert_eq!(
            with_domain.access_url("tk"),
            "https://comax.example.com/?token=tk"
        );
        let without_domain = settings("", "1.2.3.4");
        assert_eq!(
            without_domain.access_url("tk"),
            "http://1.2.3.4:22336/?token=tk"
        );
        // 没填主机就没什么可给的地址
        assert_eq!(settings("", "").access_url("tk"), "");
    }

    #[test]
    fn origin_whitelist_keeps_loopback_and_adds_cloud_domain() {
        let guard = CloudGuard::from_settings(&settings("comax.example.com", "1.2.3.4"), 8765);
        assert!(guard.origin_allowed("http://127.0.0.1:8765", 8765));
        assert!(guard.origin_allowed("https://comax.example.com", 8765));
        assert!(!guard.origin_allowed("https://evil.example.com", 8765));
    }

    #[test]
    fn server_script_installs_a_relay_and_never_touches_other_sites() {
        let script = server_script(&settings("comax.example.com", "1.2.3.4"), "tk", RELAY_SOURCE);
        // 只新增自己的 conf 与目录，不动别的站点
        assert!(script.contains("comax-cloud.conf"));
        assert!(script.contains("/opt/comax-cloud"));
        assert!(!script.contains("rm -rf /etc/nginx/conf.d"));
        // 内嵌的中继脚本必须真的被写进去，否则手机连上来也没人应答
        assert!(script.contains("COMAX-TUNNEL/1"));
        // nginx 变量不能带反斜杠（原始串里的 \$ 会原样写进配置，变量不展开）
        assert!(script.contains("proxy_set_header Upgrade $http_upgrade"));
        // WebSocket 升级头不能少，否则会话流走不通
        assert!(script.contains("Connection \"upgrade\""));
        // 必须提醒放行端口 —— 用户明确要求
        assert!(script.contains("安全组已放行"));
        assert!(script.contains("22336"));
        // 幂等：重复执行靠覆盖写 + systemd restart
        assert!(script.contains("systemctl restart comax-cloud"));
    }
}
