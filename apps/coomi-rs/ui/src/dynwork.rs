//! 动态工作流引擎（M1）：门面注入 + 记账 + journal/回放 + 子代理会话 + 世界调用白名单。
//!
//! 这是「群友 Dynamic Workflow（ZCode）文档包」在 Comax 引擎上的等价实现，按
//! `dynamic-workflow-engine-spec-for-pi.md` §3/§5/§9 与 `engine-ground-truth.md`
//! 的 M1 边界落地。核心不变量：
//!
//!  1. **脚本零副作用原语**：脚本运行在 Node 的受控 vm 上下文里，读写文件、跑命令、
//!     问子代理、发产物一律经门面原语打成 stdio 行协议，由本模块代为执行。
//!  2. **缓存键 = kind + 调用点(file:line:col) + 出现序号**（ground truth §1 的
//!     省事等价物：沙箱用 `new Error().stack` 取脚本帧作 siteId）。journal 命中即回放。
//!  3. **世界调用回放不重跑**：恢复/续跑永远不重复副作用。
//!  4. **命令白名单来自编译期字面量**：`world.run` 的第一个参数必须是脚本文本里的
//!     字符串字面量，扫描结果就是用户在确认门里看到并批准的名单。
//!  5. **非确定性运行时拦截**：沙箱里 `Date.now() / new Date() / Math.random()` 抛错。
//!
//! M1 明确不做（对应 spec §9 的 M2/M3，缺口写在代码与 UI 提示里，不假装支持）：
//!  - 洞（hole）尾洞链与子代理升级提问；
//!  - amend 的调用点稳定匹配（行号漂移不命中，只能同脚本原样续跑）；
//!  - 并发闸门（同名子代理已按 FIFO 串行，实名并发的上限留待 M2）。
//!
//! 沙箱脚本本体：`ui/src/workflow_sandbox.cjs`（随引擎二进制内嵌）。

use anyhow::Context as _;
use anyhow::Result;
use anyhow::anyhow;
use async_trait::async_trait;
use coomi_engine::Agent;
use coomi_engine::AgentEvent;
use coomi_engine::AgentObserver;
use coomi_engine::ApprovalHandler;
use coomi_engine::Session;
use coomi_engine::ToolCall;
use coomi_security::AccessMode;
use coomi_security::SecurityPolicy;
use coomi_services::HttpModelProvider;
use coomi_services::McpRuntime;
use coomi_services::MemoryManager;
use coomi_services::ProviderRegistry;
use coomi_tools::CoreTools;
use serde::Deserialize;
use serde::Serialize;
use serde_json::Value;
use serde_json::json;
use std::collections::HashMap;
use std::collections::HashSet;
use std::io::Write as _;
use std::path::Path;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::Mutex as StdMutex;
use std::sync::atomic::AtomicU64;
use std::sync::atomic::Ordering;
use std::time::Duration;
use std::time::SystemTime;
use std::time::UNIX_EPOCH;
use tokio::io::AsyncBufReadExt;
use tokio::io::AsyncWriteExt;
use tokio::io::BufReader;
use tokio::process::Command;
use tokio::sync::Mutex;

/// 沙箱运行器（内嵌，运行时落到运行目录再交给 node）。
const SANDBOX_SOURCE: &str = include_str!("workflow_sandbox.cjs");
const MAX_COMMAND_OUTPUT: usize = 400 * 1024;
const MAX_READ_BYTES: u64 = 512 * 1024;
const MAX_GLOB_RESULTS: usize = 400;
const MAX_GREP_RESULTS: usize = 400;
const MAX_WALK_FILES: usize = 40_000;
const MAX_EVENTS_PER_RUN: usize = 2_000;
const WORLD_TIMEOUT: Duration = Duration::from_secs(180);
const ACTOR_TIMEOUT: Duration = Duration::from_secs(420);
const WALK_SKIP: &[&str] = &[".git", "node_modules", "target", ".venv", "__pycache__", ".gradle"];

fn now_ts() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

/// 草稿 / 运行 id 只允许安全字符，避免越出工作流目录。
fn safe_segment(value: &str) -> Result<String> {
    let trimmed = value.trim();
    if trimmed.is_empty() || trimmed.len() > 96 {
        return Err(anyhow!("工作流 id 长度不合法"));
    }
    if !trimmed
        .chars()
        .all(|ch| ch.is_ascii_alphanumeric() || ch == '-' || ch == '_')
    {
        return Err(anyhow!("工作流 id 只能包含字母、数字、- 和 _"));
    }
    Ok(trimmed.to_owned())
}

// ---------------------------------------------------------------------------
// 草稿存储
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct DraftMeta {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    pub updated_at: u64,
}

#[derive(Clone, Debug)]
pub struct DraftStore {
    root: PathBuf,
}

impl DraftStore {
    pub fn new(home: &Path) -> Self {
        Self {
            root: home.join("workflow-drafts"),
        }
    }

    fn script_path(&self, id: &str) -> PathBuf {
        self.root.join(format!("{id}.mjs"))
    }

    fn meta_path(&self, id: &str) -> PathBuf {
        self.root.join(format!("{id}.json"))
    }

    pub fn list(&self) -> Vec<DraftMeta> {
        let mut drafts = Vec::new();
        let Ok(entries) = std::fs::read_dir(&self.root) else {
            return drafts;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|value| value.to_str()) != Some("json") {
                continue;
            }
            let Ok(bytes) = std::fs::read(&path) else {
                continue;
            };
            if let Ok(meta) = serde_json::from_slice::<DraftMeta>(&bytes) {
                drafts.push(meta);
            }
        }
        drafts.sort_by(|left, right| right.updated_at.cmp(&left.updated_at));
        drafts
    }

    pub fn load(&self, id: &str) -> Result<(DraftMeta, String)> {
        let id = safe_segment(id)?;
        let bytes = std::fs::read(self.meta_path(&id))
            .with_context(|| format!("工作流草稿 {id} 不存在"))?;
        let meta: DraftMeta = serde_json::from_slice(&bytes).context("草稿元数据无法解析")?;
        let script = std::fs::read_to_string(self.script_path(&id)).unwrap_or_default();
        Ok((meta, script))
    }

    pub fn save(&self, id: &str, name: &str, description: &str, script: &str) -> Result<DraftMeta> {
        let id = safe_segment(id)?;
        std::fs::create_dir_all(&self.root).context("创建工作流目录失败")?;
        let meta = DraftMeta {
            id: id.clone(),
            name: if name.trim().is_empty() {
                id.clone()
            } else {
                name.trim().chars().take(80).collect()
            },
            description: description.trim().chars().take(400).collect(),
            updated_at: now_ts(),
        };
        std::fs::write(
            self.meta_path(&id),
            serde_json::to_vec_pretty(&meta).context("序列化草稿元数据失败")?,
        )?;
        std::fs::write(self.script_path(&id), script)?;
        Ok(meta)
    }

    pub fn remove(&self, id: &str) -> Result<()> {
        let id = safe_segment(id)?;
        let _ = std::fs::remove_file(self.script_path(&id));
        let _ = std::fs::remove_file(self.meta_path(&id));
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// 编译门（静态扫描）
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, Default, Serialize)]
pub struct ScriptScan {
    /// 任何一条非空即拒绝运行（Diagnostics = 什么都没发生）。
    pub diagnostics: Vec<String>,
    /// 用户批准的命令白名单：世界调用的命令名必须来自这里。
    pub commands: Vec<String>,
    /// 编译期收集的产物 id 集合。
    pub artifacts: Vec<String>,
}

