<script setup lang="ts">
/**
 * 主聊天窗口。
 *
 * 三件事和别处不一样：
 * 1) 抽屉打开时整个 shell 往右推 + 轻微缩放，是 DeepSeek 的那种层次感；
 * 2) 跟随滚动交给 useAutoScroll，高度变化用 ResizeObserver 兜住 ——
 *    markdown 重排、工具卡展开、软键盘弹出都会改高度，只 watch 数组长度会漏；
 * 3) 连续的工具调用合并成一个 ToolGroup，避免长任务把时间线冲成一堵卡片墙。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'
import { useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { useSessionsStore } from '@/stores/sessions'
import { useConfigStore } from '@/stores/config'
import { apiGet } from '@/bridge/http'
import { DEMO_PROMPT, isUnattended, shouldAutoplay } from '@/bridge/demoMode'
import { useAutoScroll } from '@/composables/useAutoScroll'
import type { ToolCard } from '@/stores/viewModel'
import { buildTimelineBlocks, type TimelineBlockItem } from '@/utils/chatTimeline'
import type { ApprovalDecision } from '@/protocol/commands'
import TopBar from '@/components/TopBar.vue'
import SideDrawer from '@/components/SideDrawer.vue'
import ArtifactMindMap from '@/components/ArtifactMindMap.vue'
import StatusBar from '@/components/StatusBar.vue'
import Composer from '@/components/Composer.vue'
import EmptyState from '@/components/EmptyState.vue'
import TimelineBlock from '@/components/TimelineBlock.vue'
import LoopProgressBar from '@/components/LoopProgressBar.vue'
import ApprovalSheet from '@/components/ApprovalSheet.vue'
import QuestionSheet from '@/components/QuestionSheet.vue'
import CoomiIcon from '@/components/CoomiIcon.vue'
import SendMorphOverlay from '@/components/SendMorphOverlay.vue'
import PluginSlot from '@/components/PluginSlot.vue'
import { registerOverlay, unregisterOverlay } from '@/bridge/overlayStack'

const router = useRouter()
const session = useSessionStore()
const sessions = useSessionsStore()
const config = useConfigStore()

const scroller = ref<HTMLElement | null>(null)
const virtualScroller = ref<InstanceType<typeof DynamicScroller> | null>(null)
const drawerOpen = ref(false)
/** 产物思维导图抽屉：从右边缘左滑打开，与左侧会话抽屉互斥。 */
const mindMapOpen = ref(false)
let mindMapTouchStartX = 0
let mindMapTouchStartY = 0
let mindMapTouching = false
/** 全局轮询「后台运行中」状态的定时器（会话列表转圈的数据源）。 */
let runningPoll: ReturnType<typeof setInterval> | null = null

function settleVirtualBottom() {
  const scrollerApi = virtualScroller.value as unknown as { scrollToBottom?: () => void } | null
  scrollerApi?.scrollToBottom?.()
}
const { following, follow, jumpToBottom } = useAutoScroll(scroller, settleVirtualBottom)

const blocks = computed<TimelineBlockItem[]>(() => buildTimelineBlocks(session.timeline))

/**
 * ──────────────────────────────────────────────────────────────
 * 1.5.4o5 第 7 项：长消息把历史顶掉 / 内容被复制一份卡在最上方
 * ──────────────────────────────────────────────────────────────
 * 症状表现：输出到一定程度后，忽然整段旧内容不见了，同时最上方多出一半被截断的
 * 内容反弹回顶部。
 *
 * 根因在虚拟列表：vue-virtual-scroller 的 DynamicScroller 只在**条目尺寸变化时**
 * 重新计算顶部偏移。流式输出时最后一条消息每帧都在长高、而它的高度又要靠
 * 元素自己 emit resize 才知道 —— 中间任何一帧测量没接上，`sizes` 就落后于真实
 * 高度，随后整块的 offset 被算错，表现为「历史被顶掉、内容错位复制」。
 *
 * 对策不是"参数调大调小"，而是**不做流式虚拟化**：
 *   · 对话中（`isBusy`）整条时间线铺在一个普通滚动容器（`own-stream`）里，
 *     没有虚拟列表，条目高度随便长都不会算错偏移；
 *   · 空闲时看历史回到虚拟列表，长会话依旧只渲染屏幕内的条目；
 *   · 两种模式**互斥渲染**，任何时刻只有一份 DOM 承载内容 —— 这就从根上消除了
 *     「同一段内容被复制一份卡在最上方、旧内容被顶掉」。
 * 切换只在「开始 / 结束一轮」时发生，中间不会来回跳。
 */
