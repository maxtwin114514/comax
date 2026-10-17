/**
 * 侧栏与模式面板的动画契约测试。
 *
 * 这里钉住的每一条都是**真机上踩过的坑**（不是风格偏好），改动前请先看清原因：
 *  1. 菜单按钮在 .shell 内部，给 .shell 挂 @click 会让"点开侧栏"的同一次点击
 *     冒泡上去把它立刻关掉 —— 侧栏再也打不开。
 *  2. 侧栏是底图，层级必须低于对话界面，否则整块盖在上面，看着还是老式抽屉。
 *  3. 被推开那张卡要真的右移一个侧栏宽度，光写 rotateY 不会让出位置。
 *  4. rotateY 取正角度才是右边缘后退（CSS 里 +Z 朝观察者）。
 *  5. 模式面板的展开动画必须等元素先在"收起态"画过一帧再翻类名，
 *     否则同一帧内插入+到位，浏览器看不到变化前的样式，过渡不会触发（面板闪现）。
 *  6. 收起必须靠定时器兜底摘节点，不能只依赖过渡回调 —— 回调没跑到面板就卡在可见态。
 */
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const chat = await readFile(new URL('../src/views/ChatView.vue', import.meta.url), 'utf8')
const composer = await readFile(new URL('../src/components/Composer.vue', import.meta.url), 'utf8')
const drawer = await readFile(new URL('../src/components/SideDrawer.vue', import.meta.url), 'utf8')

test('菜单键是切换而不是只开不关', () => {
  assert.match(chat, /@menu="toggleDrawer"/)
  assert.match(chat, /function toggleDrawer\(\)/)
})

test('不把点击关闭挂在 .shell 上（会和菜单按钮的冒泡打架）', () => {
  assert.doesNotMatch(
    chat,
    /class="shell"[^>]*@click/,
    '.shell 上挂 @click 会让打开侧栏的那次点击立刻把它关掉',
  )
  // 改用卡片内部的专用收起层
  assert.match(chat, /class="drawer-dismiss"/)
  assert.match(chat, /收起侧栏/)
})

test('侧栏是底图：层级低于对话界面，打开时恢复交互', () => {
  const root = /\.drawer-root \{([^}]*)\}/.exec(drawer)?.[1] ?? ''
  const drawerZ = Number(/z-index:\s*(\d+)/.exec(root)?.[1])
  const shellZ = Number(/\.shell \{[^}]*z-index:\s*(\d+)/.exec(chat)?.[1])
  // 层级高于对话界面 = 侧栏常驻在左边，一直盖着对话界面（真机上踩过的坑）
  assert.ok(drawerZ < shellZ, `侧栏 z-index(${drawerZ}) 必须低于对话界面(${shellZ})，否则常驻`)
  // 容器只占左侧一条（不能铺满整屏，否则盖住右侧）
  assert.match(root, /inset:\s*0 auto 0 0/, '侧栏容器只占左边一条')
  // 关键：默认 pointer-events:none，.open 时恢复 auto —— 丢了就点不动、滑不动
  assert.match(root, /pointer-events:\s*none/, '默认不可点')
  assert.match(drawer, /\.drawer-root\.open \{[^}]*pointer-events:\s*auto/, '.open 必须恢复 pointer-events:auto')
})

test('对话界面真的向右让开，且旋转方向正确', () => {
  const pushed = /\.shell\.pushed \{([^}]*)\}/.exec(chat)?.[1] ?? ''
  assert.match(pushed, /translateX\(var\(--drawer-w\)\)/, '要让开一个侧栏宽度')
  assert.match(pushed, /rotateY\((?!-)\d/, 'rotateY 必须取正角度（负角度是朝观察者怼过来）')
})

test('透视只在侧栏打开时建立（空闲时不该常驻全屏合成层）', () => {
  assert.match(chat, /\.chat\.pushed \{[^}]*perspective/)
  assert.doesNotMatch(
    /\.chat \{([^}]*)\}/.exec(chat)?.[1] ?? '',
    /perspective/,
    '.chat 基础态不该有 perspective',
  )
})

