# Comax 1.5.5o3

版本号：`1.5.5o3`（versionCode 95）

针对「所有请求都 422」——这次找到的是**请求报文本身拼错了**，与模型、网关、网络、降级全都无关。

## 0. 线上报错原文

```
provider error [phase=response_body kind=Http retryable=false status=422]:
provider rejected the request (code=invalid_request_error) ::
{"error":{"message":"Failed to deserialize the JSON body into the target type:
messages[1]: invalid type: string \"你好\", expected internally tagged enum
ChatCompletionRequestMessage at line 1 column 24286","type":"invalid_request_error"}}
```

`messages[1]` 是**字符串** `"你好"`，而上游（Rust serde 写的网关）期望的是带 `role` 的枚举对象。这是报文结构错误，不是参数错误。

## 1. 根因

1.5.5 做视觉链路时，`provider.rs` 的 `openai_messages()` 里把 user 分支改成了：

```rust
Role::User => openai_user_content(message, supports_vision),
```

这段 `match` 求值出来的是**整个 message 对象**，不是 content 本身。正确写法必须再包一层 `{"role":"user","content": ...}`。

于是发给上游的请求体变成：

```json
"messages": [
  {"role": "system", "content": "..."},
  "你好",
  ...
]
```

- 为什么是 `messages[1]`：`messages[0]` 是 system，第一条用户消息正好落在下标 1 —— 与报错位置完全吻合。
- 为什么重试、降级、换模型全都没用：报文结构错误在每一级降级里都原样存在，删 `reasoning_effort` / `top_k` / `tool_choice` 都改不了它。
- 为什么之前的排查没抓到：1.5.5o1/o2 都在查「参数不被接受」和「降级阶梯失效」，方向是**上游拒收某个字段**；这次是**我们发出的 JSON 就不合法**。

## 2. 修复

```rust
Role::User => json!({
    "role": "user",
    "content": openai_user_content(message, supports_vision),
}),
```

只此一处。其余三种协议（Responses / Anthropic / Gemini）的 user 分支本来就是完整的消息对象，未受影响。

## 3. 防止再犯

### 3.1 结构不变量测试

新增 `openai_messages_are_always_tagged_objects`：任何一条 messages 项都必须是对象、必须带 `role`、必须带 `content`。

### 3.2 严格网关反序列化测试

新增 `request_body_survives_strict_tagged_deserialization`：按真实会话顺序构造完整历史（system → user → assistant → 工具轮 → 内部上下文 → 带图 user → assistant），用 `#[serde(tag = "role")]` 的 internally-tagged enum 反序列化整个请求体。

并带**负向对照**：把 1.5.5 的错误写法当输入，断言它必须被拒绝、且错误信息含 `invalid type: string` —— 保证这个测试真的能抓住该 bug，而不是永远为真。

### 3.3 报文结构错误单独归类，且立即失败

- `is_request_shape_error()` 识别 `internally tagged enum` / `ChatCompletionRequestMessage` / `expected a message object` / `messages[N]: invalid type` 这类文案。
- 命中后**不进降级阶梯**直接返回。旧行为是一次请求白跑 6 轮、每轮重发几十 KB 历史，用户盯着转圈，最后拿到的还是同一条错误。
- 错误摘要里单独标为 `provider request body is not valid for this gateway (message shape)`，日志一眼能区分「我们自己拼错了请求体」和「上游拒收某个参数」。

### 3.4 顺带修掉两个历史失败测试

`normalizes_safe_wrappers_around_tool_arguments` 与 `repairs_interrupted_tool_history_before_deepseek_request` 此前一直是红的（与本次 bug 无关）。

前者的原因是 1.5.4o3 收紧「截断 JSON 不猜参数」时，把「模型把参数包在一句话里」也一并判死了：

```
I will use these arguments: {"path":"README.md"}.
```

这是**完整对象**，只是带了客套壳子。代码里 `extract_json_object()` 仍在，只是不再被调用，现在恢复调用：只接受「恰好一个完整对象」，多个对象或无对象都返回 None，截断的也不会配平 —— 不会重新引入「拿前半截当合法参数」的老问题。

这也解释了用户之前反馈的一个现象：「有的模型会提示工具参数错误，经常莫名其妙出现又莫名其妙消失」——模型换个写法（纯 JSON）就正常了。

## 4. 验证

| 项 | 结果 |
| --- | --- |
| `cargo test -p coomi-services` | 81 passed / 0 failed / 1 ignored |
| `cargo test --workspace` | 250 passed / 0 failed |
| 严格网关反序列化（含负向对照） | 通过 |
| Rust release 构建（aarch64-linux-android） | 通过 |
| APK 内 `libcoomi.so` 与源码产物 MD5 | `bbd042ec65f4b0c558520d6fd74c7965`（一致） |
| 二进制含新增判定字符串 | 是 |

产物：`Comax-1.5.5o3-complete-signed.apk`
- 大小 202320483
- SHA256 `1202669bfe238854fdc254863e1c782edd56f79ebcd288de089aa34c876f84f4`
- versionCode 95 / versionName 1.5.5o3

**注意：本次仅本地构建，未上传官网与 GitHub。** 这类 bug 已连报三次，建议先真机验证再发布。