/**
 * 独占模式的"当前滚动视口"：始终指向 nodes[index]。
 * 用节点下标而不是元素引用，因为 DOM 可能在这一帧之后才挂上（v-if / 模式切换）。
 */
const ownScroller = ref<HTMLElement | null>(null)
/** 独占层贴底的 rAF 句柄（一帧最多写一次 scrollTop）。 */
let ownRaf = 0
/** 与虚拟列表同一套阈值：离底 12px 脱离，回到底部 8px 内恢复。 */
const OWN_DETACH_PX = 12
const OWN_REATTACH_PX = 8
/** 独占层是否贴底跟随（ref：模板按钮的显示条件依赖它）。 */
const ownFollowing = ref(true)
let ownLastTop = 0
let ownTouching = false

function onOwnScroll() {
  const list = ownScroller.value
  if (!list) return
  const top = list.scrollTop
  const movedUp = top < ownLastTop - 4
  ownLastTop = top
  const distance = list.scrollHeight - top - list.clientHeight
  if (ownFollowing.value && distance > OWN_DETACH_PX && (movedUp || ownTouching)) ownFollowing.value = false
  else if (!ownFollowing.value && distance < OWN_REATTACH_PX) ownFollowing.value = true
}

function onOwnTouchStart() { ownTouching = true }
function onOwnTouchEnd() {
  // 手指离开后还有惯性：晚一点再解除，避免惯性途中被当成"程序滚动"。
  setTimeout(() => { ownTouching = false }, 120)
}

/** 独占层内容变化后贴底：合并到一帧、只在跟随态生效。 */
function followOwn() {
  if (!ownFollowing.value || ownTouching) return
  if (ownRaf) return
  ownRaf = requestAnimationFrame(() => {
    ownRaf = 0
    const list = ownScroller.value
    if (!list || !ownFollowing.value || ownTouching) return
    list.scrollTop = list.scrollHeight
    ownLastTop = list.scrollTop
  })
}

/** 把 nodes 与 blocks 对齐（独占模式下的渲染源）。 */
const nodes = ref<TimelineBlockItem[]>([])

/**
 * 1.5.4o5 第 7 项 · 主模式切换。
 *
 * `busy`（对话中）用独占滚动层，`idle`（看历史）用虚拟列表。
 * 两种模式互斥渲染，任何时刻只有一份 DOM 承载内容 —— 这从根上消除了
 * 「复制一份内容卡在最上方、历史被顶掉」。
 *
 * 何时切：开始输出后 90ms 再切（让刚发出去的消息先落位，避免容器闪一次）；
 * 结束后 260ms 再切回虚拟列表（等最后一段高度稳定）。
 */
/**
 * 初始模式：默认 'own'。
 *
 * 用 'own' 起步是刻意的 —— 进场时要恢复历史（`session.restoring`），
 * 这时候虚拟列表还在逐条量高度，正好是最容易算错偏移的时刻。
 * 等恢复完、且确实不忙，再降级到虚拟列表；如果进来就在跑任务，就继续用 own。
 */
const renderMode = ref<'virtual' | 'own'>('own')
watch(() => session.isBusy, busy => {
  if (busy) {
    window.setTimeout(() => {
      ownFollowing.value = true
      nodes.value = blocks.value.slice()
      renderMode.value = 'own'
      nextTick(() => { followOwn() })
    }, 90)
  } else {
    window.setTimeout(() => {
      // 恢复历史期间不切：那时候虚拟列表还在量高度，正好是"上下闪"的来源。
      // 等 restoring 结束由下面的 watch 接手。
      if (session.restoring) return
      renderMode.value = 'virtual'
      nextTick(() => { follow(); jumpToBottom() })
    }, 260)
  }
}, { immediate: true })

// 进场恢复完成后，如果不在跑任务就切回虚拟列表（长会话省内存）。
watch(() => session.restoring, busy => {
  if (busy) return
  if (session.isBusy) return
  window.setTimeout(() => {
    if (session.isBusy) return
    renderMode.value = 'virtual'
    nextTick(() => { follow(); jumpToBottom() })
  }, 320)
})

// 独占模式下内容每变一次就贴底；虚拟模式交给原来的 follow()。
// immediate：首帧就要有内容 —— 否则 own 模式下 nodes 还是空的，
// 模板里 own-stream 与虚拟列表都不会渲染，出现一帧白屏。
watch(blocks, () => {
  if (renderMode.value === 'own') {
    nodes.value = blocks.value.slice()
    followOwn()
  }
}, { immediate: true })