test('模式面板：先画一帧收起态再翻展开态', () => {
  assert.match(composer, /const panelOpen = ref\(false\)/)
  assert.match(composer, /const panelMounted = ref\(false\)/)
  assert.match(
    composer,
    /requestAnimationFrame\(\(\) => \{\s*requestAnimationFrame\(\(\) => \{[\s\S]{0,220}panelOpen\.value = true/,
    '必须在两帧之后才翻展开态',
  )
})

test('模式面板：堆叠升出后立刻摊开（1.5.2o7beta 起不再停顿）', () => {
  // 独立的 spread 状态：堆叠可见与摊开必须是两个时刻
  assert.match(composer, /const panelSpread = ref\(false\)/)
  assert.match(composer, /class="mode-pop"[\s\S]{0,140}spread: panelSpread/)
  // 用户明确取消停顿：STACK_PAUSE_MS 必须为 0（结构保留，将来要改回去只动这个数）
  assert.match(composer, /const STACK_PAUSE_MS = 0\b/)
  assert.match(
    composer,
    /spreadTimer = setTimeout\(\(\) => \{[\s\S]{0,120}panelSpread\.value = true[\s\S]{0,40}\}, EMERGE_MS \+ STACK_PAUSE_MS\)/,
    '摊开必须排在"浮出 + 停顿"之后',
  )
  // 卡片在 .open（堆叠态）时就可见，不能等摊开才出现
  const openPill = /\.mode-pop\.open \.pill \{([^}]*)\}/.exec(composer)?.[1] ?? ''
  assert.match(openPill, /opacity:\s*1/, '堆叠态就必须可见')
  assert.doesNotMatch(openPill, /transform:\s*none/, '堆叠态不能把位移清掉')
})

test('堆叠是"一叠牌"：全部重合在锚点上，只错开几像素（不能是竖排）', () => {
  // 必须读真实布局位置把四张卡收到同一个锚点，再用**固定的小步长**错开。
  // 曾经写成 `index * (cardHeight + cardGap)`：每张卡差一整个卡高，四张卡排成
  // 一条竖列 —— 用户看到的正是"展开动画是竖向排列"，而不是"叠在一起"。
  assert.match(composer, /const STACK_STEP_Y = \d+/, '错开步长必须是常量')
  assert.match(composer, /--sx', `\$\{anchorLeft - el\.offsetLeft \+ index \* STACK_STEP_X\}px`/)
  assert.match(composer, /--sy', `\$\{anchorTop - el\.offsetTop \+ index \* STACK_STEP_Y\}px`/)
  assert.doesNotMatch(composer, /cardHeight \+ cardGap/, '不能按卡片高度错开（那会变成竖排）')
  // 步长要小到卡与卡真的重叠：超过 8px 就露出两张卡的缝，看着又不叠了
  const step = Number(/const STACK_STEP_Y = (\d+)/.exec(composer)?.[1])
  assert.ok(step > 0 && step <= 8, `STACK_STEP_Y(${step}) 必须在 1..8px，才叠得起来`)
  // 堆叠的关键：卡片本身不再缩小（缩小了就只是"一坨点"，不是一叠牌）
  const pillBase = /\.mode-pop \.pill \{([^}]*)\}/.exec(composer)?.[1] ?? ''
  assert.doesNotMatch(pillBase, /scale\(/, '卡片基础态不能再缩放')
})

test('模式面板：收起用定时器兜底，不依赖过渡回调', () => {
  assert.match(composer, /unmountTimer = setTimeout\(/)
  assert.match(composer, /function clearTimers\(\)/)
  assert.doesNotMatch(
    composer,
    /onReverseComplete/,
    '不能再依赖 GSAP 的 onReverseComplete —— 它没跑到面板就卡在可见态',
  )
})

test('收起：卡片从左往右折回（不是从右往左）', () => {
  assert.match(composer, /--fold-delay/)
  // 需求原文是"从左往右折叠在一起"，所以延迟必须随 index 递增；
  // 写成 (length - 1 - index) 就是从右往左，方向反了。
  assert.match(composer, /--fold-delay', `\$\{index \* CARD_STAGGER_MS\}ms`/)
  assert.doesNotMatch(composer, /--fold-delay', `\$\{Math\.max\(0, buttons\.length - 1 - index\)/)
})

test('收起：整块向右滑出屏幕，而不是沉回输入框', () => {
  // 独立的 dismissing 状态：收回的终点和展开的起点不是同一个
  assert.match(composer, /const panelDismissing = ref\(false\)/)
  assert.match(composer, /panelDismissing\.value = true/)
  const dismissing = /\.mode-pop\.open\.dismissing \{([^}]*)\}/.exec(composer)?.[1] ?? ''
  assert.match(dismissing, /translateX\(1[0-9]{2}%\)/, '必须向右滑出视口')
  assert.doesNotMatch(dismissing, /translateY/, '不能是向下沉')
})

test('收起：折回与右滑同时发生（不是等折完再走）', () => {
  // closePanel 里不能在"折完"之后再触发滑动 —— 两件事要一起开始
  const closeBody = /function closePanel\(\) \{([\s\S]*?)\n\}/.exec(composer)?.[1] ?? ''
  assert.match(closeBody, /panelSpread\.value = false/)
  assert.match(closeBody, /panelDismissing\.value = true/)
  // 两条赋值之间不应夹着 setTimeout（那是串联的标志）
  const spreadAt = closeBody.indexOf('panelSpread.value = false')
  const dismissAt = closeBody.indexOf('panelDismissing.value = true')
  const between = closeBody.slice(Math.min(spreadAt, dismissAt), Math.max(spreadAt, dismissAt))
  assert.doesNotMatch(between, /setTimeout/, '折回与右滑之间不能有定时器（那会把"同时"变成"先后"）')
})

test('滑出的对话卡片有边框，和侧栏分得开', () => {
  const pushed = /\.shell\.pushed \{([^}]*)\}/.exec(chat)?.[1] ?? ''
  assert.match(pushed, /box-shadow:/, '需要有描边/投影')
  assert.match(pushed, /0 0 0 1px var\(--border-strong\)/, '1px 描边')
})

test('模式面板不再用 GSAP 时间线驱动', () => {
  assert.doesNotMatch(composer, /buildModeTimeline/)
  assert.doesNotMatch(composer, /modeTimeline/)
})

test('侧栏面板自身不做动画（避免和卡片滑动叠成"闪一下"）', () => {
  const panel = /\.panel \{([^}]*)\}/.exec(drawer)?.[1] ?? ''
  assert.doesNotMatch(panel, /transition\s*:/, '面板不该有自己的过渡')
  assert.doesNotMatch(panel, /opacity:\s*0/, '面板不该默认透明')
})

test('展开动画从「模式」按键分裂出来（1.5.2o8beta）', () => {
  const base = /\.mode-pop \{([^}]*)\}/.exec(composer)?.[1] ?? ''
  // 缩放原点必须是卡摞中心（由 layoutStack 现算写进 --origin-x/--origin-y）。
  // 原点若在容器中心，容器是整行宽的、中心离卡摞很远，缩放时内容会横向漂移 ——
  // 那正是以前被吐槽的"从右边往上冒"。
  assert.match(base, /transform-origin:\s*var\(--origin-x/)
  assert.match(base, /var\(--origin-y/)
  // 起点位移与起点缩放都来自按键几何，不能写死
  assert.match(base, /translate\(var\(--from-x/)
  assert.match(base, /var\(--from-y/)
  assert.match(base, /scale\(var\(--from-scale/)
  // 「模式」按键必须挂上 ref，否则拿不到它的位置与尺寸
  assert.match(composer, /ref="modeToggleEl"/)
  // layoutStack 必须真的把三个几何量算出来
  assert.match(composer, /--origin-x/, '要写入 origin-x')
  assert.match(composer, /--from-x/, '要写入 from-x')
  assert.match(composer, /--from-scale/, '要写入 from-scale')
  // 起点缩放要比 1 小（分裂 = 先缩成按键大小），且不能缩到看不见
  assert.match(composer, /Math\.max\(0\.08,\s*Math\.min\(1,\s*scale\)\)/)
})