/// 去掉注释但保留字符串字面量：命令白名单必须从字符串里读出来。
fn strip_comments(source: &str) -> String {
    let mut out = String::with_capacity(source.len());
    let bytes = source.as_bytes();
    let mut index = 0usize;
    #[derive(PartialEq)]
    enum Mode {
        Code,
        Line,
        Block,
        Single,
        Double,
        Template,
    }
    let mut mode = Mode::Code;
    while index < bytes.len() {
        let current = bytes[index] as char;
        let next = bytes.get(index + 1).copied().map(char::from);
        match mode {
            Mode::Code => {
                if current == '/' && next == Some('/') {
                    mode = Mode::Line;
                    out.push(' ');
                    out.push(' ');
                    index += 2;
                    continue;
                }
                if current == '/' && next == Some('*') {
                    mode = Mode::Block;
                    out.push(' ');
                    out.push(' ');
                    index += 2;
                    continue;
                }
                match current {
                    '\'' => mode = Mode::Single,
                    '"' => mode = Mode::Double,
                    '`' => mode = Mode::Template,
                    _ => {}
                }
                out.push(current);
            }
            Mode::Line => {
                if current == '\n' {
                    mode = Mode::Code;
                    out.push('\n');
                } else {
                    out.push(' ');
                }
            }
            Mode::Block => {
                if current == '*' && next == Some('/') {
                    mode = Mode::Code;
                    out.push(' ');
                    out.push(' ');
                    index += 2;
                    continue;
                }
                out.push(if current == '\n' { '\n' } else { ' ' });
            }
            Mode::Single | Mode::Double | Mode::Template => {
                if current == '\\' {
                    out.push(current);
                    if let Some(following) = next {
                        out.push(following);
                    }
                    index += 2;
                    continue;
                }
                let closing = match mode {
                    Mode::Single => '\'',
                    Mode::Double => '"',
                    _ => '`',
                };
                if current == closing {
                    mode = Mode::Code;
                }
                out.push(current);
            }
        }
        index += 1;
    }
    out
}

/// 从 `world.run(` 后面读第一个参数：必须是字符串字面量。
fn read_first_string_literal(source: &str, open_paren: usize) -> Option<String> {
    let mut index = open_paren + 1;
    let bytes = source.as_bytes();
    while index < bytes.len() && (bytes[index] as char).is_whitespace() {
        index += 1;
    }
    let quote = *bytes.get(index)? as char;
    if quote != '\'' && quote != '"' {
        return None;
    }
    let mut value = String::new();
    index += 1;
    while index < bytes.len() {
        let current = bytes[index] as char;
        if current == '\\' {
            if let Some(following) = bytes.get(index + 1).copied().map(char::from) {
                value.push(following);
                index += 2;
                continue;
            }
        }
        if current == quote {
            return Some(value);
        }
        value.push(current);
        index += 1;
    }
    None
}

/// 编译门 + 白名单收集。任何静态违例都进 diagnostics，调用方据此拒绝启动。
pub fn scan_script(source: &str) -> ScriptScan {
    let stripped = strip_comments(source);
    let mut scan = ScriptScan::default();

    let forbidden: &[(&str, &str)] = &[
        ("require(", "禁止 require()：脚本只能使用注入的门面原语"),
        ("import ", "禁止 import：脚本是单文件，外部能力请经 world.run / agent"),
        ("import(", "禁止动态 import()"),
        ("process.", "禁止访问 process：脚本不做进程级操作"),
        ("fetch(", "禁止 fetch：网络访问请经 world.run"),
        ("globalThis", "禁止 globalThis：脚本只能看到注入的门面"),
        ("eval(", "禁止 eval()"),
        ("new Function(", "禁止 new Function()"),
        ("Date.now", "禁止 Date.now()：脚本必须确定性"),
        ("Math.random", "禁止 Math.random()：脚本必须确定性"),
    ];
    for (needle, message) in forbidden {
        if stripped.contains(needle) {
            scan.diagnostics.push((*message).to_owned());
        }
    }

    let mut search_from = 0usize;
    while let Some(offset) = stripped[search_from..].find("world.run") {
        let start = search_from + offset;
        search_from = start + "world.run".len();
        let Some(paren_offset) = stripped[start..].find('(') else {
            continue;
        };
        let open_paren = start + paren_offset;
        match read_first_string_literal(&stripped, open_paren) {
            Some(command) => {
                if !scan.commands.iter().any(|item| item == &command) {
                    scan.commands.push(command);
                }
            }
            None => scan.diagnostics.push(
                "world.run 的命令必须是编译期字符串字面量（确认窗要靠它展示命令白名单）".to_owned(),
            ),
        }
    }

    for api in ["artifact.file", "artifact.markdown", "artifact.chart"] {
        let mut search_from = 0usize;
        while let Some(offset) = stripped[search_from..].find(api) {
            let start = search_from + offset;
            search_from = start + api.len();
            let Some(paren_offset) = stripped[start..].find('(') else {
                continue;
            };
            match read_first_string_literal(&stripped, start + paren_offset) {
                Some(id) => {
                    if !scan.artifacts.iter().any(|item| item == &id) {
                        scan.artifacts.push(id);
                    }
                }
                None => scan
                    .diagnostics
                    .push(format!("{api} 的 id 必须是字符串字面量")),
            }
        }
    }

    scan.diagnostics.dedup();
    scan.commands.sort();
    scan.artifacts.sort();
    scan
}

// ---------------------------------------------------------------------------
// 运行元数据与存储
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RunMeta {
    pub run_id: String,
    pub draft_id: String,
    pub name: String,
    /// queued / running / completed / errored / stopped
    pub status: String,
    /// completed / errored / stopped(user|model|provider|interrupted)
    pub stop_reason: String,
    pub started_at: u64,
    pub finished_at: Option<u64>,
    #[serde(default)]
    pub commands: Vec<String>,
    #[serde(default)]
    pub artifacts: Vec<String>,
    #[serde(default)]
    pub diagnostics: Vec<String>,
    /// 续跑/修补时的种子运行（journal 作为缓存种子）。
    #[serde(default)]
    pub seeded_from: Option<String>,
    #[serde(default)]
    pub result: Option<Value>,
    #[serde(default)]
    pub error: Option<Value>,
}

#[derive(Clone, Debug)]
pub struct RunStore {
    root: PathBuf,
}

impl RunStore {
    pub fn new(home: &Path) -> Self {
        Self {
            root: home.join("workflow-runs"),
        }
    }

    pub fn dir(&self, run_id: &str) -> PathBuf {
        self.root.join(run_id)
    }

    fn meta_path(&self, run_id: &str) -> PathBuf {
        self.dir(run_id).join("meta.json")
    }

    pub fn journal_path(&self, run_id: &str) -> PathBuf {
        self.dir(run_id).join("journal.jsonl")
    }

    pub fn prepare(&self, run_id: &str) -> Result<PathBuf> {
        let dir = self.dir(run_id);
        std::fs::create_dir_all(&dir).context("创建工作流运行目录失败")?;
        Ok(dir)
    }

    pub fn save_meta(&self, meta: &RunMeta) -> Result<()> {
        let path = self.meta_path(&meta.run_id);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(path, serde_json::to_vec_pretty(meta).context("序列化运行元数据失败")?)?;
        Ok(())
    }

    pub fn load_meta(&self, run_id: &str) -> Result<RunMeta> {
        let run_id = safe_segment(run_id)?;
        let bytes = std::fs::read(self.meta_path(&run_id))
            .with_context(|| format!("运行 {run_id} 不存在"))?;
        serde_json::from_slice(&bytes).context("运行元数据无法解析")
    }