/**
 * 「回到底部」按钮的显示条件：虚拟模式看 useAutoScroll 的 following，
 * 独占模式看自己那套 ownFollowing（两条滚动链路互相独立，不能混用）。
 */
const showToBottom = computed(() => (
  renderMode.value === 'own' ? !ownFollowing.value : !following.value
))

/** 两种渲染模式下的「回到底部」，所有调用点都走这里。 */
function toBottom() {
  if (renderMode.value === 'own') {
    const list = ownScroller.value
    if (!list) return
    ownFollowing.value = true
    list.scrollTop = list.scrollHeight
    ownLastTop = list.scrollTop
    return
  }
  jumpToBottom()
}

function syncDigitalLifeMode() {
  config.syncDigitalLifeEnabled()
  if (!session.isBusy) session.syncLifeMode()
}

/** 页面重新可见：隐藏期间事件只在引擎侧累计，回来时一次性以权威快照恢复。 */
function onVisibilityChange() {
  if (document.hidden) return
  void session.resumeFromBackground().then(() => {
    session.connect()
  })
}

onMounted(() => {
  session.connect()
  window.addEventListener('coomi:flush-persistence', session.flushPersistence)
  // 记录引擎当前工作目录，会话列表据此把不同项目的会话隔离开。
  void apiGet<{ cwd?: string }>('/api/runtime/health')
    .then(h => { if (h?.cwd) sessions.setCurrentCwd(h.cwd) })
    .catch(() => { /* 引擎未就绪时保持空 cwd，列表退化为全部显示 */ })
  // 以引擎磁盘会话为权威源同步列表，修复“会话记录消失/串会话”。
  void sessions.syncFromEngine()
  if (config.providers.length === 0) void config.fetchProviders()
  // 全局记忆开关以引擎为权威：启动即同步，避免「开关显示关、引擎实际开」的脱节。
  void config.syncGlobalMemoryFromEngine()
  syncDigitalLifeMode()
  window.addEventListener('focus', syncDigitalLifeMode)
  // 后台返回时一次恢复权威快照：页面隐藏期间事件只 ACK 未渲染，
  // 避免返回后逐字蹦出；这里拉完整会话一次铺好。
  document.addEventListener('visibilitychange', onVisibilityChange)
  // 全局轮询各会话的「后台运行中」状态：切走会话后任务在引擎侧继续跑，
  // 抽屉/会话页据此显示转圈。轮询常驻（本地 API 开销极小），不依赖抽屉打开。
  void sessions.refreshRunning()
  runningPoll = setInterval(() => { sessions.refreshRunning(); void session.refreshLifeUnread(); session.autoDeliverLifeIfReady() }, 2000)
  void session.refreshLifeUnread()
  // DynamicScroller 自己作为唯一滚动容器，避免父级与虚拟列表同时写入位置。
  nextTick(() => {
    const element = (virtualScroller.value as any)?.$el
    scroller.value = element instanceof HTMLElement ? element : null
    follow()
    void session.restoreFromEngine(session.sessionId)
  })
  // 演示模式自动播一轮，省得进来还要先打字才能看见瀑布流。
  if (shouldAutoplay() && session.timeline.length === 0) {
    setTimeout(() => { if (session.timeline.length === 0) session.sendMessage(DEMO_PROMPT) }, 700)
  }
  window.addEventListener('touchstart', onMindMapTouchStart, { passive: true })
  window.addEventListener('touchmove', onMindMapTouchMove, { passive: true })
  window.addEventListener('touchend', onMindMapTouchEnd)
})

onBeforeUnmount(() => {
  window.removeEventListener('coomi:flush-persistence', session.flushPersistence)
  window.removeEventListener('focus', syncDigitalLifeMode)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  session.flushPersistence()
  window.removeEventListener('touchstart', onMindMapTouchStart)
  window.removeEventListener('touchmove', onMindMapTouchMove)
  window.removeEventListener('touchend', onMindMapTouchEnd)
  if (runningPoll) { clearInterval(runningPoll); runningPoll = null }
})

/**
 * 无人值守演示（?demo=1&auto=1）：授权弹层和提问弹层过一会儿自己点掉。
 * 走的是 approve / answerQuestion —— 和真手指按下去完全同一条路，
 * 所以卡片状态、「已回答」气泡都跟着变。截图、录屏、摆着自演都靠它。
 */
