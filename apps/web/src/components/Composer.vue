<script setup lang="ts">
/**
 * 输入区。
 * DeepSeek 的输入框是「一整块大圆角卡片」：文本在上，模式开关和发送在下一行。
 * 这里的两个 chip 都对应真实协议能力（enter/exit_plan_mode、set_permission_mode），
 * ⊕ 展开指令面板：Android SAF 文件导入 + 可滚动的斜杠指令列表。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { PERMISSION_MODES, REASONING_EFFORTS, useConfigStore } from '@/stores/config'
import { useSessionStore } from '@/stores/session'
import { useRouter } from 'vue-router'
import { pushTrace, setControlModeActive } from '@/bridge/controlFloat'
import { MorphIcon } from 'morphicons/vue'
import { gsap, prefersReducedMotion } from '@/composables/useGsap'
import { Menu, ChevronUp, Plus, X } from 'lucide'
import CoomiIcon from './CoomiIcon.vue'

const session = useSessionStore()
const config = useConfigStore()
const router = useRouter()

/** 斜杠指令：点击后填入输入框，可编辑后发送。 */
const SLASH_COMMANDS = [
  { name: '/loop', desc: '循环执行直到完成' },
  { name: '/plan', desc: '进入计划模式' },
  { name: '/mcp', desc: '管理 MCP 服务器' },
  { name: '/skills', desc: '查看可用技能' },
  { name: '/memory', desc: '查看持久记忆（不含记忆库）' },
  { name: '/workflows', desc: '打开记忆库与动态工作流' },
  { name: '/compact', desc: '压缩当前上下文（需确认）' },
]

const text = ref('')
const textarea = ref<HTMLTextAreaElement | null>(null)
const controlText = ref('')
const controlInput = ref<HTMLTextAreaElement | null>(null)
function toggleModelForControl() { /* 复用顶栏模型选择 */ (window as any).dispatchEvent(new Event('coomi:open-model-picker')) }
function sendControl() {
  const t = controlText.value.trim()
  if (!t) return
  controlText.value = ''
  if (!accessibilityReady.value && !overlayReady.value) {
    controlThinking.value = '还没有屏幕操控权限，请先点上方「开启无障碍」'
    return
  }
  controlThinking.value = `已发送：${t}`
  pushFloat('控制模式 · 正在发送', t)
  session.sendMessage(`控制模式任务：先用 ui_automation read_screen 查看当前手机界面，再按控件文字或返回坐标操作，每次操作后读取界面验证。\n\n${t}`)
}
const sendButton = ref<HTMLButtonElement | null>(null)
const quickOpen = ref(false)
const transferText = ref('')
const transferProgress = ref(0)
const textareaScrollable = ref(false)
const importedFiles = ref<string[]>([])
const hasNative = typeof window !== 'undefined' && !!window.CoomiAndroid

const canSend = computed(() => text.value.trim().length > 0 || importedFiles.value.length > 0)
const isJumpIn = computed(() => session.isBusy && canSend.value)
const showStop = computed(() => session.isBusy && !canSend.value)

/**
 * 底部模式按钮溢出折叠。
 *
 * 其他机型上「计划 / 权限 / 超载 / 控制」一排按钮可能超宽 → 折叠成三横杠，
 * 点击后向上展开（Morphicons 把三横杠变形成向上箭头 + 面板上升）。
 */
const barOpen = ref(false)
const barIcon = ref(Menu)
const quickIcon = computed(() => quickOpen.value ? X : Plus)
function toggleBar() {
  barOpen.value = !barOpen.value
  barIcon.value = barOpen.value ? ChevronUp : Menu
}

/**
 * 模式面板：**三阶段**动画。
 *
 *   阶段一：四张卡以「叠成一摞」的形态从输入框里升上来（模糊 → 清晰，由快到慢）。
 *           这一阶段结束时它们**仍然是摞着的**，只是露出来了。
 *   阶段二：在这摞卡上停顿 0.40 秒。**这个停顿只属于"堆叠 → 摊开"之间**，
 *           其他地方（浮出、收起）都没有任何停顿。
 *   阶段三：从左到右依次摊开成正常的一排按钮。
 *
 * 收回：反着来 —— 四张卡从右往左折回成一摞，然后整摞沉回输入框里。
 *
 * ## 为什么用 CSS 过渡 + 两个状态类
 *
 * 上一版用一根 GSAP 时间线 + reverse() 实现"可打断倒放"，真机上出现「收不回」
 * 「只播一半」—— JS 状态机只要有一个回调没跑到（元素被 v-if 卸掉、时间线绑到
 * 游离节点），面板就永远卡在可见态。
 *
 * 现在用两段 CSS 过渡：`.open` 负责"露出来"，`.spread` 负责"摊开"。过渡在被打断时
 * 由浏览器自己从当前计算值往回走，没有回调依赖；摘节点另用定时器兜底。
 * 这样"堆叠 → 停顿 → 摊开"这三个阶段的边界就是两个明确的时刻，而不是一堆补间。
 *
 * 拆成两个类也顺带解决了"卡片在浮出期间是隐形的"那个 bug：上一版把 0.4s 的停顿
 * 也算进了卡片的 transition-delay，于是卡片要等 740ms 才出现，浮出阶段根本看不到
 * 那摞卡，动画自然就"没按说的做"。
 */
const modePopEl = ref<HTMLElement | null>(null)
/** 「模式」按键本体：动画的起点。 */
const modeToggleEl = ref<HTMLElement | null>(null)
/** 是否挂在 DOM 上（比 barOpen 长命：收起动画播完才摘）。 */
const panelMounted = ref(false)
/** 阶段一：容器与卡片已"露出来"，卡片仍是摞着的。 */
const panelOpen = ref(false)
/** 阶段三：卡片摊开。 */
const panelSpread = ref(false)
/** 收回中：整块向右滑出屏幕（与展开的"向上浮出"是不同的终点）。 */
const panelDismissing = ref(false)

let appearTimer: ReturnType<typeof setTimeout> | null = null
let spreadTimer: ReturnType<typeof setTimeout> | null = null
let foldTimer: ReturnType<typeof setTimeout> | null = null
let unmountTimer: ReturnType<typeof setTimeout> | null = null

/** 动画总开关：任一关闭就退化成直接显示/隐藏，不播过渡。 */
const modeAnimOff = computed(
  () => prefersReducedMotion() || !config.sendMorphAnimation || config.allAnimationsOff,
)

// 时序（毫秒）。改这几个数字就等于改整个节奏，注释里写清各自的归属。
/** 阶段一：整摞卡浮出到位。 */
const EMERGE_MS = 340
/**
 * 阶段二：堆叠 → 摊开之间的停顿。
 *
 * 用户 1.5.2o7beta 明确要求「干脆别停顿」：一摞卡升出来就立刻摊开，
 * 中间不再空等。留成常量而不是删掉，是因为停顿曾经是需求的一部分，
 * 将来要改回去只动这一个数字。
 */
const STACK_PAUSE_MS = 0
/** 阶段三：单张卡摊开的时长与相邻两张的错开间隔。 */
const CARD_MS = 260
const CARD_STAGGER_MS = 55
/** 收起时整摞沉回去的时长。 */
const SINK_MS = 300

function clearTimers() {
  for (const timer of [appearTimer, spreadTimer, foldTimer, unmountTimer]) {
    if (timer) clearTimeout(timer)
  }
  appearTimer = spreadTimer = foldTimer = unmountTimer = null
}

/**
 * 状态条（"Comax 正在思考…"那一行）由 StatusBar 组件渲染在 ChatView 上，
 * 不在本组件的可修改范围里，所以按 DOM 找它、只动 transform/opacity/filter。
 * 也用 CSS 过渡，这样它和卡牌是同一套动画，打断时一起往回走。
 */
function findStatusBar(): HTMLElement | null {
  return document.querySelector<HTMLElement>('.sbar')
}