    /// 列出最近运行：先按目录 mtime 粗筛，再按 started_at 排序。
    pub fn list(&self, limit: usize) -> Vec<RunMeta> {
        let mut runs = Vec::new();
        let Ok(entries) = std::fs::read_dir(&self.root) else {
            return runs;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|value| value.to_str()) == Some("json") {
                // 兼容早期把 meta.json 直接放在根目录的情况。
                if let Ok(bytes) = std::fs::read(&path)
                    && let Ok(meta) = serde_json::from_slice::<RunMeta>(&bytes)
                {
                    runs.push(meta);
                }
                continue;
            }
            if !path.is_dir() {
                continue;
            }
            let Some(run_id) = path.file_name().and_then(|value| value.to_str()) else {
                continue;
            };
            if let Ok(meta) = self.load_meta(run_id) {
                runs.push(meta);
            }
        }
        runs.sort_by(|left, right| right.started_at.cmp(&left.started_at));
        runs.truncate(limit);
        runs
    }

    pub fn read_journal(&self, run_id: &str, since: usize) -> Vec<Value> {
        let Ok(run_id) = safe_segment(run_id) else {
            return Vec::new();
        };
        let Ok(text) = std::fs::read_to_string(self.journal_path(&run_id)) else {
            return Vec::new();
        };
        text.lines()
            .skip(since)
            .filter_map(|line| serde_json::from_str::<Value>(line).ok())
            .collect()
    }
}

// ---------------------------------------------------------------------------
// 回放索引
// ---------------------------------------------------------------------------

#[derive(Default)]
struct ReplayIndex {
    pending: HashMap<String, Vec<Value>>,
}

impl ReplayIndex {
    /// 从 journal 建索引：只认带 key 的记账条目（世界调用 / 子代理提问 / 产物）。
    fn build(journal: &Path) -> Self {
        let mut index = Self::default();
        let Ok(text) = std::fs::read_to_string(journal) else {
            return index;
        };
        for line in text.lines() {
            let Ok(entry) = serde_json::from_str::<Value>(line) else {
                continue;
            };
            let Some(key) = entry.get("key").and_then(Value::as_str) else {
                continue;
            };
            if entry.get("cached").and_then(Value::as_bool) == Some(true) {
                continue;
            }
            let Some(result) = entry.get("result") else {
                continue;
            };
            index
                .pending
                .entry(key.to_owned())
                .or_default()
                .push(result.clone());
        }
        for bucket in index.pending.values_mut() {
            bucket.reverse();
        }
        index
    }

    fn take(&mut self, key: &str) -> Option<Value> {
        let bucket = self.pending.get_mut(key)?;
        bucket.pop()
    }
}

// ---------------------------------------------------------------------------
// 运行依赖
// ---------------------------------------------------------------------------

pub struct RunDeps {
    pub home: PathBuf,
    pub cwd: PathBuf,
    pub permission: crate::web::PermissionMode,
    pub registry: Arc<ProviderRegistry>,
    pub mcp: Arc<McpRuntime>,
    pub memory: Arc<MemoryManager>,
}

impl RunDeps {
    fn policy_mode(&self) -> AccessMode {
        match self.permission {
            crate::web::PermissionMode::Ask => AccessMode::WorkspaceWrite,
            _ => AccessMode::FullAccess,
        }
    }

    fn policy(&self) -> Result<SecurityPolicy> {
        SecurityPolicy::new(&self.cwd, self.policy_mode()).context("工作目录不可用")
    }
}

// ---------------------------------------------------------------------------
// 运行状态
// ---------------------------------------------------------------------------

struct RunState {
    run_id: String,
    meta: StdMutex<RunMeta>,
    deps: Arc<RunDeps>,
    drafts: DraftStore,
    runs: RunStore,
    /// append-only journal 写入口（小行同步写，避免额外任务）。
    journal: StdMutex<std::fs::File>,
    events: Arc<StdMutex<HashMap<String, Vec<Value>>>>,
    event_seq: AtomicU64,
    replay: Mutex<ReplayIndex>,
    commands: HashSet<String>,
    /// 产物 id -> 已发布版本。
    artifact_versions: StdMutex<HashMap<String, u32>>,
    actors: Mutex<HashMap<String, Arc<Mutex<ActorState>>>>,
}

impl RunState {
    fn emit(&self, kind: &str, mut payload: Value) -> u64 {
        let seq = self.event_seq.fetch_add(1, Ordering::SeqCst) + 1;
        if let Some(object) = payload.as_object_mut() {
            object.insert("seq".into(), json!(seq));
            object.insert("kind".into(), json!(kind));
            object.insert("ts".into(), json!(now_ts()));
            object.insert("run_id".into(), json!(self.run_id));
        }
        let line = serde_json::to_string(&payload).unwrap_or_else(|_| "{}".into());
        {
            let mut journal = self
                .journal
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let _ = writeln!(journal, "{line}");
            let _ = journal.flush();
        }
        let mut events = self
            .events
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let bucket = events.entry(self.run_id.clone()).or_default();
        bucket.push(payload);
        if bucket.len() > MAX_EVENTS_PER_RUN {
            let overflow = bucket.len() - MAX_EVENTS_PER_RUN;
            bucket.drain(0..overflow);
        }
        seq
    }

    fn set_status(&self, status: &str, stop_reason: &str) {
        let mut meta = self
            .meta
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        meta.status = status.to_owned();
        meta.stop_reason = stop_reason.to_owned();
        if matches!(status, "completed" | "errored" | "stopped") {
            meta.finished_at = Some(now_ts());
        }
        let snapshot = meta.clone();
        drop(meta);
        let _ = self.runs.save_meta(&snapshot);
    }

    fn snapshot_meta(&self) -> RunMeta {
        self.meta
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }
}

struct ActorState {
    name: String,
    system: String,
    model_selector: Option<String>,
    session: Session,
}

// ---------------------------------------------------------------------------
// 世界调用
// ---------------------------------------------------------------------------

fn resolve_inside(root: &Path, candidate: &str) -> Result<PathBuf> {
    let trimmed = candidate.trim();
    if trimmed.is_empty() {
        return Err(anyhow!("路径不能为空"));
    }
    let raw = Path::new(trimmed);
    let joined = if raw.is_absolute() {
        raw.to_path_buf()
    } else {
        root.join(raw)
    };
    let canonical_root = root
        .canonicalize()
        .with_context(|| format!("工作目录不可用：{}", root.display()))?;
    let canonical = match joined.canonicalize() {
        Ok(value) => value,
        Err(_) => {
            // 目标还不存在：只允许在已存在的最深父目录内新建。
            let mut parent = joined.parent().map(Path::to_path_buf);
            while let Some(candidate_parent) = parent.clone() {
                if candidate_parent.exists() {
                    let canonical_parent = candidate_parent
                        .canonicalize()
                        .with_context(|| format!("路径不可用：{trimmed}"))?;
                    if !canonical_parent.starts_with(&canonical_root) {
                        return Err(anyhow!("路径越界：{trimmed}"));
                    }
                    return Ok(joined);
                }
                parent = candidate_parent.parent().map(Path::to_path_buf);
            }
            return Err(anyhow!("路径不存在：{trimmed}"));
        }
    };
    if !canonical.starts_with(&canonical_root) {
        return Err(anyhow!("路径越界：工作流只能访问工作目录内的文件"));
    }
    Ok(canonical)
}