if (isUnattended()) {
  const AUTOPILOT_DELAY = 1600
  watch(() => session.pendingApproval?.callId, id => {
    if (!id) return
    setTimeout(() => { if (session.pendingApproval?.callId === id) session.approve(id, 'allow') }, AUTOPILOT_DELAY)
  })
  watch(() => session.pendingQuestion?.callId, id => {
    if (!id) return
    setTimeout(() => {
      const q = session.pendingQuestion
      if (q?.callId === id) {
        session.answerQuestion(id, Object.fromEntries(q.questions.map(question => [question.id, question.options[0]?.label ?? ''])))
      }
    }, AUTOPILOT_DELAY)
  })
}

// ResizeObserver 不可用时的兜底：至少条目增减能跟上。
watch(() => session.timeline.length, () => nextTick(follow))
// 空态与虚拟列表互斥渲染，滚动容器会在挂载/卸载间切换：列表一上屏就接管滚动。
watch(virtualScroller, value => {
  const element = (value as { $el?: unknown } | null)?.$el
  scroller.value = element instanceof HTMLElement ? element : null
  nextTick(follow)
})
// 完整历史恢复 / 切换会话后必须按新列表的真实高度重新置底；仅监听长度不够，
// 因为两个会话完全可能恰好拥有相同数量的时间线块。
watch(() => session.viewRevision, () => nextTick(() => {
  following.value = true
  // 独占模式（正在输出）切会话：直接对齐独占层，别去动虚拟列表。
  if (renderMode.value === 'own') {
    nodes.value = blocks.value.slice()
    nextTick(() => {
      ownFollowing.value = true
      toBottom()
    })
    return
  }
  settleVirtualBottom()
  jumpToBottom()
}))
/**
 * 进入会话时的「上下闪一下」。
 *
 * 原因：时间线先用本机 localStorage 的记录渲染（高度 A、滚动置底一次），随后引擎
 * 权威快照到达、整条时间线被替换（高度 B、再置底一次）。这两次置底之间用户看到
 * 内容先是 A 的底部、再跳到 B 的底部 —— 就是"上下闪"。
 *
 * 对策：恢复期间把列表 `visibility: hidden`（保留布局与测量，滚动照常算到位），
 * 等置底完成、再等两帧让虚拟列表把行高量准，才把内容显形。整个过程只有一次置底。
 * 如果引擎超时没响应（2.5s），强制显形，绝不让用户对着空白页。
 */
const revealReady = ref(false)
const REVEAL_FALLBACK_MS = 2500
let revealTimer: ReturnType<typeof setTimeout> | null = null
function revealNow() {
  if (revealTimer) { clearTimeout(revealTimer); revealTimer = null }
  nextTick(() => {
    settleVirtualBottom()
    toBottom()
    // 两帧后再确认一次：如果这期间引擎快照刚好开始替换时间线，就继续保持隐藏，
    // 否则会先把本机记录亮出来一瞬、再换引擎历史 —— 又是"闪一下"。
    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (!session.restoring) revealReady.value = true
    }))
  })
}
watch(() => session.restoring, busy => {
  if (busy) {
    revealReady.value = false
    if (revealTimer) clearTimeout(revealTimer)
    revealTimer = setTimeout(() => { revealTimer = null; revealNow() }, REVEAL_FALLBACK_MS)
    return
  }
  revealNow()
}, { immediate: true })
onBeforeUnmount(() => { if (revealTimer) { clearTimeout(revealTimer); revealTimer = null } })

// 流式输出时气泡高度持续变化，但 DynamicScroller 内部容器不是 ResizeObserver 观察对象：
// usage_update（输出 token 增长时持续推送）是可靠的「内容在变」信号，据此贴底。
watch(() => session.usage?.output, () => follow(), { deep: false })
watch(() => session.usage?.turnOutputTokens, () => follow(), { deep: false })
/**
 * 一轮结束：只 follow，不 jump。
 *
 * 旧实现是在收到 turn_end 时递增 viewRevision，而 viewRevision 的 watch 会
 * `following = true` + `jumpToBottom()` —— 用户正往上翻历史时被硬拽回底部
 * （1.5.4o4 第 3 项）。这里改成：只在「本来就跟随」的前提下补一次贴底，
 * 用户已经脱离就绝不打扰；同时也避免跳转发生在内容仍在增长的瞬间。
 */
watch(() => session.runState, (state, previous) => {
  if (state === 'idle' && previous !== 'idle') {
    nextTick(() => { follow() })
  }
})
watch([() => config.digitalLifeEnabled, () => config.lifeGlobalMode, () => session.isBusy], ([, , busy]) => {
  if (!busy) session.syncLifeMode()
})