function setStatusBarHidden(hidden: boolean) {
  const bar = findStatusBar()
  if (!bar) return
  bar.style.transition = 'opacity .26s ease, transform .26s ease, filter .26s ease'
  if (hidden) {
    bar.style.opacity = '0'
    bar.style.transform = 'translateY(-14px)'
    bar.style.filter = 'blur(6px)'
    bar.style.pointerEvents = 'none'
    return
  }
  bar.style.opacity = ''
  bar.style.transform = ''
  bar.style.filter = ''
  bar.style.pointerEvents = ''
}

function clearStatusBar() {
  const bar = findStatusBar()
  if (!bar) return
  bar.style.transition = ''
  bar.style.opacity = ''
  bar.style.transform = ''
  bar.style.filter = ''
  bar.style.pointerEvents = ''
}

/**
 * 把四张卡"垂直摞成一叠"所需的位移写进 CSS 变量。
 *
 * 用户要的是"垂直摞起来"（像一叠牌），不是同一位置错开：四张卡全部落在第一张的
 * 位置上，但每张往下错开半个按钮的高度，看起来就是从上到下整齐叠着的一摞。
 *
 * 必须读 offsetLeft/offsetTop 现算：按钮是 flex 排布的，位置由布局决定，只有把
 * 每张卡平移到同一个锚点，它们才真的叠起来。上一版只给了同一个 x 偏移量，
 * 结果四张卡各占各的位置，从来没叠过。
 */

/**
 * 堆叠错开步长：**只错开几像素**，让四张卡几乎重合、只露一点边缘，才是一叠牌。
 *
 * 曾经按「一张卡的高度」递进错开，四张卡排成一条竖列 —— 用户看到的正是
 * 「展开动画是竖向排列」，不是「叠在一起」。
 */
const STACK_STEP_X = 1
const STACK_STEP_Y = 4

/** 累加 offsetLeft/offsetTop，得到元素在某个祖先（不含 transform）坐标系里的位置。 */
function offsetWithin(el: HTMLElement, ancestor: HTMLElement): { x: number; y: number } {
  let x = 0
  let y = 0
  let node: HTMLElement | null = el
  while (node && node !== ancestor) {
    x += node.offsetLeft
    y += node.offsetTop
    node = node.offsetParent as HTMLElement | null
  }
  return { x, y }
}

/**
 * 计算「从『模式』按键分裂出来」这套动画的几何。
 *
 * 1.5.2o8beta 起动画不再是「一横条从输入框升上来」，而是：
 *   ① 整摞卡缩成「模式」按键那么大，正好盖在按键上；
 *   ② 放大 / 上浮到位（还是摞着）；
 *   ③ 再从摞着的状态从左到右摊开。
 *
 * 关键是把 `transform-origin` 钉在**卡摞的中心**（不是容器中心，容器是整行宽的，
 * 中心离卡摞很远）。origin 在卡摞中心时，缩放不会让内容横向漂移；再配一个 translate
 * 把卡摞中心搬到按键中心，起点就精确落在按键上 —— 看起来就是按键裂成了四张卡。
 */
function layoutStack(panel: HTMLElement) {
  const buttons = Array.from(panel.querySelectorAll<HTMLElement>('.pill'))
  if (buttons.length === 0) return
  const first = buttons[0]
  const anchorLeft = first.offsetLeft
  const anchorTop = first.offsetTop
  const stackWidth = first.offsetWidth + (buttons.length - 1) * STACK_STEP_X
  const stackHeight = first.offsetHeight + (buttons.length - 1) * STACK_STEP_Y

  // origin = 卡摞中心（panel 局部坐标）
  const originX = anchorLeft + stackWidth / 2
  const originY = anchorTop + stackHeight / 2
  panel.style.setProperty('--origin-x', `${originX.toFixed(1)}px`)
  panel.style.setProperty('--origin-y', `${originY.toFixed(1)}px`)

  // 起点位移 = 按键中心 − 卡摞中心（换算到同一个 .composer 坐标系）
  const host = panel.offsetParent as HTMLElement | null
  const toggle = modeToggleEl.value
  if (host && toggle && stackWidth > 0 && stackHeight > 0) {
    const togglePos = offsetWithin(toggle, host)
    const toggleCenterX = togglePos.x + toggle.offsetWidth / 2
    const toggleCenterY = togglePos.y + toggle.offsetHeight / 2
    const stackCenterX = panel.offsetLeft + originX
    const stackCenterY = panel.offsetTop + originY
    panel.style.setProperty('--from-x', `${(toggleCenterX - stackCenterX).toFixed(1)}px`)
    panel.style.setProperty('--from-y', `${(toggleCenterY - stackCenterY).toFixed(1)}px`)
    // 缩放比 = 按键尺寸 / 卡摞尺寸：起点时卡摞正好盖住按键
    const scale = Math.min(
      toggle.offsetWidth / stackWidth,
      toggle.offsetHeight / stackHeight,
    )
    panel.style.setProperty('--from-scale', Math.max(0.08, Math.min(1, scale)).toFixed(3))
  }

  buttons.forEach((el, index) => {
    // 全部收到第一张卡的位置，再按固定小步长错开 —— 这才是「一叠牌」，不是竖列
    el.style.setProperty('--sx', `${anchorLeft - el.offsetLeft + index * STACK_STEP_X}px`)
    el.style.setProperty('--sy', `${anchorTop - el.offsetTop + index * STACK_STEP_Y}px`)
    // 摊开与折回都从左往右
    el.style.setProperty('--spread-delay', `${index * CARD_STAGGER_MS}ms`)
    el.style.setProperty('--fold-delay', `${index * CARD_STAGGER_MS}ms`)
  })
}

function cardCount(): number {
  return modePopEl.value?.querySelectorAll('.pill').length ?? 0
}

function openPanel() {
  clearTimers()
  panelOpen.value = false
  panelSpread.value = false
  panelDismissing.value = false
  panelMounted.value = true
  void nextTick().then(() => {
    const panel = modePopEl.value
    if (panel) layoutStack(panel)
    // 等两帧再翻状态：第一帧让元素带着"收起态"真正画到屏幕上，第二帧才切到
    // 阶段一。只等一帧的话，"插入"与"改类名"会合并进同一次绘制，浏览器看不到
    // 变化前的样式，过渡不会触发 —— 表现就是面板"啪"地一下出现，没有动画。
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        panelOpen.value = true
        setStatusBarHidden(true)
      })
    })
    // 阶段二 → 阶段三：浮出到位后再停 0.4 秒才摊开
    spreadTimer = setTimeout(() => {
      spreadTimer = null
      panelSpread.value = true
    }, EMERGE_MS + STACK_PAUSE_MS)
  })
}

function closePanel() {
  clearTimers()
  setStatusBarHidden(false)
  // 两件事**同时**发生：
  //   ① 四张卡从左往右逐张折回（--fold-delay 依次递增）
  //   ② 整块向右滑出屏幕（.dismissing）
  // 这里刻意保持 panelOpen = true：一旦置 false，容器会回到"沉在输入框里"那个
  // 起点，收回就变成向下沉了 —— 而需求要的是向右滑出去。
  panelSpread.value = false
  panelDismissing.value = true
  const foldTotal = Math.max(0, cardCount() - 1) * CARD_STAGGER_MS + CARD_MS
  // 兜底摘节点：过渡回调在被打断/元素被替换时可能不触发，用定时器就不会卡住
  unmountTimer = setTimeout(() => {
    unmountTimer = null
    panelMounted.value = false
    panelOpen.value = false
    panelDismissing.value = false
    clearStatusBar()
  }, Math.max(foldTotal, SINK_MS) + 80)
}

watch(barOpen, open => {
  if (modeAnimOff.value) {
    clearTimers()
    panelOpen.value = open
    panelSpread.value = open
    panelDismissing.value = false
    panelMounted.value = open
    if (!open) clearStatusBar()
    return
  }
  if (open) openPanel()
  else closePanel()
})

