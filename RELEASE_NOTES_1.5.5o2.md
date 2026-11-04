# Comax 1.5.5o2

版本号：`1.5.5o2`（versionCode 94）

针对「全部调用都 422 或 400，但不是模型问题」。这一版修的是引擎里**降级机制本身失效**的问题。

## 0. 实测过程（先把不是原因的排除掉）

为避免猜，直接用用户配置的上游（`sbu.monai.ccwu.cc/v1`）做了逐项对照：

| 请求形态 | 结果 |
| --- | --- |
| 纯文本 / 流式 / 带 tools / 带 tool_choice | 200 |
| `reasoning_effort` 取 max / xhigh / high / medium / low / minimal | 全部 200 |
| **引擎实际发出的完整请求体**（46 个工具 + 完整系统提示词 + xhigh，99 KB） | 200 |
| 同上但去掉 tools / 去掉 reasoning_effort / 只留 1 个工具 / 截断系统提示词 | 全部 200 |
| 50 万 ~ 200 万 token 的超长历史 | 200 |
| 约 250 万 token | 502（上游体积上限，不是 400/422） |

所以问题不在模型、不在提示词、不在工具数量、不在会话长度。

## 1. 主因：422 完全不触发降级阶梯

`send_with_reasoning_fallback` 原来的判定是：

```rust
if response.status().as_u16() != 400 {
    return Ok(response);          // 422 直接返回失败
}
```

而相当多 OpenAI 兼容网关（FastAPI 系、部分中转）对「未识别字段 / 参数取值非法」返回的是
**422** 而不是 400。于是这些站点上：

- `reasoning_effort` / `top_k` / `parallel_tool_calls` / `tool_choice` 一旦不被接受，
  请求直接失败，后面那五级降级**一次都不会执行**；
- 表现就是「不管换什么模型、发什么内容，全都 422 或 400」。

现在 400 与 422 同等对待，都会进入降级阶梯。

## 2. 降级阶梯缺一级：只去 tool_choice、保留 tools

旧阶梯最后一级是 `remove_optional_capability_fields`，会**连 tools 一起删掉**。
而很多网关接受 `tools`、只拒绝 `tool_choice`。旧阶梯没有中间档，只能一步跳到
「完全不给工具」——用户看到的是「模型突然不会用工具了」，而且完全无从判断。

现在插入一级：去掉 `tool_choice`、保留 `tools`。放弃工具成为**最后**的选择，
且走那一步时会在引擎日志里明确记录：

```
[provider] all degradation steps failed to keep tools;
the next attempt runs without tool calling (model=…)
```

## 3. 报错只显示一句话，看不到上游真实原因

`safe_http_error_detail` 原来只对 **400 / 405** 附带上游响应体摘要，422 只回一句
`provider rejected the request`。这就是「不知道为什么」的直接原因。

现在 400 / 405 / 422 都会附带：

- 上游原始响应摘要（截断长度从 220 放宽到 400 字符）；
- 上游错误码（`code=` / `type=`）。

可以直接看到 `model_not_found`、`invalid_request_error`、`Unsupported parameter` 这类真实原因。

## 4. 报错显示的原因可能根本不是最后失败的那条

降级阶梯里每级失败后都是 `let _ = retry.text().await;` —— 响应体被读走丢弃，
最终 `ProviderRequestError` 用的永远是最初那条 400 的 detail。

现在保留**最后一次失败**的状态码与响应体，报的就是真正卡住请求的那条。

## 验证

- `cargo build --release --locked --target aarch64-linux-android` 通过（0 error）；
- `libcoomi.so` 与产物 MD5 一致（`ab90d12a6549197855e91d97a291d837`）；
- APK 内实测：二进制含新增的 `all degradation steps failed` 日志串；
- `assembleRelease` 成功：versionCode 94 / `1.5.5o2`，arm64-v8a，202318280 B；
- 签名证书与历史版本一致（`07e07319…`）。

## 未验证（如实说明）

- **没在真机上验证修复效果**：需要装 1.5.5o2 后确认
  ① 原来 422 的 provider 现在能正常对话；
  ② 如果仍失败，报错里能看到上游原始原因（不再是「provider rejected the request」）；
- `provider::tests::normalizes_safe_wrappers_around_tool_arguments` 与
  `repairs_interrupted_tool_history_before_deepseek_request` 两个单测失败，
  **与本版改动无关**（本次只动了降级阶梯与错误详情，不涉及工具历史修复），
  但确实说明仓库里存在两个早就坏掉的测试，留待单独处理；
- 上游 `api.monai.ccwu.cc` 对非空 JSON 请求体返回 500 的问题仍是服务端故障，
  引擎侧无法绕过。