function onDecide(callId: string, decision: ApprovalDecision) { session.approve(callId, decision) }
function onAnswer(callId: string, answers: Record<string, string>) { session.answerQuestion(callId, answers) }
function openDrawer() { drawerOpen.value = true; registerOverlay('side-drawer', closeDrawer) }
/** 菜单键做成切换：不管层级怎么变，"再点一次关掉"都必须成立。 */
function toggleDrawer() { if (drawerOpen.value) closeDrawer(); else openDrawer() }
function closeDrawer() { drawerOpen.value = false; unregisterOverlay('side-drawer') }
function openMindMap() { mindMapOpen.value = true; registerOverlay('artifact-mindmap', closeMindMap) }
function closeMindMap() { mindMapOpen.value = false; unregisterOverlay('artifact-mindmap') }

/** 从右边缘左滑打开产物思维导图 */
function onMindMapTouchStart(e: TouchEvent) {
  if (e.touches.length !== 1 || mindMapOpen.value) return
  mindMapTouchStartX = e.touches[0].clientX
  mindMapTouchStartY = e.touches[0].clientY
  mindMapTouching = true
}
function onMindMapTouchMove(e: TouchEvent) {
  if (!mindMapTouching) return
  const dx = e.touches[0].clientX - mindMapTouchStartX
  const dy = e.touches[0].clientY - mindMapTouchStartY
  if (Math.abs(dx) < Math.abs(dy)) return
  if (dx < -60 && mindMapTouchStartX > window.innerWidth - 40) {
    openMindMap()
    mindMapTouching = false
  }
}
function onMindMapTouchEnd() { mindMapTouching = false }

watch(() => session.pendingApproval?.callId, (id, previous) => {
  if (previous) unregisterOverlay(`approval:${previous}`)
  if (id) registerOverlay(`approval:${id}`, () => session.approve(id, 'deny'))
})
watch(() => session.pendingQuestion?.callId, (id, previous) => {
  if (previous) unregisterOverlay(`question:${previous}`)
  if (id) registerOverlay(`question:${id}`, () => session.answerQuestion(id, {}))
})
</script>

