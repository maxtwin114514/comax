#!/usr/bin/env node
'use strict';
/**
 * Comax 云端访问 · 服务器端中继
 *
 * 为什么需要它，而不是让 nginx 直接反代到手机：
 *   手机在运营商 NAT 后面，服务器**发起不了**到手机的连接。必须手机先拨出来。
 *   nginx 只会转发它自己发起的连接，做不了这件事，所以中间要有一个中继：
 *
 *     浏览器 ──▶ nginx ──▶ 中继(127.0.0.1:PORT) ──┐
 *                                                  │ 同一条 TCP（手机主动拨出）
 *     手机 Comax 引擎 ◀────────────────────────────┘
 *
 * 为什么不用 SSH 反向隧道：
 *   手机侧的运行环境里没有 ssh/sshpass（实测只有 bash/curl/node/python3/sh），
 *   所以 `ssh -R` 这条路在真机上是走不通的。这里改成自带的 TCP 隧道实现。
 *
 * 一个端口同时承担两件事（所以只需要放行一个端口）：
 *   · 手机拨进来 → 首字节是 "COMAX-TUNNEL/" 的握手，认作隧道控制连接；
 *   · 浏览器/nginx 进来 → 其它一切，认作要转发的 HTTP(S) 流量。
 *
 * 安全：
 *   · 隧道握手用「随机 nonce + 口令哈希」应答，口令不过网，也不能重放；
 *   · 浏览器侧靠 Comax 引擎自己的访问令牌（URL 里的 ?token=）；
 *   · 引擎侧对云端请求只放行只读接口 —— 拿到地址也改不了任何配置。
 */
const net = require('node:net');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const MAGIC = 'COMAX-TUNNEL/1';
const CONFIG_PATH = process.env.COMAX_RELAY_CONFIG || path.join(__dirname, 'comax-cloud-relay.json');

function loadConfig() {
  try {
    return JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
  } catch {
    return {};
  }
}

const config = loadConfig();
const PORT = Number(config.port || process.env.COMAX_RELAY_PORT || 22336);
const USER = String(config.user || process.env.COMAX_RELAY_USER || '');
const PASSWORD = String(config.password || process.env.COMAX_RELAY_PASSWORD || '');

// 帧类型：与 Rust 侧 cloud_tunnel.rs 必须逐字节一致
const OPEN = 1;
const OPEN_OK = 2;
const OPEN_FAIL = 3;
const DATA = 4;
const CLOSE = 5;

const HEADER = 9; // type u8 + channel u32be + length u32be

function encodeFrame(type, channel, payload) {
  const body = payload || Buffer.alloc(0);
  const head = Buffer.allocUnsafe(HEADER);
  // 布局必须是 [type u8][channel u32][length u32] = 9 字节：
  //   type    偏移 0
  //   channel 偏移 1..4
  //   length  偏移 5..8   ← 这里曾经写成 4，和 channel 的最后一字节重叠，
  //                          对端解出来的 channel/length 全是垃圾，隧道看着通、
  //                          实际一个请求都过不去。
  head.writeUInt8(type, 0);
  head.writeUInt32BE(channel >>> 0, 1);
  head.writeUInt32BE(body.length, HEADER - 4);
  return Buffer.concat([head, body]);
}

function expectedAuth(nonce) {
  return crypto.createHash('sha256').update(`${nonce}\n${USER}\n${PASSWORD}`).digest('hex');
}

let tunnel = null;            // 当前手机控制连接
let nextChannel = 1;
const channels = new Map();   // channel -> { socket, queue, opened }

function log(...args) {
  console.log(new Date().toISOString(), ...args);
}

function attachTunnel(socket) {
  if (tunnel && !tunnel.destroyed) {
    log('新的隧道接入，替换旧连接');
    tunnel.destroy();
  }
  tunnel = socket;
  let buffer = Buffer.alloc(0);

  socket.on('data', chunk => {
    buffer = buffer.length ? Buffer.concat([buffer, chunk]) : chunk;
    while (buffer.length >= HEADER) {
      const type = buffer.readUInt8(0);
      const channel = buffer.readUInt32BE(1);
      const length = buffer.readUInt32BE(5);
      if (buffer.length < HEADER + length) break;
      const payload = buffer.subarray(HEADER, HEADER + length);
      buffer = buffer.subarray(HEADER + length);
      handleTunnelFrame(type, channel, Buffer.from(payload));
    }
  });

  const drop = reason => {
    if (tunnel === socket) {
      tunnel = null;
      log('隧道断开：' + reason);
    }
    // 控制连接没了，所有在途的浏览器连接都得收掉，否则会一直挂着
    for (const [, entry] of channels) entry.socket.destroy();
    channels.clear();
  };
  socket.on('close', () => drop('连接关闭'));
  socket.on('error', error => drop(String(error && error.message)));
}