fn segment_match(pattern: &str, name: &str) -> bool {
    let pattern: Vec<char> = pattern.chars().collect();
    let name: Vec<char> = name.chars().collect();
    let mut pattern_index = 0usize;
    let mut name_index = 0usize;
    let mut star: Option<usize> = None;
    let mut star_name = 0usize;
    while name_index < name.len() {
        if pattern_index < pattern.len()
            && (pattern[pattern_index] == '?' || pattern[pattern_index] == name[name_index])
        {
            pattern_index += 1;
            name_index += 1;
        } else if pattern_index < pattern.len() && pattern[pattern_index] == '*' {
            star = Some(pattern_index);
            star_name = name_index;
            pattern_index += 1;
        } else if let Some(star_index) = star {
            pattern_index = star_index + 1;
            star_name += 1;
            name_index = star_name;
        } else {
            return false;
        }
    }
    while pattern_index < pattern.len() && pattern[pattern_index] == '*' {
        pattern_index += 1;
    }
    pattern_index == pattern.len()
}

fn glob_match(pattern: &str, path: &str) -> bool {
    let pattern_parts: Vec<&str> = pattern.split('/').filter(|part| !part.is_empty()).collect();
    let path_parts: Vec<&str> = path.split('/').filter(|part| !part.is_empty()).collect();
    fn walk(pattern: &[&str], path: &[&str]) -> bool {
        match (pattern.first(), path.first()) {
            (None, None) => true,
            (Some(&"**"), _) => {
                if walk(&pattern[1..], path) {
                    return true;
                }
                if !path.is_empty() && walk(pattern, &path[1..]) {
                    return true;
                }
                false
            }
            (Some(segment), Some(name)) => {
                segment_match(segment, name) && walk(&pattern[1..], &path[1..])
            }
            _ => false,
        }
    }
    walk(&pattern_parts, &path_parts)
}

fn walk_files(root: &Path, depth_limit: usize) -> Result<Vec<String>> {
    let mut files = Vec::new();
    let mut stack = vec![(root.to_path_buf(), 0usize)];
    let mut visited = 0usize;
    while let Some((dir, depth)) = stack.pop() {
        if depth > depth_limit {
            continue;
        }
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            if WALK_SKIP.contains(&name.as_str()) {
                continue;
            }
            visited += 1;
            if visited > MAX_WALK_FILES {
                return Err(anyhow!("目录内文件过多（超过 {MAX_WALK_FILES} 项），请收窄 pattern"));
            }
            let Ok(kind) = entry.file_type() else {
                continue;
            };
            if kind.is_dir() {
                stack.push((path, depth + 1));
            } else if kind.is_file() {
                let Ok(relative) = path.strip_prefix(root) else {
                    continue;
                };
                files.push(relative.to_string_lossy().replace('\\', "/"));
            }
        }
    }
    files.sort();
    Ok(files)
}

fn truncate_text(value: &str, limit: usize) -> (String, bool) {
    if value.len() <= limit {
        return (value.to_owned(), false);
    }
    let mut cut = limit;
    while cut > 0 && !value.is_char_boundary(cut) {
        cut -= 1;
    }
    (format!("{}\n…（已截断）", &value[..cut]), true)
}

async fn execute_world(state: &RunState, op: &str, payload: &Value) -> Result<Value> {
    let root = state.deps.cwd.clone();
    match op {
        "run" => {
            let command = payload
                .get("cmd")
                .and_then(Value::as_str)
                .ok_or_else(|| anyhow!("world.run 缺少命令名"))?;
            if !state.commands.contains(command) {
                return Err(anyhow!(
                    "命令 `{command}` 不在本次运行的批准名单里（白名单来自脚本里的字面量）：{:?}",
                    state.commands
                ));
            }
            let args: Vec<String> = payload
                .get("args")
                .and_then(Value::as_array)
                .map(|items| {
                    items
                        .iter()
                        .map(|item| match item.as_str() {
                            Some(text) => text.to_owned(),
                            None => item.to_string(),
                        })
                        .collect()
                })
                .unwrap_or_default();
            let timeout_ms = payload.get("timeoutMs").and_then(Value::as_u64).unwrap_or(0);
            let timeout = if timeout_ms > 0 {
                Duration::from_millis(timeout_ms.min(600_000))
            } else {
                WORLD_TIMEOUT
            };
            let mut command_process = Command::new(command);
            command_process
                .args(&args)
                .current_dir(&root)
                .env_clear()
                .env("PATH", "/usr/local/bin:/usr/bin:/bin:/system/bin")
                .env("HOME", &state.deps.home)
                .env("LANG", "C.UTF-8")
                .env("COOMI_WORKFLOW_RUN", &state.run_id)
                .stdin(std::process::Stdio::null())
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::piped())
                .kill_on_drop(true);
            let started = now_ts();
            let output = tokio::time::timeout(timeout, command_process.output())
                .await
                .map_err(|_| anyhow!("命令 `{command}` 超时（{} 秒）", timeout.as_secs()))?
                .with_context(|| format!("命令 `{command}` 无法启动"))?;
            let (stdout, stdout_truncated) =
                truncate_text(&String::from_utf8_lossy(&output.stdout), MAX_COMMAND_OUTPUT);
            let (stderr, stderr_truncated) =
                truncate_text(&String::from_utf8_lossy(&output.stderr), MAX_COMMAND_OUTPUT);
            let elapsed_ms = (now_ts().saturating_sub(started)) * 1000;
            Ok(json!({
                "exitCode": output.status.code().unwrap_or(-1),
                "stdout": stdout,
                "stderr": stderr,
                "truncated": stdout_truncated || stderr_truncated,
                "elapsedMs": elapsed_ms,
            }))
        }
        "files.read" => {
            let raw = payload
                .get("path")
                .and_then(Value::as_str)
                .ok_or_else(|| anyhow!("files.read 缺少路径"))?;
            let path = resolve_inside(&root, raw)?;
            let meta = std::fs::metadata(&path)
                .with_context(|| format!("文件不可读：{raw}"))?;
            if meta.len() > MAX_READ_BYTES {
                return Err(anyhow!(
                    "文件过大（{} 字节 > 上限 {MAX_READ_BYTES} 字节），请改用 world.run 分批处理",
                    meta.len()
                ));
            }
            let text = std::fs::read_to_string(&path).with_context(|| format!("文件不是 UTF-8 文本：{raw}"))?;
            Ok(json!({ "path": raw, "content": text, "bytes": meta.len() }))
        }
        "files.glob" => {
            let pattern = payload
                .get("pattern")
                .and_then(Value::as_str)
                .unwrap_or("**/*");
            let files = walk_files(&root, 16)?;
            let matched: Vec<String> = files
                .into_iter()
                .filter(|item| glob_match(pattern, item))
                .collect();
            if matched.len() > MAX_GLOB_RESULTS {
                return Err(anyhow!(
                    "匹配到 {} 个文件（上限 {MAX_GLOB_RESULTS}）：容量上限拒绝而非截断，请收窄 pattern",
                    matched.len()
                ));
            }
            Ok(json!({ "pattern": pattern, "files": matched }))
        }
        "files.grep" => {
            let pattern = payload
                .get("pattern")
                .and_then(Value::as_str)
                .unwrap_or_default();
            let glob = payload.get("glob").and_then(Value::as_str).unwrap_or("**/*");
            let limit = payload
                .get("limit")
                .and_then(Value::as_u64)
                .unwrap_or(MAX_GREP_RESULTS as u64)
                .min(MAX_GREP_RESULTS as u64) as usize;
            let matcher = regex::Regex::new(pattern).context("grep 正则无法编译")?;
            let files = walk_files(&root, 16)?;
            let mut hits = Vec::new();
            for relative in files.into_iter().filter(|item| glob_match(glob, item)) {
                if hits.len() >= limit {
                    break;
                }
                let path = root.join(&relative);
                let Ok(meta) = std::fs::metadata(&path) else {
                    continue;
                };
                if meta.len() > MAX_READ_BYTES {
                    continue;
                }
                let Ok(text) = std::fs::read_to_string(&path) else {
                    continue;
                };
                for (number, line) in text.lines().enumerate() {
                    if hits.len() >= limit {
                        break;
                    }
                    if matcher.is_match(line) {
                        hits.push(json!({
                            "path": relative,
                            "line": number + 1,
                            "text": line.chars().take(400).collect::<String>(),
                        }));
                    }
                }
            }
            Ok(json!({ "pattern": pattern, "glob": glob, "hits": hits }))
        }
        "git.changedFiles" => {
            let reference = payload.get("ref").and_then(Value::as_str).unwrap_or("HEAD");
            let output = Command::new("git")
                .args(["diff", "--name-only", reference])
                .current_dir(&root)
                .env("PATH", "/usr/local/bin:/usr/bin:/bin")
                .stdin(std::process::Stdio::null())
                .output()
                .await
                .context("git 无法启动")?;
            if !output.status.success() {
                return Err(anyhow!(
                    "git.changedFiles 失败：{}",
                    String::from_utf8_lossy(&output.stderr).trim()
                ));
            }
            let files: Vec<String> = String::from_utf8_lossy(&output.stdout)
                .lines()
                .map(str::trim)
                .filter(|line| !line.is_empty())
                .map(str::to_owned)
                .collect();
            Ok(json!({ "ref": reference, "files": files }))
        }
        other => Err(anyhow!("未支持的世界调用：{other}")),
    }
}