onMounted(() => {
  // 组件可能在面板打开时被卸载重建，清掉可能残留的状态条样式
  clearStatusBar()
})

onBeforeUnmount(() => {
  clearTimers()
  clearStatusBar()
})

const modeLabel = computed(() => PERMISSION_MODES.find(m => m.mode === config.permissionMode)?.label ?? '')
const providerReady = computed(() => config.providers.some(provider => (
  provider.id === config.activeId
  && provider.models.length > 0
  && Boolean(provider.baseUrl)
)))

function autoGrow() {
  const el = textarea.value
  if (!el) return
  el.style.height = 'auto'
  const scrollHeight = el.scrollHeight
  textareaScrollable.value = scrollHeight > 132
  el.style.height = Math.min(scrollHeight, 132) + 'px'
}

async function submit() {
  if (!canSend.value) return
  if (!providerReady.value) {
    await config.fetchProviders()
    if (!providerReady.value) {
      await router.push('/providers')
      return
    }
  }
  const visibleText = text.value.trim()
  const files = [...importedFiles.value]
  const fileInstruction = files.length ? `请读取这些已导入文件：\n${files.join('\n')}` : ''
  const requestText = [visibleText, fileInstruction].filter(Boolean).join('\n\n')
  const fileNames = files.map(path => path.split('/').pop() || '文件')
  const displayText = visibleText
  const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true
  const morph = config.sendMorphAnimation && !config.allAnimationsOff && !reduceMotion && !session.isBusy && !session.pendingEdit
  const source = sendButton.value?.getBoundingClientRect()
  const messageId = session.sendMessage(requestText, displayText, morph && !!source, fileNames)
  if (messageId && morph && source) {
    window.dispatchEvent(new CustomEvent('coomi:send-morph', { detail: {
      messageId,
      source: { left: source.left, top: source.top, width: source.width, height: source.height },
    } }))
    // 覆盖层若因页面切换或旧 WebView 能力不足未接到事件，不能让消息永久隐藏。
    setTimeout(() => session.completeSendMorph(messageId), 1600)
  }
  text.value = ''
  importedFiles.value = []
  await nextTick()
  autoGrow()
}

/** 主按钮：空着且在忙 = 停止，其余 = 发送 / 插队。 */
function tapPrimary() {
  if (showStop.value) session.cancel()
  else submit()
}

function onKeydown(e: KeyboardEvent) {
  // Enter 默认换行（需求：换行键就换行）；Ctrl/Cmd+Enter 仍可快捷发送。
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); submit() }
}

const modeNotice = ref('')
let modeNoticeTimer: ReturnType<typeof setTimeout> | null = null
function flashModeNotice(text: string) {
  modeNotice.value = text
  if (modeNoticeTimer) clearTimeout(modeNoticeTimer)
  modeNoticeTimer = setTimeout(() => { modeNotice.value = ''; modeNoticeTimer = null }, 2600)
}
function cycleProductionMode() {
  const order: ('normal' | 'overload' | 'berserk')[] = ['normal', 'overload', 'berserk']
  const idx = order.indexOf(config.productionMode)
  const next = order[(idx + 1) % order.length]
  if (next === 'berserk' && !config.berserkModel) {
    // 未配置狂暴模型：回退到普通模式并明确提示（避免卡在超载/狂暴）
    session.setProductionMode('normal')
    flashModeNotice('未设置狂暴模型，请到「设置 → 狂暴模型」选择后再切换狂暴')
    return
  }
  session.setProductionMode(next)
}
function toggleProductionMode() { cycleProductionMode() }

  function cycleMode() { session.setPermissionMode(config.cyclePermissionMode()) }

async function insert(t: string) {
  text.value = text.value.trim() ? text.value.replace(/\s+$/, '') + '\n' + t : t
  quickOpen.value = false
  await nextTick()
  autoGrow()
  textarea.value?.focus()
}

/** 斜杠指令：填入输入框（需要补参数的本地能力指令用这条）。 */
async function insertSlash(cmd: string) {
  text.value = cmd
  quickOpen.value = false
  await nextTick()
  autoGrow()
  textarea.value?.focus()
}

/**
 * 「指令」列表里的每条命令：点击即执行，执行前统一弹一次确认。
 * 不再先填进输入框让用户手动发送 —— 需要模型参与的命令直接把指令原文
 * 作为一轮请求发出去，其余命令走本地动作（切模式 / 跳页面）。
 */
interface PendingCommand { name: string; desc: string; run: () => void | Promise<void> }
const commandConfirm = ref<PendingCommand | null>(null)

const COMMAND_RUNNERS: Record<string, () => void | Promise<void>> = {
  '/loop': () => { session.sendMessage('/loop') },
  '/plan': () => { session.togglePlanMode() },
  '/mcp': () => { void router.push('/catalog') },
  '/skills': () => { void router.push('/catalog') },
  '/memory': () => { session.sendMessage('/memory') },
  '/workflows': () => { void router.push('/workflows') },
  '/compact': () => { session.sendMessage('/compact') },
}

function requestCommand(cmd: { name: string; desc: string }) {
  const run = COMMAND_RUNNERS[cmd.name]
  if (!run) { void insertSlash(cmd.name); return }
  quickOpen.value = false
  commandConfirm.value = { name: cmd.name, desc: cmd.desc, run }
}

async function confirmCommand() {
  const request = commandConfirm.value
  commandConfirm.value = null
  if (!request) return
  try { await request.run() } catch { /* 动作自身会给出提示 */ }
}

function toggleQuick() { quickOpen.value = !quickOpen.value }
const controlMode = ref(false)
const riskConfirm = ref(false)
const controlThinking = ref('')
const controlChatPage = ref(false)
/** 控制模式的两个系统权限：无障碍（操控屏幕）+ 悬浮窗（桌面悬浮层）。 */
const accessibilityReady = ref(false)
const overlayReady = ref(false)
const floatRunning = ref(false)
let controlPageTimer: ReturnType<typeof setInterval> | null = null
let controlChatTimer: ReturnType<typeof setTimeout> | null = null

function nativeBridge(): any {
  return (window as any).CoomiAndroid
}

/** 同步三个系统状态：无障碍开关、悬浮窗权限、悬浮层是否在跑。 */
function refreshControlPermissions() {
  const bridge = nativeBridge()
  if (!bridge) return
  try {
    if (typeof bridge.isAccessibilityEnabled === 'function') accessibilityReady.value = !!bridge.isAccessibilityEnabled()
    if (typeof bridge.isOverlayGranted === 'function') overlayReady.value = !!bridge.isOverlayGranted()
    if (typeof bridge.isControlFloatRunning === 'function') floatRunning.value = !!bridge.isControlFloatRunning()
  } catch { /* 桥不可用时保持原状态 */ }
}

/** 申请无障碍权限（系统设置页需用户手动开启，回到应用后轮询感知）。 */
function requestAccessibility() {
  const bridge = nativeBridge()
  if (bridge && typeof bridge.requestAccessibilityPermission === 'function') {
    bridge.requestAccessibilityPermission()
  }
}

/** 申请悬浮窗权限。 */
function requestOverlay() {
  const bridge = nativeBridge()
  if (bridge && typeof bridge.requestOverlayPermission === 'function') {
    bridge.requestOverlayPermission()
  }
}

/** 把当前动作与思考内容推到桌面悬浮层。 */
function pushFloat(title: string, body: string) {
  pushTrace(title, body)
}