function handleTunnelFrame(type, channel, payload) {
  const entry = channels.get(channel);
  if (type === OPEN_OK) {
    if (entry) {
      entry.opened = true;
      // 攒下来的这些字节是**浏览器发来的请求**，要送往手机，
      // 不是写回浏览器 —— 写回浏览器等于把请求原样回显给它自己，
      // 客户端会看到 "BadStatusLine GET /... HTTP/1.1"。
      for (const queued of entry.queue) {
        if (tunnel && !tunnel.destroyed) tunnel.write(encodeFrame(DATA, channel, queued));
      }
      entry.queue = [];
    }
    return;
  }
  if (type === OPEN_FAIL) {
    if (entry) {
      const reason = payload.toString('utf8') || '手机侧无法打开到本地引擎的连接';
      entry.socket.end(`HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${Buffer.byteLength(reason)}\r\nConnection: close\r\n\r\n${reason}`);
      channels.delete(channel);
    }
    return;
  }
  if (type === DATA) {
    if (entry && !entry.socket.destroyed) entry.socket.write(payload);
    return;
  }
  if (type === CLOSE) {
    if (entry) {
      entry.socket.end();
      channels.delete(channel);
    }
  }
}

function handleHttpConnection(socket) {
  if (!tunnel || tunnel.destroyed) {
    socket.end('HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n手机还没有连上来（在 Comax 的「云端访问」里点一下连接）\n');
    return;
  }
  const channel = nextChannel++ >>> 0;
  const entry = { socket, queue: [], opened: false };
  channels.set(channel, entry);

  // 浏览器可能已经开始发请求体了，先攒着，等手机侧把本地连接打开再一起放过去
  socket.on('data', chunk => {
    if (!tunnel || tunnel.destroyed) return;
    if (entry.opened) tunnel.write(encodeFrame(DATA, channel, chunk));
    else entry.queue.push(chunk);
  });

  const finish = () => {
    if (!channels.has(channel)) return;
    channels.delete(channel);
    if (tunnel && !tunnel.destroyed) tunnel.write(encodeFrame(CLOSE, channel));
  };
  socket.on('close', finish);
  socket.on('error', finish);

  tunnel.write(encodeFrame(OPEN, channel, Buffer.alloc(0)));
}

const server = net.createServer(socket => {
  socket.setNoDelay(true);
  // 先读一小段看是隧道握手还是普通 HTTP
  socket.once('data', chunk => {
    const head = chunk.toString('utf8', 0, Math.min(chunk.length, 64));
    if (head.startsWith(MAGIC)) {
      // 协议由客户端先开口：它发 MAGIC 行，我们回一个随机 nonce 让它证明身份。
      // （服务端不能先说话 —— 这一个端口同时还要认 HTTP，必须先看首字节再决定。）
      const rest = chunk.toString('utf8').slice(head.indexOf('\n') + 1);
      const nonce = crypto.randomBytes(16).toString('hex');
      socket.write(`${MAGIC} ${nonce}\n`);
      // 首次 data 里可能已经跟着 AUTH 行
      let pending = rest;
      const expect = expectedAuth(nonce);
      const finish = line => {
        const value = (line || pending).trim();
        pending = '';
        const supplied = value.replace(/^AUTH\s+/i, '');
        const ok = supplied.length === expect.length
          && crypto.timingSafeEqual(Buffer.from(supplied), Buffer.from(expect));
        if (!ok) {
          log('隧道鉴权失败');
          socket.end('ERR bad credentials\n');
          return;
        }
        socket.write('OK\n');
        attachTunnel(socket);
        log('手机隧道已接入');
      };
      if (pending.includes('\n')) finish(pending.slice(0, pending.indexOf('\n')));
      else if (pending.trim()) finish(pending);
      else {
        const onAuth = data => {
          socket.removeListener('data', onAuth);
          const text = data.toString('utf8');
          finish(text.slice(0, text.indexOf('\n') === -1 ? undefined : text.indexOf('\n')));
          // 握手行之后可能还有帧数据，交回给正式的帧解析
          const remaining = text.slice(text.indexOf('\n') + 1);
          if (remaining) socket.emit('data', Buffer.from(remaining));
        };
        socket.on('data', onAuth);
      }
      return;
    }
    handleHttpConnection(socket);
    // 第一个块不能丢：它是请求的开头
    if (!socket.destroyed) socket.emit('data', chunk);
  });
  socket.on('error', () => {});
});

server.listen(PORT, '0.0.0.0', () => {
  log(`Comax 中继已启动：监听 0.0.0.0:${PORT}`);
  log(`认证用户：${USER || '（未配置）'}`);
  log(`等待手机拨入…（nginx 应反代到 127.0.0.1:${PORT}）`);
});

process.on('SIGTERM', () => { server.close(() => process.exit(0)); });
process.on('SIGINT', () => { server.close(() => process.exit(0)); });
