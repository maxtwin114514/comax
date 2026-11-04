# Comax 1.5.5o1

版本号：`1.5.5o1`（versionCode 93）

1.5.5 上线后出现「连对话都用不了」。修的是 5 个互相叠加的引擎缺陷，全部有实机日志或
上游实测作依据。

## 0. 现场证据

- 引擎日志刷满 `Error: invalid task transition Queued -> Interrupted`；
- 当前会话文件末尾挂着一条 `content` 为空的 assistant 记录；
- 直连用户配置的上游（`sbu.monai.ccwu.cc/v1`）：纯文本请求 200 正常，**带 `image_url`
  的请求返回 502 `Upstream service temporarily unavailable`**；
- 设置里 `provider_retry_count = 10`（且「无限重试」对 4xx 也生效）。

## 1. 高危：「无限重试」把不可恢复错误也无限重试

`should_retry` 里 `if retry_forever { return true }` 排在错误类型判定之前，于是
API Key 填错（401）、模型名写错（400/404）、base_url 路径错（404）全都会进入无限重试。

界面只显示「任务出错，按『无限重试』设置正在自动重试」，**永远不显示真实错误码**。
用户唯一能感知的就是「连对话都用不了了」。

现在：不可恢复错误（非 `retryable`）**一律不重试**，即使开了无限重试。无限重试只对
瞬时故障（超时 / 连接重置 / 429 / 5xx / 流中断）生效。

## 2. 高危：空回复被当作成功

上游 200 但 `content` 为空时（中转站吞响应体、模型把内容全放进 `reasoning_content`、
`max_tokens` 截断），旧实现直接 `Ok("")` 返回：前端收到空字符串，无消息、无报错、无重试。

现在抛成明确的上游错误并说明三种常见成因，界面会显示出来。

同时：**空 assistant 消息不再落盘**。旧实现无条件 `session.messages.push()`，
会话文件末尾因此留下空记录，重进会话时渲染成空白气泡。

## 3. 高危：带图请求失败后打满重试次数 / 永久转圈

实测上游对 `image_url` 直接 502，而 502 是 retryable —— 旧逻辑要么打满 10 次重试，
要么在无限重试下永久转圈；关键字匹配（`image_url` / `multimodal` / …）也认不出这种 502。

现在：带图请求**失败一次之后**就剥图重试一次，把图降级为纯文本让对话先继续，
并记下 `vision_degraded`，后续请求不再重放图片（图片仍保留在会话记录里）。

## 4. 中危：任务状态机不认 `Queued → Interrupted`

任务还在队列里就被中断（引擎重启、进程回收、用户切走）时必须能收尾，旧状态机没有这条边，
`transition` 返回错误、任务**永远卡在 Queued**。下一轮又被判「已有一个任务在跑」，
于是彻底无法对话。

两处修：

- 状态机允许 `Queued → Interrupted`；
- 引擎重启时的 reconcile 把 `Queued` 也纳入「需要收尾」的范围（旧实现只收 Running /
  WaitingLock / PausePending / Awaiting*，Queued 被漏掉）。

## 5. 中危：两套 URL 拼接规则互相矛盾

- `provider.rs::endpoint()`（真实对话）：只补 `/{suffix}`，手输裸域名会拼成
  `/chat/completions` → 404；
- `capabilities.rs::join_versioned()`（能力探测）：只认末段是 `v1` / `v1beta`，
  于是内置的智谱预设（`.../api/paas/v4`）被拼成 `.../v4/v1/chat/completions` → 404，
  能力集退回推断值，用户看到「不支持工具 / 不看图 / 没有思考档位」。

改法：

- 版本段判定改成 `v` + 至少一位数字 + 可选小写字母（`v1` / `v4` / `v1beta` / `v2alpha`）；
- `endpoint()` 只在**完全没有路径**的裸域名上补默认版本段；带路径的网关前缀一律原样使用
  （否则会把 `api.longcat.chat/openai` 这类本来能用的配置弄坏）。

## 6. 未改动（如实说明）

- 报告里提到的「工具轮次耗尽丢弃已产出文本」与「各入口 max_tool_rounds 不一致」
  本次没有动：它们不会导致「无法对话」，改动面更大，留到下一版单独处理。
- 中转站 `api.monai.ccwu.cc` 对非空 JSON 请求体返回 500 的问题属于服务端故障，
  引擎侧无法绕过；用户若配置的是该站，需要先修服务端。

## 验证

- 引擎 `cargo check` 通过（0 error）；`cargo build --release --locked` 通过，
  `libcoomi.so` 与产物 MD5 一致（`b791f7110cf519802087ed90331c6013`）；
- 新增单元测试 `endpoint_resolver_handles_non_v1_version_segments`（覆盖智谱 v4、
  裸域名补 v1、v1beta / v2alpha 识别）；
- 真机侧已用 WebSocket 直连现有引擎复现并确认基线：新会话纯文本对话正常
  （收到 `你好`），说明「对话不通」不是协议层坏了，而是上面 5 条叠加。
- 本次**没有**在真机上验证修复后的行为：需要装 1.5.5o1 后确认
  ① 带图对话不再无限转圈、② 401 立刻报错并显示 `HTTP 401`、
  ③ 会话末尾不再出现空白气泡、④ 日志不再刷 `invalid task transition`。