function toggleControlMode() {
  if (controlMode.value) { exitControlMode(); return }
  // 风险提示用应用内浮层（WebView 中 window.confirm 会被吞）
  riskConfirm.value = true
}
function confirmControlMode() {
  riskConfirm.value = false
  controlMode.value = true
  controlThinking.value = ''
  // 打开转发开关：之后引擎侧的思考 / 工具调用会自动同步到悬浮层。
  setControlModeActive(true)
  refreshControlPermissions()
  // 无障碍是控制模式的主力：没有它就只能退回 Shizuku 的坐标点按。
  if (!accessibilityReady.value) requestAccessibility()
  if (!overlayReady.value) requestOverlay()
  window.dispatchEvent(new CustomEvent('coomi:control-mode', { detail: { enabled: true } }))
  // 待机检测：每 5s 尝试原生桥，检测进入微信/QQ 聊天页，同时刷新权限与悬浮层状态
  controlPageTimer = setInterval(() => {
    const bridge = nativeBridge()
    refreshControlPermissions()
    if (bridge && typeof bridge.controlForegroundApp === 'function') {
      try {
        const app = bridge.controlForegroundApp()
        controlChatPage.value = /weixin|wechat|qq\.com|tencent|mobileqq/i.test(String(app || ''))
      } catch { controlChatPage.value = false }
    }
  }, 5000)
}
function cancelControlMode() { riskConfirm.value = false }
function exitControlMode() {
  controlMode.value = false
  controlChatPage.value = false
  controlThinking.value = ''
  setControlModeActive(false)
  const bridge = nativeBridge()
  if (bridge && typeof bridge.stopControlFloat === 'function') {
    try { bridge.stopControlFloat() } catch { /* 忽略 */ }
  }
  floatRunning.value = false
  if (controlPageTimer) { clearInterval(controlPageTimer); controlPageTimer = null }
  if (controlChatTimer) { clearTimeout(controlChatTimer); controlChatTimer = null }
  window.dispatchEvent(new CustomEvent('coomi:control-mode', { detail: { enabled: false } }))
}
/** 待机检测到聊天页 → 用户点「对话」：把要回复的内容注入输入框并聚焦。 */
function startControlReply() {
  controlChatPage.value = false
  controlThinking.value = '检测到聊天页面，请在下方输入回复内容'
  pushFloat('控制模式 · 已进入聊天页', '可以输入回复内容，点发送即可填入并送出')
  nextTick(() => controlInput.value?.focus())
}

/**
 * 控制模式：无障碍就绪后自动把桌面悬浮层拉起来。
 * 悬浮层负责在用户切到微信/QQ 之后继续显示「正在调用哪个工具 / 思考到哪一步」，
 * 所以它必须独立于应用界面存在。
 */
watch([accessibilityReady, overlayReady, controlMode], ([accessible, overlay, active]) => {
  const bridge = nativeBridge()
  if (!active) return
  if (!accessible && !overlay) return
  if (!bridge?.startControlFloat) return
  try { bridge.startControlFloat() } catch { /* 忽略 */ }
  pushFloat('控制模式已就绪', accessible ? '无障碍已开启，可以直接操作屏幕' : '悬浮窗已开启，屏幕操控仍需要无障碍')
})

/** 思考/动作变化时同步到悬浮层，让切到别的 App 后也能看到进度。 */
watch(controlThinking, value => {
  if (!controlMode.value) return
  pushFloat('控制模式 · 执行中', value || '')
})



function importFiles() { quickOpen.value = false; window.CoomiAndroid?.importFiles?.() }
function authorizeFolder() { quickOpen.value = false; window.CoomiAndroid?.authorizeFolder?.() }
function onTransferProgress(event: Event) {
  const detail = (event as CustomEvent<{ message?: string; progress?: number }>).detail ?? {}
  transferText.value = detail.message ?? '正在传输文件'
  transferProgress.value = detail.progress ?? 0
}
function onFilesImported(event: Event) {
  const detail = (event as CustomEvent<{ paths?: string[]; requestId?: string }>).detail ?? {}
  const paths = detail.paths ?? []
  transferText.value = paths.length ? `已导入 ${paths.length} 个文件` : '文件导入完成'
  transferProgress.value = 100
  if (detail.requestId) session.completeFileTransfer(detail.requestId, paths)
  else if (paths.length) importedFiles.value = Array.from(new Set([...importedFiles.value, ...paths]))
  setTimeout(() => { transferText.value = ''; transferProgress.value = 0 }, 2600)
}
function onFileExported(event: Event) {
  const detail = (event as CustomEvent<{ requestId?: string; path?: string }>).detail ?? {}
  if (detail.requestId) session.completeFileTransfer(detail.requestId, detail.path ? [detail.path] : [])
}
function removeImportedFile(path: string) {
  importedFiles.value = importedFiles.value.filter(item => item !== path)
}
function onPrefillDraft(event: Event) {
  const detail = (event as CustomEvent<{ sessionId?: string; text?: string }>).detail ?? {}
  if ((detail.sessionId && detail.sessionId !== session.sessionId) || typeof detail.text !== 'string') return
  text.value = detail.text
  void nextTick(autoGrow)
}
onMounted(() => {
  window.addEventListener('coomi:file-transfer-progress', onTransferProgress)
  window.addEventListener('coomi:files-imported', onFilesImported)
  window.addEventListener('coomi:file-exported', onFileExported)
  window.addEventListener('coomi:prefill-draft', onPrefillDraft)
  loadDraft()
})
onBeforeUnmount(() => {
  window.removeEventListener('coomi:file-transfer-progress', onTransferProgress)
  window.removeEventListener('coomi:files-imported', onFilesImported)
  window.removeEventListener('coomi:file-exported', onFileExported)
  window.removeEventListener('coomi:prefill-draft', onPrefillDraft)
  saveDraft()
})

// ── 草稿按会话持久化：每个会话（含新对话）各自保留输入框内容 ──
const DRAFT_PREFIX = 'coomi.draft.'
let draftTimer: ReturnType<typeof setTimeout> | null = null

function draftKey(id: string) { return DRAFT_PREFIX + id }

function loadDraft() {
  let saved = ''
  try { saved = localStorage.getItem(draftKey(session.sessionId)) ?? '' } catch { /* ignore */ }
  text.value = saved
  void nextTick(autoGrow)
}

function saveDraft() {
  if (draftTimer) { clearTimeout(draftTimer); draftTimer = null }
  try { localStorage.setItem(draftKey(session.sessionId), text.value) } catch { /* ignore */ }
}

// 切会话（含新建会话）时：先把旧会话的草稿存回【旧】key，再加载新会话草稿。
// 注意：watch 回调里 session.sessionId 已经是新值，保存必须用回调的 prev 参数，
// 否则旧内容会被写进新会话的 key，导致所有会话显示同一个草稿。
watch(() => session.sessionId, (next, prev) => {
  if (prev && prev !== next) {
    try { localStorage.setItem(draftKey(prev), text.value) } catch { /* ignore */ }
  }
  loadDraft()
})
watch(text, () => {
  if (draftTimer) clearTimeout(draftTimer)
  draftTimer = setTimeout(saveDraft, 200)
})
</script>