<template>
  <div class="chat" :class="{ 'minimal-ui': config.minimalUi, pushed: drawerOpen }">
    <div class="shell" :class="{ pushed: drawerOpen }">
      <TopBar :menu-open="drawerOpen" @menu="toggleDrawer" />
      <!-- 插件浮层：绝对定位盖在会话区上方，pointer-events 由插件自己决定 -->
      <div class="plugin-overlay-layer"><PluginSlot name="chat-overlay" /></div>

      <!--
        两种渲染模式互斥，任何时刻只有一份 DOM 承载内容（1.5.4o5 第 7 项）：
          · own     —— 对话中：全部时间线铺在一个普通滚动容器里，没有虚拟化，
                        长度增长不会让虚拟列表算错偏移（历史被顶掉 / 内容被复制一份）。
          · virtual —— 空闲时看历史：仍用虚拟列表，长会话只渲染屏幕内的条目。
        切换只在「开始/结束一轮」时发生，中间不会来回跳。
      -->
      <div
        v-if="renderMode === 'own' && nodes.length > 0"
        ref="ownScroller"
        class="stream own-stream"
        :class="{ 'reveal-pending': !revealReady }"
        @scroll.passive="onOwnScroll"
        @touchstart.passive="onOwnTouchStart"
        @touchend.passive="onOwnTouchEnd"
      >
        <div class="inner">
          <TimelineBlock
            v-for="(node, index) in nodes"
            :key="node.key"
            :block="node"
            :live="index === nodes.length - 1 && session.isBusy"
          />
        </div>
      </div>

      <DynamicScroller
        v-else-if="blocks.length > 0"
        ref="virtualScroller"
        :items="blocks"
        key-field="key"
        :min-item-size="48"
        :buffer="360"
        class="stream virtual-stream"
        :class="{ 'reveal-pending': !revealReady }"
      >
        <template #default="{ item, index, active }">
          <DynamicScrollerItem
            :item="item"
            :active="active"
            :data-index="index"
            :emit-resize="true"
            class="virtual-item"
            @resize="follow"
          >
            <TimelineBlock
              :block="item"
              :live="index === blocks.length - 1 && session.isBusy"
            />
          </DynamicScrollerItem>
        </template>
      </DynamicScroller>
      <main v-if="blocks.length === 0" class="stream" :class="{ 'reveal-pending': !revealReady }">
        <div class="inner empty-inner"><EmptyState /></div>
      </main>

      <!-- 侧栏打开时，被推开的那张卡整块变成"点一下收起"。
           用独立的覆盖层而不是给 .shell 挂 @click —— 菜单按钮就在 .shell 里面，
           挂在 .shell 上会让"点开侧栏"的同一次点击冒泡上去把它立刻关掉。 -->
      <button
        v-if="drawerOpen"
        class="drawer-dismiss"
        type="button"
        aria-label="收起侧栏"
        @click="closeDrawer()"
      />

      <Transition name="pop">
        <button v-if="showToBottom" class="to-bottom" aria-label="回到底部" @click="toBottom">
          <CoomiIcon name="arrowDown" :size="18" />
        </button>
      </Transition>

      <LoopProgressBar v-if="session.plan.active || (session.loop.active && session.plan.steps.length === 0)" />
      <!-- 1.5.4o5 第 5 项：原来这两块是裸 div + v-if，插入时直接「啪」地出现，没有任何动画。
           现在包进 Transition，复用已有的 pop 曲线（位移 + 轻微缩放 + 淡入）。 -->
      <Transition name="sheet-pop">
        <div v-if="session.retryConfirmation" class="retry-confirm">
          <div class="retry-line">
            <CoomiIcon name="alert" :size="16" />
            <span class="retry-text">{{ session.retryConfirmation.message }}</span>
            <!-- 第 4 项：报错代码就贴在提示旁边 -->
            <code v-if="session.retryConfirmation.code" class="retry-code">{{ session.retryConfirmation.code }}</code>
          </div>
          <div class="retry-actions">
            <button class="retry-secondary" @click="session.dismissRetry()">结束任务</button>
            <button class="retry-primary" @click="session.retryInterruptedTurn()">继续重试</button>
          </div>
        </div>
      </Transition>
      <Transition name="sheet-pop">
        <div v-if="session.undoConfirm" class="retry-confirm">
          <div class="retry-line"><CoomiIcon name="arrowLeft" :size="16" /><span class="retry-text">确定回撤这一轮？将撤回该轮执行与产生的所有修改，并返回上一轮状态。</span></div>
          <div class="retry-actions">
            <button class="retry-secondary" @click="session.cancelUndo()">取消</button>
            <button class="retry-primary" @click="session.confirmUndo()">确认回撤</button>
          </div>
        </div>
      </Transition>
      <Transition name="pop">
        <button
          v-if="session.lifeUnread.length && session.isGlobalSession && session.mode === 'life' && !session.isBusy"
          class="life-pill"
          type="button"
          @click="session.deliverLife()"
        >
          <CoomiIcon name="lifeRings" :size="15" />
          <span class="life-pill-label">{{ session.lifeUnreadName || '数字生命体' }} 想对你说</span>
          <span class="life-pill-preview">{{ session.lifeUnread[0].text }}</span>
        </button>
      </Transition>
      <StatusBar />
      <Composer />
    </div>

    <SideDrawer :open="drawerOpen" @close="closeDrawer" />
    <ArtifactMindMap :open="mindMapOpen" @close="closeMindMap" />
    <SendMorphOverlay />

    <ApprovalSheet
      v-if="session.pendingApproval"
      :card="session.pendingApproval"
      @decide="(d: ApprovalDecision) => onDecide(session.pendingApproval!.callId, d)"
    />
    <QuestionSheet
      v-else-if="session.pendingQuestion"
      :card="session.pendingQuestion"
      @answer="(answers: Record<string, string>) => onAnswer(session.pendingQuestion!.callId, answers)"
    />
  </div>
</template>

<style scoped>
/* 插件浮层容器：不拦事件，只负责定位。插件若要做可点击的浮层，自己开 pointer-events。 */
.plugin-overlay-layer {
  /* .chat 本身是 overflow:hidden 的高度容器；这里覆盖整块会话区，
     z-index 低于弹层（40/60）、高于消息流，插件想拦事件就自己开 pointer-events。 */
  position: absolute; inset: 0; z-index: 6; pointer-events: none;
}
.chat {
  height: 100%;
  min-height: 0;
  background-color: var(--bg);
  background-image:
    linear-gradient(var(--chat-background-overlay), var(--chat-background-overlay)),
    var(--chat-background-image);
  background-position: center;
  background-size: cover;
}