// ---------------------------------------------------------------------------
// 子代理（actor）
// ---------------------------------------------------------------------------

struct ActorObserver {
    events: Arc<StdMutex<HashMap<String, Vec<Value>>>>,
    run_id: String,
    actor: String,
}

impl ActorObserver {
    fn push(&self, event: Value) {
        let mut events = self
            .events
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let bucket = events.entry(self.run_id.clone()).or_default();
        let mut payload = event;
        if let Some(object) = payload.as_object_mut() {
            object.insert("kind".into(), json!("actor"));
            object.insert("actor".into(), json!(self.actor));
            object.insert("ts".into(), json!(now_ts()));
        }
        bucket.push(payload);
        if bucket.len() > MAX_EVENTS_PER_RUN {
            let overflow = bucket.len() - MAX_EVENTS_PER_RUN;
            bucket.drain(0..overflow);
        }
    }
}

impl AgentObserver for ActorObserver {
    fn on_event(&self, event: &AgentEvent) {
        match event {
            AgentEvent::ToolStarted(call) => {
                self.push(json!({"event": "tool_start", "tool": call.name}));
            }
            AgentEvent::ToolFinished { call, result } => {
                self.push(json!({
                    "event": "tool_done",
                    "tool": call.name,
                    "isError": !result.success,
                    "preview": result.output.chars().take(240).collect::<String>(),
                }));
            }
            AgentEvent::ModelStarted { round, model, .. } => {
                self.push(json!({"event": "model", "round": round, "model": model}));
            }
            AgentEvent::CompactionCompleted { before_tokens, after_tokens, .. } => {
                self.push(json!({
                    "event": "compacted",
                    "before": before_tokens,
                    "after": after_tokens,
                }));
            }
            _ => {}
        }
    }
}

/// 工作流里的子代理已由用户批准的脚本 + 命令白名单背书，工具调用不再逐个打断：
/// 权限档位仍在 RunDeps::policy_mode 里生效（见 RunDeps::policy）。
struct RunApproval;

#[async_trait]
impl ApprovalHandler for RunApproval {
    async fn approve(&self, _call: &ToolCall, _reason: &str) -> bool {
        true
    }
}

fn extract_json(text: &str) -> Option<Value> {
    let trimmed = text.trim();
    let without_fence = if let Some(start) = trimmed.find("```") {
        let rest = &trimmed[start + 3..];
        let body = rest.split("```").next().unwrap_or("");
        body.trim_start_matches("json").trim().to_owned()
    } else {
        trimmed.to_owned()
    };
    if let Ok(value) = serde_json::from_str::<Value>(&without_fence) {
        return Some(value);
    }
    let start = without_fence.find(['{', '['])?;
    let end = without_fence.rfind(['}', ']'])?;
    if end <= start {
        return None;
    }
    serde_json::from_str(&without_fence[start..=end]).ok()
}

/// 极简 JSON Schema 校验（type / required / properties / items / enum）：
/// 只做能确定判定的部分，判不了的不假装判过。
fn validate_schema(value: &Value, schema: &Value) -> Result<()> {
    let Some(kind) = schema.get("type").and_then(Value::as_str) else {
        return Ok(());
    };
    let ok = match kind {
        "object" => value.is_object(),
        "array" => value.is_array(),
        "string" => value.is_string(),
        "number" => value.is_number(),
        "integer" => value.as_i64().is_some() || value.as_u64().is_some(),
        "boolean" => value.is_boolean(),
        "null" => value.is_null(),
        _ => true,
    };
    if !ok {
        return Err(anyhow!("期望 {kind}，实际是 {}", json_type(value)));
    }
    if let Some(required) = schema.get("required").and_then(Value::as_array) {
        for field in required.iter().filter_map(Value::as_str) {
            if value.get(field).is_none() {
                return Err(anyhow!("缺少必填字段 `{field}`"));
            }
        }
    }
    if let Some(properties) = schema.get("properties").and_then(Value::as_object) {
        for (field, sub) in properties {
            if let Some(field_value) = value.get(field) {
                validate_schema(field_value, sub)?;
            }
        }
    }
    if let Some(items) = schema.get("items") {
        if let Some(list) = value.as_array() {
            for item in list {
                validate_schema(item, items)?;
            }
        }
    }
    if let Some(allowed) = schema.get("enum").and_then(Value::as_array)
        && !allowed.iter().any(|candidate| candidate == value)
    {
        return Err(anyhow!("取值不在 enum 允许范围内"));
    }
    Ok(())
}

fn json_type(value: &Value) -> &'static str {
    match value {
        Value::Null => "null",
        Value::Bool(_) => "boolean",
        Value::Number(_) => "number",
        Value::String(_) => "string",
        Value::Array(_) => "array",
        Value::Object(_) => "object",
    }
}

// ---------------------------------------------------------------------------
// 引擎主体
// ---------------------------------------------------------------------------

#[derive(Default)]
pub struct DynamicWorkflows {
    home: PathBuf,
    events: Arc<StdMutex<HashMap<String, Vec<Value>>>>,
    live: Arc<StdMutex<HashMap<String, tokio::task::AbortHandle>>>,
}

impl DynamicWorkflows {
    pub fn new(home: &Path) -> Self {
        Self {
            home: home.to_path_buf(),
            events: Arc::new(StdMutex::new(HashMap::new())),
            live: Arc::new(StdMutex::new(HashMap::new())),
        }
    }

    pub fn drafts(&self) -> DraftStore {
        DraftStore::new(&self.home)
    }

    pub fn runs(&self) -> RunStore {
        RunStore::new(&self.home)
    }

