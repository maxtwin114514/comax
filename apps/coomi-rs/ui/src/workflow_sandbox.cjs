#!/usr/bin/env node
'use strict';
/**
 * Coomi 动态工作流沙箱（M1）。
 *
 * 职责：把用户脚本放进一个受控的 vm 上下文执行，脚本自己 **不做任何世界访问** ——
 * 读写文件、跑命令、问子代理、发产物，全部经下面的门面原语打到 stdout 行协议，
 * 由引擎侧（Rust）代为执行并记账（journal），恢复运行时命中即回放、绝不重跑副作用。
 *
 * 不变量（与 dynamic-workflow-engine-spec-for-pi.md §10 对齐）：
 *  1. 脚本零副作用原语，世界访问全走记账门面；
 *  2. 缓存键 = kind + 调用点(file:line:col) + 出现序号，引擎据此回放；
 *  3. 世界调用回放不重跑；
 *  4. 非确定性运行时拦截：Date.now() / new Date() / Math.random() 直接抛错。
 *
 * 用法：node sandbox.cjs <script.mjs> [--run-dir <dir>]
 * 协议：stdout 每行一个 JSON 对象 {type:"call"|"phase"|...}；stdin 每行一个
 *       {"type":"deliver","reqId":N,"result":...} 或 {"type":"cancel"}。
 */

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const argv = process.argv.slice(2);
const scriptPath = argv[0];
if (!scriptPath) {
  process.stdout.write(JSON.stringify({ type: 'complete', ok: false, error: { name: 'Usage', message: '缺少脚本路径' } }) + '\n');
  process.exit(2);
}

// ── stdio 行协议 ─────────────────────────────────────────────
const pending = new Map();
const instanceCounters = new Map();

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function siteId() {
  const stack = new Error().stack || '';
  for (const raw of stack.split('\n').slice(1)) {
    const line = raw.trim();
    const match = /\(?([^()\s]+):(\d+):(\d+)\)?$/.exec(line);
    if (!match) continue;
    const file = match[1];
    if (file === __filename || file.startsWith('node:')) continue;
    return `${path.basename(file)}:${match[2]}:${match[3]}`;
  }
  return 'inline:0:0';
}

/** 调用点身份 + 出现序号：引擎的 journal / 回放都以这个键索引。 */
function callKey(kind) {
  const site = siteId();
  const bucket = `${kind}@${site}`;
  const instance = (instanceCounters.get(bucket) || 0) + 1;
  instanceCounters.set(bucket, instance);
  return { key: `${bucket}#${instance}`, site };
}

let reqSeq = 0;
function nextReqId() { reqSeq += 1; return reqSeq; }

function call(kind, payload) {
  const { key, site } = callKey(kind);
  const reqId = nextReqId();
  return new Promise((resolve, reject) => {
    pending.set(reqId, { resolve, reject });
    send({ type: 'call', reqId, kind, key, site, ...payload });
  });
}

let cancelRequested = false;
process.stdin.setEncoding('utf8');
let inbox = '';
process.stdin.on('data', chunk => {
  inbox += chunk;
  let index = inbox.indexOf('\n');
  while (index >= 0) {
    const line = inbox.slice(0, index);
    inbox = inbox.slice(index + 1);
    index = inbox.indexOf('\n');
    if (!line.trim()) continue;
    let message;
    try { message = JSON.parse(line); } catch { continue; }
    if (message.type === 'cancel') {
      cancelRequested = true;
      for (const [, entry] of pending) entry.reject(new Error('运行已取消'));
      pending.clear();
      continue;
    }
    if (message.type === 'deliver') {
      const entry = pending.get(message.reqId);
      if (!entry) continue;
      pending.delete(message.reqId);
      if (message.error) entry.reject(new Error(String(message.error)));
      else entry.resolve(message.result);
    }
  }
});

// 门面里返回 Promise 的调用必须 await：漏掉会变成静默失败（脚本照样返回，但结果缺失）。
process.on('unhandledRejection', reason => {
  send({
    type: 'log',
    message: `未处理的异步失败（门面调用要 await）：${(reason && reason.message) || String(reason)}`,
  });
});

function checkCancel() {
  if (cancelRequested) throw new Error('运行已取消');
}

// ── 非确定性拦截 ─────────────────────────────────────────────
function forbidden(what) {
  return () => { throw new Error(`${what} 在动态工作流里不可用：脚本必须确定性，时间/随机数一律经参数传入`); };
}

const DeterministicDate = new Proxy(Date, {
  construct(target, args, newTarget) {
    if (args.length === 0) forbidden('new Date()')();
    return Reflect.construct(target, args, newTarget);
  },
  apply() { return forbidden('Date()')(); },
});
Object.defineProperty(DeterministicDate, 'now', { value: forbidden('Date.now()'), configurable: false, writable: false });
Object.defineProperty(DeterministicDate, 'parse', { value: Date.parse.bind(Date), configurable: false, writable: false });
Object.defineProperty(DeterministicDate, 'UTC', { value: Date.UTC.bind(Date), configurable: false, writable: false });

const DeterministicMath = Object.create(Math);
Object.defineProperty(DeterministicMath, 'random', { value: forbidden('Math.random()'), configurable: false, writable: false, enumerable: false });

