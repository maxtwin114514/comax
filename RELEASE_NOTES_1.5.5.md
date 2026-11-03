# Comax 1.5.5

版本号：`1.5.5`（versionCode 92）

这一版只解决一件事：**视觉链路彻底打通**。之前用户把图片或截图拖进来，模型只能去调 OCR，
而 OCR 只认文字、认不出画面 —— 看起来就是「视觉模型也不会看图」。

## 1. 修复：图片根本没有进入请求

四套协议的组装函数里，`Role::User` 分支都直接返回纯文本：

```rust
Role::User => json!({"role": "user", "content": message.content})
```

于是 `ChatMessage.images` 里存的图片被**静默丢弃**。只有工具结果（`Role::Tool`）的图片
被正确处理过 —— 因为那是 `view_image` / `show_image` 产生的。

现在四套协议都会带上用户图片：

| 协议 | user 消息的图片字段 |
| --- | --- |
| OpenAI 兼容 | `content: [{type:"text"}, {type:"image_url"}]` |
| OpenAI Responses | `content: [{type:"input_text"}, {type:"input_image"}]` |
| Anthropic | `content: [{type:"text"}, {type:"image", source:{type:"base64"}}]` |
| Gemini | `parts: [{text}, {inlineData:{mimeType,data}}]` |

## 2. 前端：附件路径随消息一起发出去

`send_message` / `jump_in` 增加 `images: string[]`。前端从附件里筛出图片扩展名
（png/jpg/jpeg/gif/webp）后一并发送；引擎读文件转成图像内容。

单张上限 10 MiB、一次最多 6 张；非图片附件仍走原来的「请读取这些文件」文本说明。

## 3. 修复：providers.json 里 supports_vision 一律为 false

两个原因叠加：

- **前端**：保存 provider 时无条件给每个模型补一条 `vision: false`；
- **引擎**：`capabilityOverrides[model].vision` 缺失时回落到顶层的
  `supports_vision`（默认 false），而 override 里写满了 false，于是所有模型都被
  当作文本模型。

改法：

- 前端只保存**用户真的改过**的 override；全默认的模型不再落盘。
- 引擎改成两级判定：显式 `vision: true` → 顶层 `supports_vision` → **按模型名推断**
  （`gpt-5` / `claude` / `gemini` / `*-vl` / `vision` / `multimodal` / `omni` 等）。
- 推断命中也安全：真被上游拒图时 agent 有 image-compatibility 回退，会剥掉图片重试
  并把会话标记为降级（`maybe_degrade_vision`）。

「图像理解」页签也不再恒为空 —— 它沿用同一套推断，用户能看到并勾选自己的视觉模型。

## 4. 新增：真正的截屏

`ui_automation` 的 `screenshot` 以前只走 adb 回退（`screencap`），没有 adb 权限时直接失败；
`read_screen` 又只有文字。所以「截图识别」这条路一直是断的。

现在：

- `coomi_accessibility_service.xml` 增加 `android:canTakeScreenshot="true"`；
- `CoomiAccessibilityService` 实现 `takeScreenshot()`（API 30+，不需要 MediaProjection
  授权弹窗、不需要 adb），异步回调 + 8 秒兜底，PNG 写进应用私有目录
  `files/control-shots/`，返回绝对路径；
- 引擎侧 `screenshot_result()` 把路径读回来 `with_image` —— 画面直接进入模型上下文。

## 5. OCR 不可用时给出可执行替代

未装 tesseract 时不再只回一句「不可用」，而是明确建议改用 `view_image` 把图交给视觉模型，
并给出 `apt install -y tesseract-ocr tesseract-ocr-chi-sim`。

## 6. 官网重做

参考 droidance.app 的克制风格：近白底、单一深色文字、一条中性灰分隔线。
去掉了渐变光晕、玻璃卡片、常驻循环动画；层级靠字号与留白建立。
能力/对比/更新/下载/FAQ 全部重写，`latest.json` 字段与旧站保持一致，部署脚本无需改。

## 验证

- 前端 `vue-tsc --noEmit` 通过；`vite build` 成功；
- 引擎 `cargo check` / `cargo build --release --target aarch64-linux-android` 通过；
- `assembleRelease` 成功：versionCode 92 / `1.5.5`，arm64-v8a，202.3 MB（202313081 B）；
- APK SHA-256 `566a575f9978045674d9dcba0e1b5891d4fc85c3215678c79f095704976392a1`；
- APK 内实测：`web.zip` 含 `supportsVision`；`libcoomi.so` 与编译产物 MD5 一致
  （`6c77d9257fcb2fbd47fdc0b6eb01aa70`），二进制内含 `action=screenshot`；
  `res/*.xml` 里能搜到 `canTakeScreenshot`；
- `patch-site-index.py --self-test` 通过，新 `index.html` 的所有选择器都能被它匹配。

## 未验证（如实说明）

- 没上真机：图片是否真的被上游接受、截屏是否真的拿到画面、无障碍截图在 HyperOS 上是否
  需要额外权限，全都要实测；
- 「按模型名推断视觉」是保守白名单，必然会有漏判（未知多模态模型）与误判（同名文本模型）。
  两种情况都会退回可用的降级路径，但**都需要真机确认降级是否干净**；
- 已有用户的 providers.json **不会**被自动改写：配置页在**加载**时会过滤掉「三项恰好等于
  默认值」的旧条目（`{text:true, vision:false, image_generation:false}`），所以只要打开一次
  该 provider 并保存，满屏 false 就会被清掉；不打开则保持原样（引擎侧的名推断仍会生效，
  因为 `vision:false` 现在不再压过它 —— 只有 `vision:true` 才算显式声明）。
