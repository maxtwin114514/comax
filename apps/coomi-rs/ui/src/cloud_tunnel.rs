//! 云端访问的隧道客户端（手机侧）。
//!
//! 为什么是自研 TCP 隧道而不是 `ssh -R`：
//!
//! 手机侧的运行环境（APK 内置的 Debian rootfs）里**没有 ssh / sshpass / nc / socat**，
//! 实测只有 bash、curl、node、python3、sh。所以任何依赖 ssh 的方案在真机上根本
//! 起不来 —— 第一版就是这么废掉的。这里改成在引擎进程内实现一条 TCP 隧道，
//! 不依赖任何外部程序。
//!
//! ## 拓扑
//!
//! ```text
//! 浏览器 ─▶ nginx ─▶ 服务器中继:22336 ─┐
//!                                       │ 单条 TCP，由手机主动拨出
//! 手机 127.0.0.1:8765（本机引擎） ◀─────┘
//! ```
//!
//! 这条控制连接上跑一个极简的复用协议：服务器每收到一个浏览器连接，就分配一个
//! channel 通知手机；手机照着开一条到本机引擎的 TCP，两边原样对拷字节。
//! 因为是**纯 TCP 转发**，WebSocket 升级、流式响应都自然可用，不需要额外处理。
//!
//! ## 帧格式（与 cloud_relay.cjs 必须逐字节一致）
//!
//! ```text
//! [type u8][channel u32be][length u32be][payload]
//! ```
//!
//! type：1=OPEN 2=OPEN_OK 3=OPEN_FAIL 4=DATA 5=CLOSE

use anyhow::Context;
use anyhow::Result;
use sha2::Digest;
use sha2::Sha256;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::AsyncReadExt;
use tokio::io::AsyncWriteExt;
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio::sync::mpsc;

use crate::cloud::CloudSettings;
use crate::cloud::TunnelState;

pub const MAGIC: &str = "COMAX-TUNNEL/1";
const OPEN: u8 = 1;
const OPEN_OK: u8 = 2;
const OPEN_FAIL: u8 = 3;
const DATA: u8 = 4;
const CLOSE: u8 = 5;
const HEADER: usize = 9;
/// 单帧最大长度：防御性上限，避免被一个畸形长度头骗去分配巨量内存。
const MAX_FRAME: u32 = 4 * 1024 * 1024;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(20);
/// 重连退避：失败后先等这么久再试，避免把服务器打成暴力破解。
const RETRY_DELAY: Duration = Duration::from_secs(15);

fn auth_hash(nonce: &str, user: &str, password: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(nonce.as_bytes());
    hasher.update(b"\n");
    hasher.update(user.as_bytes());
    hasher.update(b"\n");
    hasher.update(password.as_bytes());
    hasher.finalize().iter().map(|byte| format!("{byte:02x}")).collect()
}

/// 控制连接的写半边：多个 channel 要往同一条 TCP 上写，串行化。
struct FrameWriter {
    inner: Mutex<tokio::net::tcp::OwnedWriteHalf>,
}

impl FrameWriter {
    async fn send(&self, kind: u8, channel: u32, payload: &[u8]) -> Result<()> {
        let mut frame = Vec::with_capacity(HEADER + payload.len());
        frame.push(kind);
        frame.extend_from_slice(&channel.to_be_bytes());
        frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        frame.extend_from_slice(payload);
        let mut guard = self.inner.lock().await;
        guard.write_all(&frame).await.context("写入隧道失败")?;
        guard.flush().await.context("刷新隧道失败")?;
        Ok(())
    }
}

async fn read_line_async(stream: &mut tokio::net::tcp::OwnedReadHalf) -> Result<String> {
    let mut line = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        let read = stream.read(&mut byte).await.context("读取握手失败")?;
        if read == 0 {
            anyhow::bail!("服务器在握手阶段关闭了连接");
        }
        if byte[0] == b'\n' {
            break;
        }
        line.push(byte[0]);
        if line.len() > 4096 {
            anyhow::bail!("握手行过长");
        }
    }
    Ok(String::from_utf8_lossy(&line).trim().to_owned())
}