const sandboxConsole = Object.freeze({
  log: (...parts) => emitLog(parts.map(stringify).join(' ')),
  info: (...parts) => emitLog(parts.map(stringify).join(' ')),
  warn: (...parts) => emitLog(parts.map(stringify).join(' ')),
  error: (...parts) => emitLog(parts.map(stringify).join(' ')),
  debug: () => {},
});

function stringify(value) {
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value); } catch { return String(value); }
}

function emitLog(message) {
  send({ type: 'log', message: String(message).slice(0, 4000) });
}

// ── 门面原语 ─────────────────────────────────────────────────
let anonCounter = 0;

function agent(nameOrPersona, maybePersona) {
  let name = typeof nameOrPersona === 'string' ? nameOrPersona : '';
  const persona = (typeof nameOrPersona === 'object' && nameOrPersona) || maybePersona || {};
  if (!name) { anonCounter += 1; name = `匿名#${anonCounter}`; }
  return {
    name,
    ask: (prompt, options) => call('ask', {
      agent: name,
      persona: { system: persona.system || '', model: persona.model || '' },
      prompt: String(prompt ?? ''),
      schema: (options && options.schema) || null,
    }),
  };
}

const world = Object.freeze({
  run: (cmd, args, options) => {
    if (typeof cmd !== 'string' || !cmd) {
      throw new Error('world.run 的命令必须是编译期字符串字面量');
    }
    const list = Array.isArray(args) ? args.map(item => (typeof item === 'string' ? item : stringify(item))) : [];
    return call('world', { op: 'run', cmd, args: list, timeoutMs: (options && options.timeoutMs) || 0 });
  },
});

const files = Object.freeze({
  read: filePath => call('world', { op: 'files.read', path: String(filePath) }),
  glob: pattern => call('world', { op: 'files.glob', pattern: String(pattern) }),
  grep: (pattern, options) => call('world', { op: 'files.grep', pattern: String(pattern), glob: String((options && options.glob) || '**/*'), limit: (options && options.limit) || 200 }),
});

const git = Object.freeze({
  changedFiles: ref => call('world', { op: 'git.changedFiles', ref: String(ref || 'HEAD') }),
});

function channel(name) {
  const label = String(name || 'channel');
  const buffer = [];
  const waiters = [];
  let closed = false;
  return Object.freeze({
    name: label,
    push(value) {
      if (closed) throw new Error(`channel(${label}) 已关闭`);
      if (waiters.length) waiters.shift()(Promise.resolve(value));
      else buffer.push(value);
    },
    close() { closed = true; for (const waiter of waiters.splice(0)) waiter(Promise.resolve(undefined)); },
    next: () => {
      if (buffer.length) return Promise.resolve(buffer.shift());
      if (closed) return Promise.resolve(undefined);
      return new Promise(resolve => waiters.push(resolve));
    },
  });
}

function future(factory) {
  checkCancel();
  return Promise.resolve().then(() => factory());
}

function phase(name) {
  const label = String(name || '');
  send({ type: 'phase', name: label, site: siteId() });
  return label;
}

function log(message) { emitLog(stringify(message)); }

function report(item, tag) {
  send({ type: 'report', item, tag: tag ? String(tag) : '' , site: siteId() });
  return item;
}

// 注意：载荷里不能出现 kind 字段 —— 它是行协议的消息类型判别字段，
// 展开时会覆盖掉，引擎就会把 artifact 当成未知调用。这里用 artifactKind。
const artifact = Object.freeze({
  file: (id, filePath, options) => call('artifact', { id: String(id), artifactKind: 'file', path: String(filePath), title: (options && options.title) || '', primary: !!(options && options.primary) }),
  markdown: (id, markdown, options) => call('artifact', { id: String(id), artifactKind: 'markdown', body: String(markdown), title: (options && options.title) || '', primary: !!(options && options.primary) }),
  chart: (id, spec, options) => call('artifact', { id: String(id), artifactKind: 'chart', spec, title: (options && options.title) || '', primary: !!(options && options.primary) }),
});

function hole(name, prompt) {
  throw new Error(`hole("${String(name)}") 未实现：尾洞链属于 M3，本版先按 ${String(prompt || '').slice(0, 60)} 自行推进`);
}

// ── 执行脚本 ─────────────────────────────────────────────────
const context = vm.createContext({
  agent, world, files, git, channel, future, phase, log, report, artifact, hole,
  Date: DeterministicDate,
  Math: DeterministicMath,
  console: sandboxConsole,
});

async function main() {
  const source = fs.readFileSync(scriptPath, 'utf8');
  const wrapped = `(async () => {\n${source}\n})()`;
  const script = new vm.Script(wrapped, { filename: scriptPath });
  const result = await script.runInContext(context);
  send({ type: 'complete', ok: true, result: result === undefined ? null : result });
}

main().then(
  () => process.exit(0),
  error => {
    send({
      type: 'complete',
      ok: false,
      error: {
        name: (error && error.name) || 'Error',
        message: (error && error.message) || String(error),
        stack: ((error && error.stack) || '').split('\n').slice(0, 8).join('\n'),
      },
    });
    process.exit(0);
  },
);
