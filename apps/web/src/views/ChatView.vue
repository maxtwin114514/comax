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
    jumpToBottom()
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

      <DynamicScroller
        v-if="blocks.length > 0"
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
      <!-- 空态必须独立渲染：DynamicScroller 的 #empty 插槽是无条件渲染进滚动内容的
           （不是「列表为空才渲染」），会一直贴在内容顶部把格言显示在对话上方。
           这里回到以前的独立空态层：撑满可视区并按整块高度居中。 -->
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
        <button v-if="!following" class="to-bottom" aria-label="回到底部" @click="jumpToBottom">
          <CoomiIcon name="arrowDown" :size="18" />
        </button>
      </Transition>

      <LoopProgressBar v-if="session.plan.active || (session.loop.active && session.plan.steps.length === 0)" />
      <div v-if="session.retryConfirmation" class="retry-confirm">
        <div><CoomiIcon name="alert" :size="16" /><span>{{ session.retryConfirmation }}</span></div>
        <div class="retry-actions">
          <button class="retry-secondary" @click="session.dismissRetry()">结束任务</button>
          <button class="retry-primary" @click="session.retryInterruptedTurn()">继续重试</button>
        </div>
      </div>
      <div v-if="session.undoConfirm" class="retry-confirm">
        <div><CoomiIcon name="arrowLeft" :size="16" /><span>确定回撤这一轮？将撤回该轮执行与产生的所有修改，并返回上一轮状态。</span></div>
        <div class="retry-actions">
          <button class="retry-secondary" @click="session.cancelUndo()">取消</button>
          <button class="retry-primary" @click="session.confirmUndo()">确认回撤</button>
        </div>
      </div>
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
  perspective: 1400px;
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
     will-change 只在推开时才加（见 .shell.pushed），避免空闲时也常驻一张全屏层。 */
  transition: transform .34s cubic-bezier(.22, .68, .19, 1);
  /* 常驻合成层：如果等 .pushed 才加 will-change，浏览器要在动画第一帧现建图层，
     那一帧会卡一下（用户感知是"侧栏刚打开时顿一下"）。常驻的成本是一张屏幕大小的
     纹理，换来的是动画全程零重建。 */
  will-change: transform;
  backface-visibility: hidden;
}
.shell.pushed {
  /* 先整体右移一个侧栏宽度，再绕左边缘侧翻 14°（右边缘后退）。
     角度刻意收敛到 14°：再大卡片会明显变窄，右侧露出大片空白。
     **不再 scale**：整块卡片的文字按 0.94 的非整数倍缩放时，WebView 每帧都要把
     整棵文字重新栅格化（还会发虚），这是侧栏动画发卡的主因之一。 */
  transform: translateX(var(--drawer-w)) rotateY(14deg);
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

.retry-confirm { margin: 0 12px 6px; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); box-shadow: var(--shadow-1); }.retry-confirm > div:first-child { display: flex; align-items: center; gap: 7px; font-size: 13px; color: var(--text-2); }
.retry-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 9px; }
.retry-actions button { min-height: 34px; padding: 0 13px; border-radius: 6px; font-size: 13px; font-weight: 600; }
.retry-secondary { background: var(--fill); color: var(--text-2); }
.retry-primary { background: var(--blue); color: #fff; }
</style>