    pub fn events_since(&self, run_id: &str, since: usize) -> Vec<Value> {
        self.events
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(run_id)
            .map(|bucket| bucket.iter().skip(since).cloned().collect())
            .unwrap_or_default()
    }

    pub fn is_running(&self, run_id: &str) -> bool {
        self.live
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .contains_key(run_id)
    }

    pub fn has_capacity(&self) -> bool {
        self.live
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .len()
            < 2
    }

    /// 停止一次运行：取消任务（子进程随之被 kill_on_drop 收走）。
    pub fn stop(&self, run_id: &str) -> bool {
        let handle = self
            .live
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(run_id);
        match handle {
            Some(handle) => {
                handle.abort();
                true
            }
            None => false,
        }
    }

    /// 启动一次运行。`seed_from` 非空时把该运行的 journal 作为缓存种子（续跑/修补）。
    pub fn start(
        self: &Arc<Self>,
        deps: Arc<RunDeps>,
        draft: &DraftMeta,
        script: &str,
        scan: &ScriptScan,
        seed_from: Option<String>,
    ) -> Result<String> {
        if !scan.diagnostics.is_empty() {
            return Err(anyhow!("脚本未通过编译门：{}", scan.diagnostics.join("；")));
        }
        if !self.has_capacity() {
            return Err(anyhow!("同时运行的工作流已达上限（2），请等一个跑完"));
        }
        let run_id = uuid::Uuid::new_v4().simple().to_string();
        let runs = self.runs();
        runs.prepare(&run_id)?;
        let meta = RunMeta {
            run_id: run_id.clone(),
            draft_id: draft.id.clone(),
            name: draft.name.clone(),
            status: "queued".into(),
            stop_reason: String::new(),
            started_at: now_ts(),
            finished_at: None,
            commands: scan.commands.clone(),
            artifacts: scan.artifacts.clone(),
            diagnostics: Vec::new(),
            seeded_from: seed_from.clone(),
            result: None,
            error: None,
        };
        runs.save_meta(&meta)?;
        std::fs::write(runs.dir(&run_id).join("script.mjs"), script)?;
        std::fs::write(runs.dir(&run_id).join("sandbox.cjs"), SANDBOX_SOURCE)?;
        if let Some(seed) = &seed_from {
            let seed_journal = runs.journal_path(seed);
            if seed_journal.is_file() {
                let _ = std::fs::copy(&seed_journal, runs.journal_path(&run_id));
            }
        }
        self.events
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(run_id.clone(), Vec::new());

        let service = Arc::clone(self);
        let task_run_id = run_id.clone();
        let scan_commands = scan.commands.clone();
        let spawned = tokio::spawn(async move {
            let outcome = run_script(&service, &task_run_id, meta, deps, scan_commands).await;
            service
                .events
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .retain(|key, _| key != &task_run_id);
            service
                .live
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .remove(&task_run_id);
            if let Err(error) = outcome {
                eprintln!("[workflow] run {task_run_id} failed: {error:#}");
            }
        });
        self.live
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(run_id.clone(), spawned.abort_handle());
        Ok(run_id)
    }
}

/// 沙箱运行时（node）是否可用：UI 用它决定要不要提示"装 node 才能跑工作流"。
pub fn sandbox_runtime_available() -> bool {
    resolve_node().is_some()
}

fn resolve_node() -> Option<PathBuf> {
    if let Ok(custom) = std::env::var("COOMI_WORKFLOW_NODE") {
        let path = PathBuf::from(custom);
        if path.is_file() {
            return Some(path);
        }
    }
    for candidate in [
        "/usr/bin/node",
        "/usr/local/bin/node",
        "/bin/node",
        "/data/data/com.termux/files/usr/bin/node",
    ] {
        let path = PathBuf::from(candidate);
        if path.is_file() {
            return Some(path);
        }
    }
    None
}

async fn run_script(
    service: &Arc<DynamicWorkflows>,
    run_id: &str,
    meta: RunMeta,
    deps: Arc<RunDeps>,
    commands: Vec<String>,
) -> Result<()> {
    let runs = service.runs();
    let dir = runs.dir(run_id);
    let journal_file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(runs.journal_path(run_id))
        .context("打开 journal 失败")?;
    let state = Arc::new(RunState {
        run_id: run_id.to_owned(),
        meta: StdMutex::new(meta),
        deps: Arc::clone(&deps),
        drafts: service.drafts(),
        runs: RunStore::new(&service.home),
        journal: StdMutex::new(journal_file),
        events: Arc::clone(&service.events),
        event_seq: AtomicU64::new(0),
        replay: Mutex::new(ReplayIndex::build(&runs.journal_path(run_id))),
        commands: commands.into_iter().collect(),
        artifact_versions: StdMutex::new(HashMap::new()),
        actors: Mutex::new(HashMap::new()),
    });

    // 先把 journal 里已有的条目序号对齐，避免新事件的 seq 与旧条目撞车。
    let existing = runs.read_journal(run_id, 0).len() as u64;
    state.event_seq.store(existing, Ordering::SeqCst);

    let Some(node) = resolve_node() else {
        state.emit(
            "error",
            json!({"message": "找不到 node 运行时（动态工作流沙箱需要它）"}),
        );
        state.set_status("errored", "errored");
        return Ok(());
    };
    let script_path = dir.join("script.mjs");
    let sandbox_path = dir.join("sandbox.cjs");
    state.set_status("running", "");
    state.emit(
        "start",
        json!({
            "name": state.snapshot_meta().name,
            "commands": state.commands.iter().cloned().collect::<Vec<_>>(),
        }),
    );

    let mut child = Command::new(&node)
        .arg(&sandbox_path)
        .arg(&script_path)
        .current_dir(&dir)
        .env_clear()
        .env("PATH", "/usr/local/bin:/usr/bin:/bin")
        .env("HOME", &deps.home)
        .env("LANG", "C.UTF-8")
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .context("启动沙箱失败")?;

    let stdin = child.stdin.take().context("沙箱 stdin 不可用")?;
    let stdin = Arc::new(Mutex::new(stdin));
    let stdout = child.stdout.take().context("沙箱 stdout 不可用")?;
    let stderr = child.stderr.take();
    if let Some(stderr) = stderr {
        let state = Arc::clone(&state);
        tokio::spawn(async move {
            let mut lines = BufReader::new(stderr).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if line.trim().is_empty() {
                    continue;
                }
                state.emit("stderr", json!({"message": line.chars().take(2000).collect::<String>()}));
            }
        });
    }

    let replies = Arc::clone(&stdin);
    let mut lines = BufReader::new(stdout).lines();
    let mut inflight: Vec<tokio::task::JoinHandle<()>> = Vec::new();
    loop {
        let line = lines.next_line().await;
        let line = match line {
            Ok(Some(line)) => line,
            Ok(None) => break,
            Err(error) => {
                state.emit("error", json!({"message": format!("读取沙箱输出失败：{error}")}));
                break;
            }
        };
        if line.trim().is_empty() {
            continue;
        }
        let Ok(message) = serde_json::from_str::<Value>(&line) else {
            state.emit("log", json!({"message": line.chars().take(2000).collect::<String>()}));
            continue;
        };
        match message.get("type").and_then(Value::as_str).unwrap_or_default() {
            "phase" => {
                state.emit(
                    "phase",
                    json!({"name": message.get("name").and_then(Value::as_str).unwrap_or("")}),
                );
            }
            "log" => {
                state.emit(
                    "log",
                    json!({"message": message.get("message").and_then(Value::as_str).unwrap_or("")}),
                );
            }
            "report" => {
                state.emit(
                    "report",
                    json!({
                        "item": message.get("item").cloned().unwrap_or(Value::Null),
                        "tag": message.get("tag").and_then(Value::as_str).unwrap_or(""),
                    }),
                );
            }
            "call" => {
                let state = Arc::clone(&state);
                let replies = Arc::clone(&replies);
                inflight.push(tokio::spawn(async move {
                    let req_id = message.get("reqId").and_then(Value::as_u64).unwrap_or(0);
                    let reply = handle_call(&state, &message).await;
                    let mut stdin = replies.lock().await;
                    let payload = match reply {
                        Ok(result) => json!({"type": "deliver", "reqId": req_id, "result": result}),
                        Err(error) => json!({"type": "deliver", "reqId": req_id, "error": format!("{error:#}")}),
                    };
                    let _ = stdin.write_all(format!("{payload}\n").as_bytes()).await;
                    let _ = stdin.flush().await;
                }));
            }
            "complete" => {
                let ok = message.get("ok").and_then(Value::as_bool).unwrap_or(false);
                if ok {
                    state.emit(
                        "complete",
                        json!({"result": message.get("result").cloned().unwrap_or(Value::Null)}),
                    );
                    let mut meta = state
                        .meta
                        .lock()
                        .unwrap_or_else(|poisoned| poisoned.into_inner());
                    meta.result = Some(message.get("result").cloned().unwrap_or(Value::Null));
                    drop(meta);
                    state.set_status("completed", "completed");
                } else {
                    let error = message.get("error").cloned().unwrap_or(Value::Null);
                    state.emit("error", json!({"message": error.get("message").and_then(Value::as_str).unwrap_or("脚本执行失败"), "detail": error}));
                    let mut meta = state
                        .meta
                        .lock()
                        .unwrap_or_else(|poisoned| poisoned.into_inner());
                    meta.error = Some(error);
                    drop(meta);
                    state.set_status("errored", "errored");
                }
                break;
            }
            _ => {}
        }
    }
    for handle in inflight {
        let _ = handle.await;
    }
    let _ = child.wait().await;
    let status = state.snapshot_meta().status;
    if status == "running" || status == "queued" {
        state.emit("error", json!({"message": "沙箱提前退出（进程被杀或崩溃）"}));
        state.set_status("errored", "interrupted");
    }
    Ok(())
}