/// 连一次、跑到底。返回 Err 表示这条连接断了（外层据此退避重连）。
async fn pump_once(
    settings: &CloudSettings,
    engine_port: u16,
    password: &str,
    state: &TunnelState,
) -> Result<()> {
    let host = settings.host.trim();
    if host.is_empty() {
        anyhow::bail!("未填写服务器地址");
    }
    let stream = tokio::time::timeout(
        Duration::from_secs(20),
        TcpStream::connect((host, settings.tunnel_port)),
    )
    .await
    .map_err(|_| anyhow::anyhow!("连接服务器 {}:{} 超时", host, settings.tunnel_port))?
    .with_context(|| format!("连接服务器 {}:{} 失败", host, settings.tunnel_port))?;
    stream.set_nodelay(true).ok();
    let (mut read_half, write_half) = stream.into_split();
    let writer = Arc::new(FrameWriter {
        inner: Mutex::new(write_half),
    });

    // ── 握手 ──
    // 必须由我们（客户端）先开口：服务器要在同一个端口上区分"隧道"与"普通 HTTP"，
    // 只能先看首字节 —— 它不能抢先说话，否则浏览器请求也会被当成隧道。
    {
        let mut guard = writer.inner.lock().await;
        guard
            .write_all(format!("{MAGIC}\n").as_bytes())
            .await
            .context("发送隧道握手失败")?;
        guard.flush().await.ok();
    }
    let greeting = tokio::time::timeout(HANDSHAKE_TIMEOUT, read_line_async(&mut read_half))
        .await
        .map_err(|_| anyhow::anyhow!("等待服务器握手超时"))??;
    let Some(nonce) = greeting.strip_prefix(MAGIC).map(str::trim) else {
        anyhow::bail!("服务器返回了非预期的握手内容：{greeting}");
    };
    let digest = auth_hash(nonce, settings.user.trim(), password);
    {
        let mut guard = writer.inner.lock().await;
        guard
            .write_all(format!("AUTH {digest}\n").as_bytes())
            .await
            .context("发送鉴权失败")?;
        guard.flush().await.ok();
    }
    let reply = tokio::time::timeout(HANDSHAKE_TIMEOUT, read_line_async(&mut read_half))
        .await
        .map_err(|_| anyhow::anyhow!("等待鉴权结果超时"))??;
    if reply.trim() != "OK" {
        anyhow::bail!("服务器拒绝隧道：{reply}");
    }

    // 握手过了才算"连上"。这一步很关键：pump_once 只有在连接**结束**时才会返回，
    // 所以状态不能等它的返回值 —— 必须在接通的那一刻主动上报，
    // 否则界面会永远显示"未连接"，而实际上隧道是通的。
    state.mark_connected();

    // ── 复用循环 ──
    // channels: channel id -> 把待写字节丢给该 channel 的本地连接任务
    let mut channels: HashMap<u32, mpsc::Sender<Vec<u8>>> = HashMap::new();
    let mut header = [0u8; HEADER];
    loop {
        if read_half.read_exact(&mut header).await.is_err() {
            break;
        }
        let kind = header[0];
        let channel = u32::from_be_bytes([header[1], header[2], header[3], header[4]]);
        let length = u32::from_be_bytes([header[5], header[6], header[7], header[8]]);
        if length > MAX_FRAME {
            anyhow::bail!("收到超长帧（{length} 字节），拒绝");
        }
        let mut payload = vec![0u8; length as usize];
        if length > 0 && read_half.read_exact(&mut payload).await.is_err() {
            break;
        }

        match kind {
            OPEN => {
                let writer = Arc::clone(&writer);
                let (tx, rx) = mpsc::channel::<Vec<u8>>(64);
                channels.insert(channel, tx);
                tokio::spawn(async move {
                    serve_channel(writer, channel, engine_port, rx).await;
                });
            }
            DATA => {
                if let Some(sender) = channels.get(&channel) {
                    // 本地写不动时直接丢弃而不是无限排队：隧道是尽力而为的转发，
                    // 卡住一个 channel 不该把整条控制连接拖死。
                    let _ = sender.try_send(payload);
                }
            }
            CLOSE => {
                if let Some(sender) = channels.remove(&channel) {
                    // 丢一个空包表示"提前关闭"：task 收到 None 之后会收尾
                    drop(sender);
                }
            }
            _ => {}
        }
    }
    Ok(())
}

