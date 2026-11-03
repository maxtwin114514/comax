# Comax 1.5.4o5beta

版本号：`1.5.4o5beta`（versionCode 91）

九项：思考窗口从第一行长到三行、收回不再留缝、思考中也能点开、强度探测不再卡、
无限重试、重试弹层带动画并显示错误码、停止即时生效、长消息不再顶掉历史、
各处展开动画换成合成器友好写法。

## 1. 修复：收回思考后空出一点

两处残留叠加：

- `ProcessBlock` 的 `.pblock` 用 `gap: 6px` 分隔标题与内容。**gap 是容器属性**，
  内容收到 0 高度后那 6px 依然存在。现在间距挪到 `.pwrap` 的 `margin-top`，
  随开合一起归零。
- `ReasoningBlock` 的 `.body` 自带 `margin-top: 4px`。grid 轨道收到 `0fr` 时
  item 高度为 0，但 **margin 不计入 item 尺寸**，这 4px 会永远留着。
  改成三层 `wrap(grid) > inner(overflow:hidden, min-height:0) > body`，
  间距进了内层，收起就是干净的 0。

## 2. 修复：思考过程中点展开/收回没反应

两个原因：

- `ProcessBlock` 旧版在 `live` 时 `pwrap { transition: none }`，点了高度瞬变但
  内层 overflow 让读者看不出变化，像没反应。现在流式中走 120ms 短过渡。
- `ReasoningBlock` 流式中「尾巴窗口 ↔ 全文」是 `v-if` 直接替换节点，没有过渡。
  现在切换时保留节点并走短过渡，点击立刻有反馈。

## 3. 修复：开启模型思考强度时引擎卡顿很久

`probe_reasoning_efforts` 原来**串行**发 6 个候选值、每个 12 秒超时 ——
最坏 72 秒，期间看着就是「引擎卡住了」。

现在改成**每批 3 个并发**（`join3` / `join`，批间串行），最坏 2×12s；
一批里出现鉴权/网络失败立刻收工，不再白等下三批。结果按
`max → xhigh → high → medium → low → minimal` 重排后再落配置，界面顺序稳定。

## 4. 新增：无限重试 + 报错代码

设置 → 连接、重试与并发，新增「无限重试」开关：

- 开启后**任何**错误都重试（含 4xx / 参数错误 / 鉴权失败），唯一出口是点停止；
- 关闭时行为与原来完全一致（只重试瞬时故障，且不超过配置次数）；
- 重试提示旁边始终显示报错代码：`HTTP 429` / `Timeout` / `DNS` /
  `ConnectionReset` / `TLS` / `Stream` 等；
- 无限重试时进度显示「第 N 次 · 无限」，不再出现「1/0」。

引擎侧：`AgentEvent::ConnectionRetry` 增加 `code` 字段，
`retry_confirmation` 增加 `code`；`ConnectionSettings` 增加 `retryForever`。

## 5. 修复：重试提示没有弹出动画

`retry_confirmation` 与 `undoConfirm` 两块原来是裸 `div + v-if`，插入即显示。
现在包进 `<Transition name="sheet-pop">`：上浮 10px + 轻微放大 + 淡入，200ms 收住。

## 6. 修复：点停止后要等一会才停

两处：

- **引擎侧顺序错了**。`stop_session_task` 原来先 `terminate_all().await`（逐个
  SIGTERM 再等回收，一个有长任务的 proot 进程能拖几秒）**才**推 `agent_cancelled`。
  现在事件先发、进程清理挪到最后，反馈立刻到达。
- **前端在干等**。补了乐观反馈：点停止立刻置 `cancelling`、收起运行中的工具卡、
  发送键变成不可点的「停止中」小方块；420ms 内没等到引擎事件就直接按已停止收尾，
  剩下的清理让引擎在后台做完。

## 7. 修复：长消息把历史顶掉 / 内容被复制一份卡在最上方