/*
 * 侧栏展开 = 对话界面当一张卡片**向右滑出去并侧翻**，侧栏就在它下面的底图上。
 *
 * 三个必须同时成立的条件（上一版一个都没满足）：
 *
 * 1) 层级。侧栏是"底图"，就必须真的画在下面。之前 .drawer-root 写死 z-index:60，
 *    而 .shell 没有层级 —— 于是侧栏整块盖在对话界面上，看起来还是老式抽屉。
 *    现在把侧栏压到 5、对话界面抬到 10，滑动才真的"露出"下面的侧栏。
 *
 * 2) 位移。卡片得真的往右让开。只写 rotateY 的话旋转轴在左边缘，卡片右边的部分
 *    只是转了个角度，并没有让出侧栏该占的那条宽度。
 *
 * 3) 旋转方向。rotateY 取**正**角度才是右边缘往后（远离视点）退。取负角度时
 *    右边缘是朝观察者这边怼过来的（CSS 里 +Z 朝观察者），看着像卡片被推斜了
 *    而不是被掀开。上一版写的 -26deg 就是这个毛病。
 *
 * 透视由 .chat 提供：3D 透视要由容器给，被旋转的元素自己不能同时当透视基准，
 * 否则每帧都要重算投影中心，WebView 上会明显掉帧。
 */
.chat {
  /* 侧栏宽度只在这里定义一次：面板和对话界面的位移量都读它，
     两边不一致就会出现"滑过去了但没对齐"的缝。 */
  --drawer-w: min(82vw, 340px);
  overflow: hidden;
}
/* 透视只在打开侧栏时才建。
   3D 透视会让整个子树进入一个全屏合成层，常开的话滚动和所有动画都在为它付代价 —
   这正是"还是卡的"的一部分原因。 */
.chat.pushed {
  /* 透视距离加大到 2000px：旋转同样角度时边缘拉伸更小，文字几乎不变形 */
  perspective: 2000px;
  perspective-origin: left center;
}
.shell {
  position: relative;
  /* 必须高于 .drawer-root，否则侧栏会盖在对话界面上（见上面的说明）。 */
  z-index: 10;
  display: flex; flex-direction: column; height: 100%; min-height: 0;
  background: var(--bg);
  /* 旋转轴贴左边缘：卡片像是被左手按住、右边缘被推开 */
  transform-origin: left center;
  /* 只过渡 transform：Android WebView 里 transform+border-radius 同时过渡会
     每帧重建合成层，表现为打开侧边栏时文字闪烁，而且明显掉帧。
     圆角直接切到位，不参与动画。
     1.5.4o5 第 8 项：缓动从 (0.22,0.68,0.19,1)（起步极快、尾段拖长）换成
     material 的 emphasised(0.2,0,0,1) —— 前段加速均匀、后段干净收住，看起来
     "顺"而不是"先窜出去再慢慢爬"。时长也从 340ms 收到 300ms。 */
  transition: transform .3s cubic-bezier(.2, 0, 0, 1);
  /* 常驻合成层：如果等 .pushed 才加 will-change，浏览器要在动画第一帧现建图层，
     那一帧会卡一下（用户感知是"侧栏刚打开时顿一下"）。常驻的成本是一张屏幕大小的
     纹理，换来的是动画全程零重建。 */
  will-change: transform;
  backface-visibility: hidden;
  /* 独立合成层：transform 动画全程不进主线程重排，第三方插件浮层也不会被牵连重绘。 */
  contain: layout paint;
}
.shell.pushed {
  /* 先整体右移一个侧栏宽度，再绕左边缘侧翻 14°（右边缘后退）。
     角度刻意收敛到 14°：再大卡片会明显变窄，右侧露出大片空白。
     **不再 scale**：整块卡片的文字按 0.94 的非整数倍缩放时，WebView 每帧都要把
     整棵文字重新栅格化（还会发虚），这是侧栏动画发卡的主因之一。 */
  /* 角度收到 8°：每一点 3D 旋转都会让 WebView 把整块文字重新栅格化一次，
     14° 时右侧文字的亚像素抖动肉眼可见。8° 仍有"卡片被掀开"的层次感，但栅格代价低得多。 */
  transform: translateX(var(--drawer-w)) rotateY(8deg);
  border-radius: 18px;
  overflow: hidden;
  /* 卡片边缘：一圈描边 + 朝左的投影，让"被掀起来的卡片"有实体感，
     和底下的侧栏分开。用 box-shadow 而不是 border —— border 会改变布局宽度，
     在已经 translate 的元素上会牵动右侧对齐。
     模糊半径从 28px 收到 12px：大半径投影在 3D 变换下每帧都要重算，代价很高。 */
  box-shadow:
    0 0 0 1px var(--border-strong),
    -8px 0 12px rgba(17, 22, 31, .16);
  /* 3D 变换下关掉背面渲染，少画一层 */
  backface-visibility: hidden;
}