/// 一个 channel = 一条到本机引擎的 TCP 连接。
async fn serve_channel(
    writer: Arc<FrameWriter>,
    channel: u32,
    engine_port: u16,
    mut inbound: mpsc::Receiver<Vec<u8>>,
) {
    let local = match TcpStream::connect(("127.0.0.1", engine_port)).await {
        Ok(stream) => stream,
        Err(error) => {
            let reason = format!("无法连接本机引擎（127.0.0.1:{engine_port}）：{error}");
            let _ = writer.send(OPEN_FAIL, channel, reason.as_bytes()).await;
            return;
        }
    };
    local.set_nodelay(true).ok();
    let _ = writer.send(OPEN_OK, channel, &[]).await;
    let (mut local_read, mut local_write) = local.into_split();

    // 上行：本机引擎 → 服务器
    let up_writer = Arc::clone(&writer);
    let upstream = tokio::spawn(async move {
        let mut buffer = vec![0u8; 32 * 1024];
        loop {
            match local_read.read(&mut buffer).await {
                Ok(0) | Err(_) => break,
                Ok(count) => {
                    if up_writer.send(DATA, channel, &buffer[..count]).await.is_err() {
                        break;
                    }
                }
            }
        }
        let _ = up_writer.send(CLOSE, channel, &[]).await;
    });

    // 下行：服务器 → 本机引擎
    while let Some(chunk) = inbound.recv().await {
        if local_write.write_all(&chunk).await.is_err() {
            break;
        }
        let _ = local_write.flush().await;
    }
    let _ = local_write.shutdown().await;
    let _ = upstream.await;
}

/// 隧道看护：断线自动重拨，配置改了下一轮就生效。
///
/// 用「重连循环」而不是「启动一个就完事」，是因为手机侧网络会变（切 WiFi/蜂窝、
/// 锁屏被系统掐连接），没有守护的话用户每次都得手动点一次连接。
pub async fn run_forever(
    home: std::path::PathBuf,
    engine_port: u16,
    state: Arc<TunnelState>,
) {
    loop {
        let settings = CloudSettings::load(&home);
        if !settings.enabled {
            state.mark_stopped();
            tokio::time::sleep(Duration::from_secs(5)).await;
            continue;
        }
        let password = CloudSettings::read_password(&home);
        if settings.host.trim().is_empty() || settings.user.trim().is_empty() || password.is_empty() {
            state.mark_stopped();
            state.record_error("云端访问配置不完整（缺服务器地址 / 用户名 / 密码）");
            tokio::time::sleep(Duration::from_secs(10)).await;
            continue;
        }
        if !state.should_connect() {
            // 用户按了「断开」：不要自作主张重连，等他再点连接
            tokio::time::sleep(Duration::from_secs(2)).await;
            continue;
        }

        state.mark_connecting();
        match pump_once(&settings, engine_port, &password, &state).await {
            Ok(()) => state.mark_stopped(),
            Err(error) => {
                state.record_error(format!("{error:#}"));
                state.mark_stopped();
            }
        }
        tokio::time::sleep(RETRY_DELAY).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn auth_hash_is_stable_and_depends_on_every_input() {
        let base = auth_hash("nonce", "user", "pass");
        assert_eq!(base.len(), 64);
        assert_eq!(base, auth_hash("nonce", "user", "pass"));
        // nonce 变了必须换一个值 —— 否则"随机 nonce"就防不住重放
        assert_ne!(base, auth_hash("nonce2", "user", "pass"));
        assert_ne!(base, auth_hash("nonce", "user2", "pass"));
        assert_ne!(base, auth_hash("nonce", "user", "pass2"));
    }

    #[test]
    fn auth_hash_never_contains_the_password() {
        let hash = auth_hash("nonce", "user", "hunter2");
        assert!(!hash.contains("hunter2"));
    }
}