<template>
  <div class="composer">
    <Transition name="banner">
      <div v-if="session.pendingEdit" class="edit-banner">
        <span>正在编辑上一条消息，发送将覆盖该轮执行</span>
        <button @click="session.cancelEditMessage()">取消编辑</button>
      </div>
    </Transition>
    <div v-if="transferText" class="transfer">
      <span>{{ transferText }}</span><progress :value="transferProgress" max="100" />
    </div>
    <div v-if="quickOpen || session.lifeStatsOpen" class="quick-scrim" @click="quickOpen = false; session.lifeStatsOpen = false" />
    <div v-if="quickOpen" class="quick">
      <p class="qhead reasoning-head">推理强度</p>
      <div class="reasoning-options"><button v-for="item in REASONING_EFFORTS" :key="item.value" :class="{ selected: config.reasoningEffort === item.value }" @click="session.setReasoningEffort(item.value)">{{ item.label }}</button></div>
      <p class="qhead">本地能力</p>
      <div class="local-actions">
        <button class="qchip file" @click="insertSlash('/local_model ')"><CoomiIcon name="sparkle" :size="15" />本地模型</button>
        <button class="qchip file" @click="insertSlash('/local_ocr ')"><CoomiIcon name="scan" :size="15" />本地 OCR</button>
        <button class="qchip file" @click="insertSlash('/local_tts ')"><CoomiIcon name="volume" :size="15" />语音合成</button>
        <button class="qchip file" @click="insertSlash('/ui_automation ')"><CoomiIcon name="cursor" :size="15" />UI 自动化</button>
      </div>
      <p class="qhead">指令</p>
      <div v-if="hasNative" class="file-actions">
        <button class="qchip file" @click="importFiles"><CoomiIcon name="fileRead" :size="15" />选择文件</button>
        <button class="qchip file" @click="authorizeFolder"><CoomiIcon name="folder" :size="15" />授权目录</button>
      </div>
      <div class="slash-list">
        <button v-for="c in SLASH_COMMANDS" :key="c.name" class="slash-item" @click="requestCommand(c)">
          <code>{{ c.name }}</code><span>{{ c.desc }}</span>
        </button>
      </div>
    </div>
    <div v-if="commandConfirm" class="risk-mask" @click.self="commandConfirm = null">
      <div class="risk-dialog" role="alertdialog" aria-modal="true" aria-label="确认执行指令">
        <p class="cmd-title">执行 {{ commandConfirm.name }}？</p>
        <p class="risk-text">
          {{ commandConfirm.desc }}
          <template v-if="commandConfirm.name === '/compact'">会保留当前工作方向并压缩旧消息；这个操作会调用模型，完成后不能直接恢复原始上下文。</template>
          <template v-else-if="commandConfirm.name === '/memory'">会在本会话里输出持久记忆清单，不会改动任何记忆内容。</template>
          <template v-else-if="commandConfirm.name === '/loop'">会把该指令作为一轮请求发给模型。</template>
          <template v-else-if="commandConfirm.name === '/plan'">会切换计划模式：打开后先给方案，确认了再动手。</template>
          <template v-else>会打开对应页面，当前会话保持不动。</template>
        </p>
        <div class="risk-actions">
          <button class="btn ghost" @click="commandConfirm = null">取消</button>
          <button class="btn primary" @click="confirmCommand">确认执行</button>
        </div>
      </div>
    </div>

    <div class="field" :class="{ busy: session.isBusy }">
      <div v-if="session.lifeStatsOpen" class="life-stats-card">
        <header><span>生命状态</span><button aria-label="关闭" @click="session.lifeStatsOpen = false"><CoomiIcon name="close" :size="14" /></button></header>
        <div class="life-waveform" aria-label="数字生命动态状态波形">
          <svg viewBox="0 0 320 100" preserveAspectRatio="none" aria-hidden="true">
            <path class="wave wave-upper upper-back" d="M0 50C20 47 26 28 46 29C65 30 66 45 84 39C102 33 101 15 117 12C133 9 136 38 153 39C169 40 177 29 191 34C207 40 210 48 225 48C243 48 247 30 264 32C282 34 285 45 301 43C310 42 316 48 320 50V50H0Z" />
            <path class="wave wave-upper upper-main" d="M0 50C17 46 27 17 47 20C68 23 68 43 87 34C104 26 103 5 119 4C137 3 137 35 153 36C169 37 177 21 192 29C207 37 209 48 225 47C242 46 248 20 264 24C281 28 283 45 300 40C309 38 316 47 320 50V50H0Z" />
            <path class="wave wave-upper upper-front" d="M0 50C22 48 33 35 49 36C65 37 72 46 87 42C104 37 105 23 119 20C135 17 139 43 155 44C171 45 179 35 193 39C208 43 214 49 228 49C245 49 250 37 265 38C282 39 291 48 304 46C312 45 317 49 320 50V50H0Z" />
            <path class="wave wave-lower lower-back" d="M0 50C19 53 26 73 46 71C65 69 66 55 84 61C102 67 101 85 117 88C133 91 136 62 153 61C169 60 177 71 191 66C207 60 210 52 225 52C243 52 247 70 264 68C282 66 285 55 301 57C310 58 316 52 320 50V50H0Z" />
            <path class="wave wave-lower lower-main" d="M0 50C17 54 27 83 47 80C68 77 68 57 87 66C104 74 103 95 119 96C137 97 137 65 153 64C169 63 177 79 192 71C207 63 209 52 225 53C242 54 248 80 264 76C281 72 283 55 300 60C309 62 316 53 320 50V50H0Z" />
            <path class="wave wave-lower lower-front" d="M0 50C22 52 33 65 49 64C65 63 72 54 87 58C104 63 105 77 119 80C135 83 139 57 155 56C171 55 179 65 193 61C208 57 214 51 228 51C245 51 250 63 265 62C282 61 291 52 304 54C312 55 317 51 320 50V50H0Z" />
            <path class="wave-baseline" d="M0 50H320" />
          </svg>
        </div>
        <div class="life-stats-grid"><span>当前模式<strong>数字生命</strong></span><span>推理档位<strong>{{ REASONING_EFFORTS.find(i => i.value === config.reasoningEffort)?.label }}</strong></span><span>会话状态<strong>{{ session.isBusy ? '运行中' : '待命' }}</strong></span><span>动态流<strong>已连接</strong></span></div>
      </div>
      <div class="input-clip">
        <div v-if="importedFiles.length" class="attachments" aria-label="已导入文件">
          <span v-for="path in importedFiles" :key="path" class="attachment">
            <CoomiIcon name="fileRead" :size="15" />
            <span>{{ path.split('/').pop() || '文件' }}</span>
            <button type="button" aria-label="移除文件" @click="removeImportedFile(path)"><CoomiIcon name="close" :size="12" /></button>
          </span>
        </div>
        <textarea
          ref="textarea"
          v-model="text"
          class="input"
          :class="{ scrollable: textareaScrollable }"
          rows="1"
          :placeholder="session.isBusy ? '插队补充指令…' : '给 Comax 下达任务…'"
          @input="autoGrow"
          @keydown="onKeydown"
        />
      </div>

        <div class="bar">
          <!-- 三横杠：模式按钮竖向折叠，点击向上展开面板 -->
          <button
            ref="modeToggleEl"
            class="pill bar-toggle"
            :class="{ on: barOpen }"
            @click="toggleBar"
            aria-label="更多模式"
            :aria-expanded="barOpen"
          >
            <MorphIcon
              :icon="barIcon"
              :size="15"
              stroke-width="2"
              :spring="{ stiffness: 300, damping: 22 }"
              :reduced-motion="(config.sendMorphAnimation && !config.allAnimationsOff) ? 'never' : 'always'"
            />
            <span>模式</span>
          </button>

          <span class="spacer" />

          <button class="act" aria-label="快捷指令" @click="toggleQuick">
            <MorphIcon
              :icon="quickIcon"
              :size="21"
              stroke-width="2"
              :spring="{ stiffness: 320, damping: 24 }"
              :reduced-motion="(config.sendMorphAnimation && !config.allAnimationsOff) ? 'never' : 'always'"
            />
          </button>

          <button
            ref="sendButton"
            class="send"
            :class="{ jump: isJumpIn, stop: showStop }"
            :disabled="!canSend && !session.isBusy"
            :aria-label="showStop ? '停止' : isJumpIn ? '插队' : '发送'"
            @click="tapPrimary"
          >
            <CoomiIcon v-if="showStop" name="stop" :size="17" />
            <CoomiIcon v-else-if="isJumpIn" name="subtask" :size="18" />
            <CoomiIcon v-else name="arrowUp" :size="18" />
          </button>
        </div>
          <div
            v-if="panelMounted"
            ref="modePopEl"
            class="mode-pop"
            :class="{
              open: panelOpen || modeAnimOff,
              spread: panelSpread || modeAnimOff,
              dismissing: panelDismissing,
            }"
            role="group"
            aria-label="模式选项"
          >
            <button class="pill" :class="{ on: config.planMode }" @click="session.togglePlanMode()">
              <CoomiIcon name="target" :size="14" />
              <span>计划</span>
            </button>
            <button class="pill" :class="{ on: config.permissionMode === 'auto' || config.permissionMode === 'minimal', 'warn-on': config.permissionMode === 'full' }" @click="cycleMode()">
              <CoomiIcon name="shield" :size="14" />
              <span>{{ modeLabel }}</span>
            </button>
            <button class="pill production-pill" :class="{ on: config.productionMode !== 'normal', 'warn-on': config.productionMode === 'berserk' }" @click="toggleProductionMode()">
              <CoomiIcon name="target" :size="14" />
              <span>{{ config.productionMode === 'berserk' ? '狂暴' : config.productionMode === 'overload' ? '超载' : '普通' }}</span>
            </button>
            <button class="pill control-pill" :class="{ on: controlMode }" @click="toggleControlMode()">
              <CoomiIcon name="cursor" :size="14" />
              <span>控制</span>
            </button>
          </div>

    </div>

    <!-- 控制模式风险确认浮层 -->
    <div v-if="riskConfirm" class="risk-mask" @click.self="cancelControlMode">
      <div class="risk-dialog">
        <p class="risk-title">控制模式</p>
        <p class="risk-text">该功能风险极高，如出现问题软件作者不负责，且需要多模态模型与大量 token 消耗。确认后需要开启「无障碍」与「悬浮窗」权限：无障碍用于代替你点击、输入、发送，悬浮窗用于在你切换到别的 App 后继续显示执行进度（缩成小球，不挡屏幕）。</p>
        <div class="risk-actions">
          <button class="btn ghost" @click="cancelControlMode">取消</button>
          <button class="btn danger" @click="confirmControlMode">确认进入</button>
        </div>
      </div>
    </div>
    <!-- 控制模式浮窗：权限状态 + 指令输入 -->
    <div v-if="controlMode" class="control-float">
      <div class="control-bar">
        <span class="control-label">控制模式</span>
        <button class="exit-control" @click="exitControlMode">退出</button>
      </div>
      <div class="ctrl-perms">
        <button class="perm-chip" :class="{ ok: accessibilityReady }" @click="accessibilityReady ? undefined : requestAccessibility()">
          {{ accessibilityReady ? '✓ 无障碍已开启' : '开启无障碍' }}
        </button>
        <button class="perm-chip" :class="{ ok: overlayReady }" @click="overlayReady ? undefined : requestOverlay()">
          {{ overlayReady ? '✓ 悬浮窗已开启' : '开启悬浮窗' }}
        </button>
        <span v-if="floatRunning" class="perm-note">悬浮层已悬浮在桌面</span>
      </div>
      <textarea
        ref="controlInput"
        v-model="controlText"
        class="control-input"
        rows="2"
        placeholder="输入指令或回复内容…（会自动填入当前聊天框并发送）"
        @keydown.enter.prevent="sendControl"
      />
      <p v-if="controlThinking" class="control-thinking">{{ controlThinking }}</p>
      <button v-if="controlChatPage" class="control-chat-btn" type="button" @click="startControlReply">对话</button>
    </div>
    <p v-if="modeNotice" class="mode-notice">{{ modeNotice }}</p>
  </div>