/* 被推开那张卡上的"点一下收起"层：盖住整块卡片，避免和菜单按钮的事件冒泡打架。 */
.drawer-dismiss {
  position: absolute; inset: 0; z-index: 40;
  margin: 0; padding: 0; border: 0;
  background: transparent;
  cursor: pointer;
}
/* 减少动效 / 关闭全部动画时退化成不翻转的纯位移，避免晕眩 */
@media (prefers-reduced-motion: reduce) {
  .shell { transition: none; }
  .shell.pushed { transform: translateX(var(--drawer-w)); }
}
[data-all-animations-off] .shell { transition: none; }
[data-all-animations-off] .shell.pushed { transform: translateX(var(--drawer-w)); }

.stream {
  flex: 1; min-width: 0; min-height: 0; max-width: 100%; overflow-x: hidden; overflow-y: auto;
  -webkit-overflow-scrolling: touch; overscroll-behavior-y: contain;
}
/* 恢复期间只藏"看得见"的那一层：visibility 不参与布局，行高照常测量、滚动照常置底，
   显形时已经是最终位置，不会再有第二次跳动。 */
.stream.reveal-pending { visibility: hidden; }
.inner {
  display: flex; flex-direction: column; gap: 12px;
  width: 100%; min-width: 0; min-height: 100%; padding: 10px 12px 18px; overflow-x: hidden;
}
.empty-inner { min-height: 100%; }
/* 独占滚动层（对话中）：普通滚动容器，不做虚拟化。
   它和 .virtual-stream 的滚动行为完全一致，所以切换时用户感知不到换了个容器。 */
.own-stream { padding: 0; }
.virtual-stream { width: 100%; min-width: 0; padding: 10px 12px 18px; }
.virtual-item { width: 100%; min-width: 0; padding-bottom: 12px; }
/* 虚拟列表回收历史 DOM 时不重播入场位移，否则滑过回收边界就像整页跳一下。
   实时工具、推理展开与发送水滴仍保留各自的专用动画。 */
.virtual-stream :deep(.cascade) { animation: none; }

.to-bottom {
  position: absolute; left: 50%; bottom: 116px; z-index: 8;
  display: grid; place-items: center;
  width: 38px; height: 38px; margin-left: -19px;
  border: 1px solid var(--border); border-radius: 50%;
  background: var(--bg); color: var(--text-2);
  box-shadow: var(--shadow-2);
}
.to-bottom:active { background: var(--fill); }
.pop-enter-active, .pop-leave-active { transition: opacity .18s ease, transform .18s ease; }
.pop-enter-from, .pop-leave-to { opacity: 0; transform: translateY(8px) scale(.9); }

.life-pill {
  display: flex; align-items: center; gap: 7px;
  margin: 0 12px 6px; padding: 8px 12px;
  border: 1px solid color-mix(in srgb, var(--accent) 45%, var(--border));
  border-radius: 12px;
  background: linear-gradient(105deg, color-mix(in srgb, var(--accent-soft) 80%, var(--bg)), var(--bg));
  color: var(--accent);
  box-shadow: var(--shadow-1);
  text-align: left;
}
.life-pill-label { flex: none; font-size: 12.5px; font-weight: 650; }
.life-pill-preview {
  flex: 1; min-width: 0; overflow: hidden; white-space: nowrap; text-overflow: ellipsis;
  color: var(--text-3); font-size: 12px;
}

.retry-confirm { margin: 0 12px 6px; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); box-shadow: var(--shadow-1); }
.retry-line { display: flex; align-items: center; gap: 7px; font-size: 13px; color: var(--text-2); }
.retry-text { flex: 1; min-width: 0; }
/* 报错代码：等宽 + 橙色低饱和底，一眼能认出来但不会盖过正文 */
.retry-code {
  flex-shrink: 0; padding: 2px 7px; border-radius: 5px;
  background: var(--orange-soft); color: var(--orange);
  font-family: var(--font-mono); font-size: 11px; letter-spacing: .02em;
}
/* 重试/回撤弹层入场：从下方 10px 上浮 + 轻微放大，200ms 收住 ——
   和输入区其它浮层同一套节奏（composer 的 .effort-pop / 指令面板）。 */
.sheet-pop-enter-active { transition: opacity .2s ease, transform .22s cubic-bezier(.2,0,0,1); }
.sheet-pop-leave-active { transition: opacity .14s ease, transform .14s ease; }
.sheet-pop-enter-from { opacity: 0; transform: translateY(10px) scale(.97); }
.sheet-pop-leave-to { opacity: 0; transform: translateY(4px) scale(.99); }
.retry-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 9px; }
.retry-actions button { min-height: 34px; padding: 0 13px; border-radius: 6px; font-size: 13px; font-weight: 600; }
.retry-secondary { background: var(--fill); color: var(--text-2); }
.retry-primary { background: var(--blue); color: #fff; }
</style>