根因在虚拟列表：`DynamicScroller` 只在条目尺寸变化时重算偏移，而流式中最后一条
每帧都在长高、高度又要靠元素自己 emit resize 才知道 —— 中间任何一帧测量没接上，
`sizes` 就落后于真实高度，随后整块 offset 被算错。

对策不是调参数，而是**不做流式虚拟化**：对话中用普通滚动容器（`own-stream`）铺
全部时间线，空闲看历史才回到虚拟列表；两种模式**互斥渲染**，任何时刻只有一份 DOM
承载内容。切换只发生在「开始 / 结束一轮」，中间不来回跳。

## 8. 修复：每个展开动画都很卡（含侧栏）

逐项去掉了真正贵的东西：

- **侧栏**：去 `rotateY(14deg)` → `8deg`，透视 1400 → 2000px（减小边缘拉伸与
  文字重栅格化），缓动换成 `cubic-bezier(.2,0,0,1)`、时长 340 → 300ms。
- **模式面板**：删掉 `filter: blur(6px)` —— blur 会让 WebView 在整个动画期间
  每帧把面板内所有文字重新栅格化，这是「模式展开很卡」的主因。改用纯
  transform + opacity。
- **状态条**：`setStatusBarHidden` 里同样的 `blur(6px)` 一并删掉。
- **工具组**：`.glist` 的 `max-height: 0 → 4800px` 补间换成
  `grid-template-rows: 0fr ↔ 1fr`。前者每帧重排整条消息流，且 4800px 量级会让
  缓动严重失真（前半程几乎不动、后半程猛跳）。

## 9. 思考过程动画：从第一行平滑长到三行，新字由模糊转清晰

`ReasoningBlock` 的成长窗口重做：

- 容器高度由 `--lines` 驱动，**起始 1 行**，随内容行数平滑长到 **3.5 行**
  （`transition: height .42s`，只在行数变化时触发一次，不是每帧）；
- 双端 mask：顶边淡出（旧内容）、底边渐入（刚写出的字）—— 观感是从模糊到清晰，
  但不使用真正的 `filter: blur()`（那会每帧重栅格化，正是 o4 之前卡死的原因）；
- 内层 flex 底部对齐 + 顶部裁切：新行从底部冒出、整体往下推，**不写 scrollTop**，
  测量与绘制不再互相打架；
- 行数按换行数与长度（一行≈22 汉字）粗估，不做真实测量。

## 验证

- 前端 `vue-tsc --noEmit` 通过；`vite build` 产物已更新并打进 `assets/web.zip`；
- 引擎 `cargo check --target aarch64-linux-android` 通过；
- `cargo build --release` 通过，`libcoomi.so` 与编译产物 MD5 一致
  （`fb2ba2ef072f694834cf97e189c162a4`）；
- `assembleRelease` 成功：versionCode 91 / `1.5.4o5beta`，arm64-v8a，202.3 MB；
- 签名校验通过：`CN=Cubee Agent`，SHA-256 `07e07319…`（与历史版本同一证书）；
- APK 内实测校验：`web.zip` 里含「无限重试」「retry-code」「own-stream」；
- 改动文件：`ReasoningBlock.vue`、`ProcessBlock.vue`、`ToolGroup.vue`、
  `Composer.vue`、`ChatView.vue`、`SettingsView.vue`、`stores/session.ts`、
  `stores/config.ts`、`protocol/events.ts`、`engine/src/agent.rs`、
  `engine/src/types.rs`、`services/src/provider.rs`、`ui/src/web.rs`、
  `ui/src/main.rs`、`ui/src/terminal_ui/mod.rs`、`app/build.gradle`

## 未验证（如实说明）

- 没上真机：思考窗口的成长节奏、侧栏与模式面板的实际帧率、停止的体感延迟、
  长消息下的滚动位置，都要真机确认；
- `probe_reasoning_efforts` 的并发批次只做了编译验证，没有对真实上游跑过
  （并发会不会触发某些网关的限流，需要实测）；
- 「无限重试」是极端行为：某些不可恢复错误（如 401 鉴权失效）会一直重试直到
  用户手动停止，中间会持续消耗请求配额。