</template>

<style scoped>
.composer { position: relative; padding: 6px 10px calc(var(--safe-bottom) + 8px); background: var(--bg); }
.control-float {
  position: fixed; left: 10px; right: 10px; bottom: calc(var(--safe-bottom) + 78px); z-index: 80;
  padding: 10px 12px; border: 1px solid var(--blue-border); border-radius: var(--r-card);
  background: var(--bg); box-shadow: var(--shadow-2);
  display: flex; flex-direction: column; gap: 8px;
  animation: control-pop .2s cubic-bezier(.2,.9,.3,1.15) both;
}
@keyframes control-pop { from { opacity: 0; transform: translateY(10px) scale(.96); } to { opacity: 1; transform: none; } }
.risk-mask { position: fixed; inset: 0; z-index: 90; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; padding: 28px; }
.risk-dialog { width: 100%; max-width: 320px; padding: 16px 15px; border-radius: var(--r-card); background: var(--bg); box-shadow: var(--shadow-2); display: flex; flex-direction: column; gap: 10px; }
.risk-title { margin: 0; font-size: 15px; font-weight: 750; color: var(--danger); }
.risk-text { margin: 0; font-size: 12.5px; line-height: 1.6; color: var(--text-2); }
.risk-actions { display: flex; justify-content: flex-end; gap: 8px; }
.risk-actions .btn { min-height: 36px; padding: 0 14px; border-radius: 8px; font-size: 13px; }
.risk-actions .btn.ghost { background: var(--fill); color: var(--text-2); }
.risk-actions .btn.danger { background: var(--danger); color: #fff; }
.risk-actions .btn.primary { background: var(--blue); color: #fff; }
.cmd-title { margin: 0; font-size: 15px; font-weight: 750; color: var(--text); }
.risk-text :first-child { margin-top: 0; }
.mode-notice {
  position: fixed; left: 50%; top: calc(var(--safe-top) + 10px); z-index: 95;
  transform: translateX(-50%); max-width: 86vw; padding: 8px 14px;
  border-radius: var(--r-pill); background: var(--orange-soft); color: var(--orange);
  font-size: 12.5px; font-weight: 650; text-align: center;
  box-shadow: var(--shadow-2); animation: control-pop .2s ease both;
}
.control-pill.on { background: var(--danger-soft); color: var(--danger); }
.control-bar { display: flex; align-items: center; gap: 8px; }
.control-bar .model-pick { margin-right: auto; }
.ctrl-perms { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.perm-chip {
  height: 30px; padding: 0 11px; border-radius: var(--r-pill);
  border: 1px solid var(--orange-border, var(--border));
  background: none; color: var(--orange, var(--text-2));
  font-size: 12.5px; font-weight: 600;
}
.perm-chip.ok { border-color: var(--border); color: var(--ok); background: var(--fill); }
.perm-note { font-size: 11.5px; color: var(--text-3); }
.control-label { font-size: 12.5px; color: var(--text-2); font-weight: 650; }
.exit-control { height: 30px; padding: 0 11px; border-radius: var(--r-pill); background: var(--fill); color: var(--text-2); font-size: 12.5px; }
.control-input {
  width: 100%; min-height: 46px; padding: 10px 12px; border: 1px solid var(--border);
  border-radius: 16px; background: var(--fill); color: var(--text);
  font: inherit; font-size: 15.5px; outline: none; resize: none;
}
.control-thinking { font-size: 12px; color: var(--blue); line-height: 1.5; }
.control-chat-btn {
  align-self: flex-start; height: 34px; padding: 0 16px; border-radius: var(--r-pill);
  background: var(--blue); color: #fff; font-size: 13px; font-weight: 700;
}
.edit-banner {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  margin: 0 2px 6px; padding: 6px 12px;
  border: 1px solid color-mix(in srgb, var(--blue) 40%, var(--border));
  border-radius: var(--r-pill);
  background: var(--blue-soft); color: var(--blue);
  font-size: 12px;
}
.edit-banner button { border: 0; background: none; color: var(--blue); font-weight: 650; }
.transfer { display: flex; align-items: center; gap: 8px; margin: 0 2px 6px; font-size: 11.5px; color: var(--text-2); }
.transfer span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.transfer progress { width: 76px; height: 4px; accent-color: var(--blue); }

.field {
  position: relative;
  padding: 4px 6px 6px 8px;
  border: 1px solid var(--border);
  border-radius: 26px;
  background: var(--fill);
  transition: border-color .16s;
}
.life-orbit {
  position: absolute; z-index: 2; top: -13px; left: 50%;
  width: 27px; height: 27px; margin-left: -13.5px;
  border-radius: 50%; background: var(--bg);
  box-shadow: 0 0 0 3px var(--bg), 0 0 13px color-mix(in srgb, var(--blue) 38%, transparent);
  border: 0; padding: 0; cursor: pointer;
}
.orbit { position: absolute; inset: 3px; border-radius: 50%; }
.orbit.outer {
  border: 2px solid transparent; border-top-color: var(--blue); border-right-color: var(--blue);
  filter: drop-shadow(0 0 3px color-mix(in srgb, var(--blue) 70%, transparent));
  animation: life-spin 1.8s linear infinite;
}
.orbit.inner {
  inset: 7px; border: 2px solid transparent; border-bottom-color: var(--orange); border-left-color: var(--orange);
  filter: drop-shadow(0 0 2px color-mix(in srgb, var(--orange) 65%, transparent));
  animation: life-spin-reverse 1.15s linear infinite;
}
@keyframes life-spin { to { transform: rotate(360deg); } }
@keyframes life-spin-reverse { to { transform: rotate(-360deg); } }
@media (prefers-reduced-motion: reduce) {
  .orbit.outer, .orbit.inner { animation-duration: 6s; }
}
.field:focus-within { border-color: var(--blue-border); background: var(--bg); }
.field.busy { border-color: var(--border-strong); }

.input-clip { overflow: hidden; border-radius: 18px 18px 8px 8px; }
.attachments { display:flex; flex-wrap:wrap; gap:6px; padding:7px 6px 2px; }
.attachment { display:inline-flex; align-items:center; gap:5px; max-width:100%; height:30px; padding:0 7px 0 9px; border:1px solid var(--blue-border); border-radius:10px; background:var(--blue-soft); color:var(--blue); font-size:12px; }
.attachment > span { min-width:0; max-width:190px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.attachment button { display:grid; place-items:center; width:20px; height:20px; padding:0; border:0; border-radius:50%; background:transparent; color:inherit; }
.attachment button:active { background:color-mix(in srgb,var(--blue) 12%,transparent); }
.input {
  display: block; width: 100%; max-height: 132px; overflow-y: hidden;
  padding: 9px 10px 5px 6px; border: 0; background: none; outline: none; resize: none;
  font: inherit; font-size: 15.5px; line-height: 1.5; color: var(--text);
  scrollbar-width: thin; scrollbar-color: var(--border-strong) transparent;
}
.input.scrollable { overflow-y: auto; }
.input::placeholder { color: var(--text-3); }
.input:not(.scrollable)::-webkit-scrollbar { display: none; width: 0; }
.input.scrollable::-webkit-scrollbar { width: 3px; }
.input.scrollable::-webkit-scrollbar-track { margin-block: 12px 7px; background: transparent; }
.input.scrollable::-webkit-scrollbar-thumb { border-radius: 3px; background: var(--border-strong); }

.bar {
  display: flex; align-items: center; gap: 6px; padding: 2px 0 0 2px;
  /* 小屏/大字体机型：一行放不下就换行，而不是横向溢出屏幕。 */
  flex-wrap: wrap; min-width: 0;
}
.spacer { flex: 1 1 auto; min-width: 4px; }
/* ── 模式面板：从「模式」按键分裂 → 摊开（1.5.2o8beta） ──
   只动 transform / opacity / filter 这三个合成器能处理的属性；
   刻意不碰 width/height/left 这类会触发重排的属性，否则在 WebView 上会掉帧。

   三个几何变量由 layoutStack() 现算，别在这里写死：
     --origin-x / --origin-y  卡摞中心（容器局部坐标），缩放原点
     --from-x / --from-y      起点位移：把卡摞中心搬到「模式」按键中心
     --from-scale             起点缩放：让卡摞正好盖住那颗按键
   起点 = 按键本身，所以观感是"按键裂成四张卡"，而不是"一条横带从底下升上来"。 */

/* 容器基础态 = **展开的起点**：沉在输入框下方一点，缩小、模糊、不可点
   （不可点很重要，否则会误触看不见的按钮）。 */
.mode-pop {
  /*
   * 动画锚点：容器覆盖整张输入区（相对 .composer），但**展开起点**收缩成
   * 「模式」按键自己的位置与尺寸（左上角的 origin + 缩小到按钮大小）。
   *
   * 1.5.2o8beta 的动画是「从『模式』按键分裂出来」：整摞卡先从按键处升起、放大，
   * 到位后才从左到右摊开。起点不再是一条从输入框底部升上来的横带，而是那颗
   * 按键本身 —— 视觉上像按键裂开变成了四张卡。
   */
  position: absolute; z-index: 5; left: 0; right: 0; bottom: calc(100% + 8px);
  display: flex; flex-direction: row; align-items: center; justify-content: flex-start;
  flex-wrap: wrap; gap: 6px;
  background: transparent; border: 0; box-shadow: none; padding: 0;
  opacity: 0;
  transform-origin: var(--origin-x, 16px) var(--origin-y, 16px);
  transform: translate(var(--from-x, 0px), var(--from-y, 12px)) scale(var(--from-scale, .2));
  filter: blur(6px);
  pointer-events: none;
  transition:
    opacity .26s cubic-bezier(.22,.68,.19,1),
    transform .34s cubic-bezier(.22,.68,.19,1),
    filter .26s cubic-bezier(.22,.68,.19,1);
  will-change: transform, opacity;
}

/* 阶段一：从按键处放大到位、聚焦。此时卡片仍叠在锚点上（见下面的 --sx/--sy）。 */
.mode-pop.open {
  opacity: 1;
  transform: none;
  filter: blur(0px);
  pointer-events: auto;
  /* 到位后立刻可以点：卡片是摞着的，用户可能想直接点最上面那张 */
}

/* 关闭动画 / 减弱动效：不做分裂，直接显形 */
@media (prefers-reduced-motion: reduce) {
  .mode-pop { transform: none; filter: none; transition: none; }
}
[data-all-animations-off] .mode-pop {
  transform: none; filter: none; transition: none;
}

/*
 * 收回：**同时**向右滑出屏幕。
 *
 * 终点与展开的起点不同（展开是"从下面浮上来"，收回是"从右边滑出去"），
 * 所以必须用独立状态，不能靠"回到非 .open"来实现 —— 那样收回会变成向下沉。
 *
 * opacity 用一点延迟再淡出：滑出的过程要让用户看见卡片正在从左往右折叠，
 * 一上来就透明掉等于看不到折叠。
 */
.mode-pop.open.dismissing {
  transform: translateX(118%);
  opacity: 0;
  transition:
    transform .34s cubic-bezier(.22,.68,.19,1),
    opacity .16s ease .26s,
    filter .34s cubic-bezier(.22,.68,.19,1);
  pointer-events: none;
}

/*
 * 单张卡：
 *   · 基础态 = 叠在锚点上的一摞（位移到 --sx/--sy，缩小、透明）
 *   · .open   = 显形，但**位置不动**（还是摞着）—— 这就是"堆叠状出来"
 *   · .spread = 位移归零、放大回原尺寸，按 --spread-delay 从左到右摊开
 *   · .dismissing 时回到基础态，按 --fold-delay **从左往右**逐张折回
 *
 * 之所以把"显形"和"摊开"分成两个类：上一版把两者挤进同一个 transition-delay，
 * 结果 0.4s 的停顿也被算进了显形，卡片在浮出阶段根本看不见。
 */
.mode-pop .pill {
  min-height: 0; width: auto;
  transform: translate(var(--sx, 0px), var(--sy, 0px));
  opacity: 0;
  /* transition 是"覆盖"而不是"追加"，所以这里必须把 .pill 原本的
     background/color 过渡一起带上，否则选中态换色会变成瞬变。 */
  transition:
    opacity .2s ease var(--fold-delay, 0s),
    transform .26s cubic-bezier(.22,.68,.19,1) var(--fold-delay, 0s),
    background .15s, color .15s;
}
/* 阶段一：只显形，不移动 */
.mode-pop.open .pill {
  opacity: 1;
  transition-delay: 0s, var(--fold-delay, 0s), 0s, 0s;
}
/* 阶段三：摊开 */
.mode-pop.spread .pill {
  transform: none;
  opacity: 1;
  transition-delay: var(--spread-delay, 0s), var(--spread-delay, 0s), 0s, 0s;
}

/* 系统减弱动效时不做堆叠位移，避免晕眩 */
@media (prefers-reduced-motion: reduce) {
  .mode-pop, .mode-pop .pill { transition: none; }
  .mode-pop .pill { transform: none; }
}

.bar-toggle { flex-shrink: 0; }
.bar-toggle.on { color: var(--blue); background: var(--blue-soft); }
.production-pill.on { color: #ff3b30; background: rgba(255, 59, 48, 0.12); }

.act {
  /* 用自然尺寸而不是固定像素：系统开启大字模式时，图标与按钮随之缩放，不会错位。 */
  display: grid; place-items: center;
  min-width: 34px; min-height: 34px;
  padding: 6px;
  border: 0; border-radius: 50%; background: none; color: var(--text-2);
}
.act:active { background: var(--fill-press); }

.send {
  display: grid; place-items: center; flex-shrink: 0;
  /* aspect-ratio 1 + min 尺寸：正常字号下仍是正圆，大字体下整体放大不破形。 */
  min-width: 36px; min-height: 36px; aspect-ratio: 1; padding: 6px;
  border: 0; border-radius: 50%;
  background: var(--blue); color: #fff;
  transition: background .16s, transform .06s;
}
.send.jump { background: var(--orange); }
.send.stop { background: var(--text); }
.send:disabled { background: var(--border-strong); pointer-events: none; }
.send:active { transform: scale(.92); }

/* 指令面板浮层：可滚动卡片 */
.quick-scrim { position: fixed; inset: 0; z-index: 1; }
.quick {
  position: absolute; z-index: 2; left: 10px; right: 10px; bottom: calc(100% + 4px);
  max-height: min(56vh, 360px); overflow-y: auto;
  padding: 10px 12px 12px;
  border: 1px solid var(--border); border-radius: var(--r-card);
  background: var(--bg); box-shadow: var(--shadow-2);
  animation: coomi-cascade .18s ease both;
}
.reasoning-head { margin-top: 3px; }
.reasoning-options { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:4px; margin-bottom:8px; }
.reasoning-options button { position:relative; min-width:0; height:32px; overflow:visible; isolation:isolate; border-radius:6px; background:var(--fill); color:var(--text-2); font-size:12px; }
.reasoning-options button::after { content:''; position:absolute; z-index:-1; inset:-3px; border-radius:9px; opacity:0; background:radial-gradient(circle, color-mix(in srgb,var(--blue) 34%,transparent), transparent 68%); pointer-events:none; }
.reasoning-options button.selected { background:var(--blue-soft); color:var(--blue); font-weight:650; }
.reasoning-options button.selected::after { opacity:1; animation:reasoning-ripple 1.8s ease-out infinite; }
.reasoning-options button:nth-child(2).selected::after { inset:-5px; }
.reasoning-options button:nth-child(3).selected::after { inset:-8px; }
.reasoning-options button:nth-child(4).selected::after, .reasoning-options button:nth-child(5).selected::after { inset:-11px; }
.life-stats-card { position:absolute; z-index:4; left:12px; right:12px; bottom:calc(100% + 10px); overflow:hidden; padding:11px 12px 12px; border:1px solid color-mix(in srgb,var(--blue) 35%,var(--border)); border-radius:13px; background:color-mix(in srgb,var(--bg) 94%,var(--blue-soft)); box-shadow:0 8px 28px color-mix(in srgb,var(--blue) 20%,transparent); animation:life-card-in .2s ease both; }
.life-stats-card header { display:flex; align-items:center; justify-content:space-between; color:var(--text); font-size:12px; font-weight:650; }
.life-stats-card header button { display:grid; place-items:center; width:24px; height:24px; border-radius:50%; background:var(--fill); color:var(--text-2); }
.life-waveform { position:relative; height:64px; margin:9px 1px 10px; overflow:hidden; border-radius:7px; background:linear-gradient(to bottom, color-mix(in srgb,var(--blue-soft) 28%,transparent), transparent 50%, color-mix(in srgb,var(--blue-soft) 20%,transparent)); }
.life-waveform svg { display:block; width:100%; height:100%; overflow:visible; }
.wave { transform-origin:160px 50px; vector-effect:non-scaling-stroke; animation:life-wave-breathe 3.4s ease-in-out infinite alternate; }
.wave-upper { fill:color-mix(in srgb,var(--orange) 48%,var(--bg)); stroke:color-mix(in srgb,var(--orange) 72%,var(--bg)); stroke-width:1.2; }
.wave-lower { fill:color-mix(in srgb,var(--blue) 52%,var(--bg)); stroke:color-mix(in srgb,var(--blue) 76%,var(--bg)); stroke-width:1.2; }
.upper-back,.lower-back { opacity:.55; animation-duration:4.5s; animation-delay:-1.1s; }
.upper-front,.lower-front { opacity:.7; animation-duration:2.8s; animation-delay:-.55s; }
.wave-baseline { fill:none; stroke:var(--border-strong); stroke-width:1.4; vector-effect:non-scaling-stroke; opacity:.9; }
.life-stats-grid { display:grid; grid-template-columns:repeat(2,1fr); gap:7px 12px; }.life-stats-grid span { display:flex; justify-content:space-between; gap:8px; color:var(--text-3); font-size:11px; }.life-stats-grid strong { color:var(--text-2); font-weight:600; }
@keyframes life-card-in { from { opacity:0; transform:translateY(5px) scale(.98); } to { opacity:1; transform:none; } }
@keyframes reasoning-ripple { 0%,100% { transform:scale(.92); opacity:.35; } 50% { transform:scale(1.12); opacity:.9; } }
@keyframes life-wave-breathe { 0% { transform:translateX(-1.5px) scaleY(.9); opacity:.72; } 50% { transform:translateX(0) scaleY(1.04); opacity:1; } 100% { transform:translateX(1.5px) scaleY(.94); opacity:.8; } }
@media (prefers-reduced-motion: reduce) { .wave { animation-duration:8s; } }
  .local-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px; margin-bottom: 8px; }
  .local-actions .qchip { justify-content: flex-start; }
.qhead { margin-bottom: 8px; font-size: 12px; font-weight: 600; color: var(--text-3); }
.file-actions { display: flex; gap: 7px; margin-bottom: 8px; }
.qchip.file { display: inline-flex; align-items: center; gap: 5px; color: var(--blue); }
.qchip {
  height: 32px; padding: 0 13px;
  border: 1px solid var(--border); border-radius: var(--r-pill);
  background: var(--bg); font-size: 13.5px; color: var(--text-2);
}
.qchip:active { background: var(--blue-soft); border-color: var(--blue-border); color: var(--blue); }

/* 斜杠指令逐行列表 */
.slash-list { display: flex; flex-direction: column; gap: 2px; }
.slash-item {
  display: flex; align-items: center; gap: 10px; width: 100%;
  padding: 10px 8px; border: 0; border-radius: 10px;
  background: none; text-align: left; cursor: pointer;
}
.slash-item code { font-family: inherit; font-size: 13.5px; font-weight: 700; color: var(--blue); }
.slash-item span { font-size: 12.5px; color: var(--text-2); }
.slash-item:active { background: var(--blue-soft); }
.banner-enter-active, .banner-leave-active { transition: opacity .22s ease, transform .22s ease; }
.banner-enter-from { opacity: 0; transform: translateY(-6px); }
.banner-leave-to { opacity: 0; transform: translateY(-4px); }
</style>