async fn handle_call(state: &Arc<RunState>, message: &Value) -> Result<Value> {
    let kind = message.get("kind").and_then(Value::as_str).unwrap_or_default();
    let key = message.get("key").and_then(Value::as_str).unwrap_or_default();
    if message.get("site").and_then(Value::as_str).unwrap_or("").is_empty() {
        return Err(anyhow!("调用点身份缺失，拒绝执行以避免不可回放"));
    }
    // 1) 回放优先：journal 里记过的调用直接取记录值，绝不重跑副作用。
    {
        let mut replay = state.replay.lock().await;
        if let Some(recorded) = replay.take(key) {
            state.emit(
                kind_event(kind),
                json!({"op": message.get("op").cloned().unwrap_or(Value::Null), "key": key, "cached": true}),
            );
            return Ok(recorded);
        }
    }
    // 2) 未命中 → 执行并记账。
    match kind {
        "world" => {
            let op = message.get("op").and_then(Value::as_str).unwrap_or_default();
            let result = execute_world(state, op, message).await;
            match result {
                Ok(value) => {
                    state.emit(
                        "world",
                        json!({
                            "op": op,
                            "key": key,
                            "cmd": message.get("cmd").cloned().unwrap_or(Value::Null),
                            "args": message.get("args").cloned().unwrap_or(Value::Null),
                            "result": value,
                            "cached": false,
                        }),
                    );
                    Ok(value)
                }
                Err(error) => {
                    state.emit(
                        "world_failed",
                        json!({"op": op, "key": key, "message": format!("{error:#}")}),
                    );
                    Err(error)
                }
            }
        }
        "ask" => {
            let actor = message.get("agent").and_then(Value::as_str).unwrap_or("匿名");
            state.emit(
                "ask_start",
                json!({"actor": actor, "key": key, "prompt": message.get("prompt").cloned().unwrap_or(Value::Null)}),
            );
            let result = run_actor(state, message).await;
            match result {
                Ok(value) => {
                    state.emit(
                        "ask",
                        json!({"actor": actor, "key": key, "result": value, "cached": false}),
                    );
                    Ok(value)
                }
                Err(error) => {
                    state.emit(
                        "ask_failed",
                        json!({"actor": actor, "key": key, "message": format!("{error:#}")}),
                    );
                    Err(error)
                }
            }
        }
        "artifact" => {
            let id = message.get("id").and_then(Value::as_str).unwrap_or("").to_owned();
            let version = {
                let mut versions = state
                    .artifact_versions
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                let entry = versions.entry(id.clone()).or_insert(0);
                *entry += 1;
                *entry
            };
            let payload = json!({
                "id": id,
                "version": version,
                "artifactKind": message
                    .get("artifactKind")
                    .or_else(|| message.get("kind"))
                    .cloned()
                    .unwrap_or(Value::Null),
                "title": message.get("title").cloned().unwrap_or(Value::Null),
                "path": message.get("path").cloned().unwrap_or(Value::Null),
                "body": message.get("body").cloned().unwrap_or(Value::Null),
                "spec": message.get("spec").cloned().unwrap_or(Value::Null),
                "primary": message.get("primary").cloned().unwrap_or(Value::Bool(false)),
                "key": key,
            });
            state.emit("artifact", payload.clone());
            let value = json!({"id": payload["id"], "version": version});
            state.emit(
                "artifact_record",
                json!({"key": key, "result": value, "cached": false}),
            );
            Ok(value)
        }
        other => Err(anyhow!("未支持的门面调用：{other}")),
    }
}

fn kind_event(kind: &str) -> &'static str {
    match kind {
        "world" => "world",
        "ask" => "ask",
        "artifact" => "artifact_record",
        _ => "call",
    }
}

