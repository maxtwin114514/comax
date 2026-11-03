# Comax 1.5.4o4beta

版本号：`1.5.4o4beta`（versionCode 90）

这一版把「用着不对劲」的六件事收干净：思考卡住、档位选不动、往上翻还被拽回底部、
引擎坏了只能重装、工具结束了还在转。

## 1. 修复：思考显示直接卡住 / 非常卡

旧实现每来一个 token 都做三件 O(全文) 的事：`split` 整段思考成行（只看最后 4 行却切全文）、
按 rAF 往内层写 `scrollTop`、用 `replace(/\s+/g,'')` 数全文字数。思考一长，主线程每帧被
叫停一次，画面就僵住。

现在：

- 只截**末尾 600 字**渲染成**一个文本节点**，靠 flex 的 `align-items:flex-end` +
  父级 `overflow:hidden` 做「尾巴窗口」，**完全不碰 scrollTop**；
- 字数只在思考结束后算一次，流式期间不显示计数（改成 `···`）；
- 尾窗顶边加 mask 渐隐，越旧越淡。

顺带修掉一个隐患：子元素**不能**自己限高 + `overflow:hidden`，否则裁掉的是末尾
而不是开头，尾巴窗口会显示最旧的文字。

## 2. 修复：选思考强度后不显示 / 无法切换

两处根因：

- **持久化校验表缺档**：`REASONING_EFFORTS` 只列到 `xhigh`，用户选 `max` / `minimal`
  重启后被判非法并悄悄退回 auto。现在补全 `minimal/low/medium/high/xhigh/max`，
  并单独导出 `REASONING_EFFORT_VALUES` 做校验。
- **收敛 watcher 抢跑**：旧逻辑监听 `declaredEfforts`，探测结果一回来清单就变，
  随即把用户刚选的档位判成非法打回 auto。现在只监听 `provider/model` 切换，
  且 `off` / `auto` 永远原样保留。

探测结果现在**先落 localStorage**（`coomi.efforts.<provider:model>`）再回写配置，
界面立刻生效，不用等 `upsert → fetchProviders` 的往返；用户点到清单里还没有的档位时，
也会顺手并进本地清单。

## 3. 修复：往上滑了一段还会被自动置底

旧实现只把 `touchstart` 期间的 scroll 事件算成用户行为，**惯性滚动落进了盲区**，
于是手指离开后一有新内容就 follow 到底。

现在：

- `touchmove` / `touchend` 后 700ms 内，只要 `scrollTop` 变小就算用户上翻；
- 脱离后 400ms 冷静期内不因为「看起来在底部」自动恢复，避免来回抖；
- **`turn_end` 不再强制置底**：原来它递增 `viewRevision`，而那个 watch 会
  `following = true` + `jumpToBottom()`，用户正看历史就被硬拽回底部。
  现在改成监听 `runState` 回到 idle，只做一次 `follow()`（跟随态才生效）。

## 4. 修复：思考过程中点展开/收回没反应

`ProcessBlock` 的 `toggle()` 里有 `if (props.live && open.value) return` ——
运行中把点击吞掉了。现在点击永远生效，只在「新一轮开始」时自动展开一次。
`ReasoningBlock` 同时新增了「流式中也能展开全文」，展开后带 42vh 上限自己滚。

## 5. 新增：引擎日志页的「修复引擎」

`CoomiLogActivity` 底部新增两块按钮：**立即修复** / **修复并重启引擎**。

清理（全部能从 APK 内置资产重建）：

- `files/web`（前端解包目录）
- `~/.coomi/runtime-v2/versions` 与 `state.json`
- `~/.coomi/engine.version`（引擎二进制指纹）
- `~/.coomi_deployed`（部署标记）
- 旧日志的 `.1` 轮转残留

保留：全部对话历史、提供商与设置、`runtime-v2/home`（guest 持久 home）、
`runtime-v2/downloads`（离线重装依赖的压缩包）、工作区文件。

修复前会先扫 `/proc` 杀掉本应用拉起的引擎与 PRoot 残留进程（否则它们握着
runtime 目录，删一半就失败）。「修复并重启」通过带 action 的 intent 让
`CoomiService` 在服务线程上 `stopEngineSync + startEngineSync`，不依赖界面绑定。

## 6. 修复：工具已经结束，卡片还显示「正在调用」

三条兜底：

- `tool_start` 改成幂等：同一 `call_id` 重复到达时只更新已有卡片，不再新建
  （旧卡片会永远停在「准备」）；`turnToolTrace` 也不会重复堆积。
- `tool_done` 找不到卡片时**补一张终态卡片**，结果不会凭空消失。
- `turn_end` 是「这一轮真的结束」的权威信号：此时仍是 `running`/`starting` 的卡片
  一律收成「已取消 · 本轮已结束，未收到该工具的返回结果」，不再继续转圈。
  同时把控制模式悬浮层的状态行改成「本轮已完成」，不再停在最后一句「正在调用 xxx」。

## 验证

- 前端 `vue-tsc --noEmit` 通过；`vite build` 产物已更新 `apps/web/dist`；
- `assembleRelease` 成功（versionCode 90 / 1.5.4o4beta，arm64-v8a）；
- 改动文件：`ReasoningBlock.vue`、`ProcessBlock.vue`、`Composer.vue`、
  `useAutoScroll.ts`、`ChatView.vue`、`stores/session.ts`、`stores/config.ts`、
  `CoomiService.java`、`CoomiLogActivity.java`、`CoomiEngineRepair.java`（新增）、
  `activity_coomi_logs.xml`、`strings_coomi.xml`、`app/build.gradle`

## 未验证（如实说明）

- 没上真机：思考流畅度、上滑脱手后的惯性判定、修复引擎后的实际重建耗时，都要真机手感确认；
- Java 侧只做了编译（`assembleRelease`），没有跑仪器化测试；
- 「修复引擎」删的是可再生产物，但**没有任何回滚**：若 APK 内置资产本身损坏，
  修复后仍然起不来 —— 这种情况只能重装。日志页会先给出提示，不会静默失败。