async fn run_actor(state: &Arc<RunState>, message: &Value) -> Result<Value> {
    let name = message
        .get("agent")
        .and_then(Value::as_str)
        .unwrap_or("匿名")
        .to_owned();
    let persona = message.get("persona").cloned().unwrap_or(Value::Null);
    let prompt = message
        .get("prompt")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned();
    if prompt.trim().is_empty() {
        return Err(anyhow!("子代理提问不能为空"));
    }
    let schema = message.get("schema").cloned().filter(|value| !value.is_null());

    let slot = {
        let mut actors = state.actors.lock().await;
        match actors.get(&name) {
            Some(existing) => Arc::clone(existing),
            None => {
                let system = actor_system_prompt(&name, &persona);
                let model_selector = persona
                    .get("model")
                    .and_then(Value::as_str)
                    .map(str::trim)
                    .filter(|value| !value.is_empty())
                    .map(str::to_owned);
                let provider_selector = model_selector.clone();
                let resolved = state.deps.registry.resolve(provider_selector.as_deref())?;
                let session = Session::new(
                    resolved.id.clone(),
                    resolved.model.clone(),
                    state.deps.cwd.clone(),
                );
                let created = Arc::new(Mutex::new(ActorState {
                    name: name.clone(),
                    system,
                    model_selector,
                    session,
                }));
                actors.insert(name.clone(), Arc::clone(&created));
                created
            }
        }
    };

    // 同名子代理 = 同一场会话 = FIFO：锁住它，后来的提问排队。
    let mut actor = slot.lock().await;
    if let Some(selector) = actor.model_selector.as_deref() {
        let resolved = state.deps.registry.resolve(Some(selector))?;
        actor.session.provider_id = resolved.id.clone();
        actor.session.model = resolved.model.clone();
    }
    let provider_config = state
        .deps
        .registry
        .resolve(Some(&format!("{}:{}", actor.session.provider_id, actor.session.model)))
        .or_else(|_| state.deps.registry.resolve(None))?;
    let provider = HttpModelProvider::new(provider_config)?;
    let policy = state.deps.policy()?;
    let tools = CoreTools::new(state.deps.cwd.clone(), policy)
        .with_skills_directory(state.deps.home.join("skills"))
        .with_config_home(state.deps.home.clone())
        .with_mcp_runtime(Arc::clone(&state.deps.mcp))
        .with_memory(Arc::clone(&state.deps.memory));
    let observer = ActorObserver {
        events: Arc::clone(&state.events),
        run_id: state.run_id.clone(),
        actor: name.clone(),
    };
    let user = match &schema {
        Some(schema) => format!(
            "{prompt}\n\n【输出要求】只输出一个 JSON 值，且必须满足下面的 JSON Schema，不要输出解释或代码围栏：\n{}",
            serde_json::to_string_pretty(schema).unwrap_or_default()
        ),
        None => prompt.clone(),
    };
    let agent = Agent::new(actor.system.clone())
        .with_max_tool_rounds(32)
        .with_reasoning_effort("medium");
    let run = agent.run_turn(
        &mut actor.session,
        user,
        &provider,
        &tools,
        &RunApproval,
        &observer,
    );
    let text = tokio::time::timeout(ACTOR_TIMEOUT, run)
        .await
        .map_err(|_| anyhow!("子代理 `{name}` 超时（{} 秒）", ACTOR_TIMEOUT.as_secs()))?
        .map_err(|error| anyhow!("子代理 `{name}` 执行失败：{error:#}"))?;

    match schema {
        Some(schema) => {
            let value = extract_json(&text)
                .ok_or_else(|| anyhow!("子代理 `{name}` 没有返回可解析的 JSON"))?;
            validate_schema(&value, &schema)
                .map_err(|error| anyhow!("子代理 `{name}` 的结构化结果不合法：{error}"))?;
            Ok(json!({"data": value, "text": text, "actor": name}))
        }
        None => Ok(json!({"text": text, "actor": name})),
    }
}

fn actor_system_prompt(name: &str, persona: &Value) -> String {
    let extra = persona
        .get("system")
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim()
        .to_owned();
    let mut prompt = format!(
        "你是动态工作流里的子代理「{name}」。你只负责被问到的那一件事，回答要能被脚本直接使用。\n\
         工作目录：{cwd}\n\
         - 需要事实就用工具去查，不要凭印象下结论；\n\
         - 结论要带证据（命令、文件、行号），判不了的写「未确认」；\n\
         - 只回答被问的问题，不要复述任务背景，不要寒暄；\n\
         - 被要求结构化输出时严格按给出的 JSON Schema 输出 JSON。",
        cwd = "当前会话工作目录"
    );
    if !extra.is_empty() {
        prompt.push_str("\n\n【角色设定】\n");
        prompt.push_str(&extra);
    }
    prompt
}

// ---------------------------------------------------------------------------
// 测试
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scan_collects_command_whitelist_and_rejects_non_literals() {
        let scan = scan_script(
            "phase('a');\nconst r = await world.run(\"ls\", [\"-1\"]);\nreport(r);\n// world.run(\"rm\", []) 注释里的不算\n",
        );
        assert_eq!(scan.commands, vec!["ls".to_owned()]);
        assert!(scan.diagnostics.is_empty(), "{:?}", scan.diagnostics);

        let bad = scan_script("const cmd = 'ls';\nawait world.run(cmd, []);\n");
        assert!(
            bad.diagnostics.iter().any(|item| item.contains("字面量")),
            "{:?}",
            bad.diagnostics
        );
    }

    #[test]
    fn scan_rejects_nondeterministic_and_escaped_globals() {
        let scan = scan_script("const t = Date.now();\nconst p = Math.random();\nconst m = require('fs');\n");
        assert!(scan.diagnostics.iter().any(|item| item.contains("Date.now")));
        assert!(scan.diagnostics.iter().any(|item| item.contains("Math.random")));
        assert!(scan.diagnostics.iter().any(|item| item.contains("require()")));
    }

    #[test]
    fn scan_reads_artifact_ids_from_literals() {
        let scan = scan_script("artifact.markdown('report', '# hi', { primary: true });\n");
        assert_eq!(scan.artifacts, vec!["report".to_owned()]);
        assert!(scan.diagnostics.is_empty());
    }

    #[test]
    fn glob_matches_double_star_and_extension() {
        assert!(glob_match("**/*.ts", "src/a/b.ts"));
        assert!(glob_match("src/*.ts", "src/a.ts"));
        assert!(!glob_match("src/*.ts", "src/a/b.ts"));
        assert!(glob_match("**/*", "a.txt"));
        assert!(glob_match("*.md", "README.md"));
    }

    #[test]
    fn replay_index_returns_recorded_results_in_order() {
        let dir = tempfile::tempdir().expect("tempdir");
        let journal = dir.path().join("journal.jsonl");
        std::fs::write(
            &journal,
            concat!(
                "{\"key\":\"world@a.mjs:1:1#1\",\"result\":{\"n\":1}}\n",
                "{\"key\":\"world@a.mjs:1:1#1\",\"result\":{\"n\":2}}\n",
                "{\"key\":\"ask@a.mjs:2:1#1\",\"result\":{\"text\":\"ok\"},\"cached\":false}\n",
                "{\"key\":\"world@a.mjs:3:1#1\",\"result\":{},\"cached\":true}\n",
            ),
        )
        .expect("write journal");
        let mut index = ReplayIndex::build(&journal);
        assert_eq!(index.take("world@a.mjs:1:1#1").expect("first")["n"], 1);
        assert_eq!(index.take("world@a.mjs:1:1#1").expect("second")["n"], 2);
        assert!(index.take("world@a.mjs:1:1#1").is_none());
        assert_eq!(index.take("ask@a.mjs:2:1#1").expect("ask")["text"], "ok");
        // cached 条目不再作为回放来源，避免把回放当成新事实重复下发。
        assert!(index.take("world@a.mjs:3:1#1").is_none());
    }

    #[test]
    fn schema_validation_flags_missing_fields() {
        let schema = json!({
            "type": "object",
            "required": ["conclusion"],
            "properties": { "conclusion": { "type": "string" } }
        });
        assert!(validate_schema(&json!({"conclusion": "ok"}), &schema).is_ok());
        assert!(validate_schema(&json!({"findings": []}), &schema).is_err());
        assert!(validate_schema(&json!({"conclusion": 3}), &schema).is_err());
    }

    #[test]
    fn extract_json_handles_code_fences() {
        let value = extract_json("说明\n```json\n{\"a\": 1}\n```\n").expect("json");
        assert_eq!(value["a"], 1);
        assert!(extract_json("没有 JSON").is_none());
    }

    #[test]
    fn path_outside_workspace_is_rejected() {
        let dir = tempfile::tempdir().expect("tempdir");
        let error = resolve_inside(dir.path(), "../../etc/passwd").expect_err("must reject");
        assert!(format!("{error:#}").contains("越界"));
    }
}
