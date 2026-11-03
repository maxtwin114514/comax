import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

/**
 * 瀑布流跟随滚动。
 *
 * 规则只有一条：**只有用户自己的手指/滚轮能决定「跟不跟随」**。
 * 虚拟列表重测量时会自己写 scrollTop，那是程序行为，绝不能被当成"用户在往上翻"；
 * 反过来，用户往上滑之后，也绝不能被内容增长拽回底部。
 *
 * 1.5.4o4 修的两件事：
 *   ① 「向上滑了一段距离还会自动置底」—— 手指离开后惯性还在滑，旧实现只看
 *      touchstart 期间的 scroll 事件，惯性滚动落进了"程序自己滚动"的盲区，
 *      于是一有新内容就 follow 到底。现在 touchmove / touchend 之后 700ms 内
 *      只要 scrollTop 是**变小**的，一律算用户上翻。
 *   ② 「回复完成锁死」的补丁原来靠无条件置底，撞上了用户上翻。改成事件区分：
 *      内容变高只 follow；只有切会话/恢复历史这种"必须回到底部"的场合才 jump。
 */
/**
 * 脱离跟随的距离阈值。
 * 手机上 12px 不到一行：只要真的离开底部就停止跟随。
 */
const DETACH_PX = 12
/** 回到底部多近才恢复跟随。 */
const REATTACH_PX = 8
/** scrollTop 是小数，写进去再读回来可能差一点点。 */
const SAME_POS_PX = 2
/** 用户手指离开后，惯性滚动仍要算作用户行为的时长。 */
const INERTIA_MS = 700
/** 脱离跟随后的冷静期：这段时间内不因为"看起来在底部"就自动恢复。 */
const DETACH_COOLDOWN_MS = 400
/** 我们自己发起的平滑滚动期间不判定脱离。 */
const SMOOTH_MS = 800

export function useAutoScroll(
  target: Ref<HTMLElement | null>,
  settleVirtualBottom?: () => void,
) {
  const following = ref(true)
  let raf = 0
  /** 我们自己最后一次把 scrollTop 写成了多少。 */
  let pinnedTop = -1
  let suppressUntil = 0
  let touching = false
  let touchReleaseTimer: ReturnType<typeof setTimeout> | null = null
  let attached: HTMLElement | null = null
  let lastInputTs = 0
  /** 上一次 onScroll 看到的位置，用来判断"这次是往上还是往下"。 */
  let lastScrollTop = 0
  /** 手指离开的时刻，惯性判定窗口的起点。 */
  let touchEndAt = 0
  /** 脱离跟随的时刻，冷静期的起点。 */
  let detachedAt = 0

  function distanceFromBottom(el: HTMLElement): number {
    return el.scrollHeight - el.scrollTop - el.clientHeight
  }

  function detach() {
    if (!following.value) return
    following.value = false
    detachedAt = performance.now()
  }

  function onScroll() {
    const el = target.value
    if (!el) return
    const now = performance.now()
    const top = el.scrollTop
    const movedUp = top < lastScrollTop - 4
    lastScrollTop = top
    const d = distanceFromBottom(el)

    if (!following.value) {
      // 只有「用户自己滑回底部」才恢复：既要在近底部，也要过了冷静期，
      // 避免程序把位置写到底后立刻又被判成"用户想跟随"（来回抖）。
      if (d < REATTACH_PX && now - detachedAt > DETACH_COOLDOWN_MS
        && now >= suppressUntil) {
        following.value = true
      }
      return
    }

    if (now < suppressUntil) return
    // 用户正在摸屏幕，或者刚离开手指不到 700ms 且在往上滑 —— 都算用户上翻。
    const touchingNow = touching || now - touchEndAt < INERTIA_MS
    const recentWheel = now - lastInputTs < 250
    if (d > DETACH_PX && (movedUp && (touchingNow || recentWheel))) {
      detach()
      return
    }
    // 手指按住不放、但 scrollTop 已被程序滚到上面：以手指为准，也脱离。
    if (d > DETACH_PX && touching) detach()
  }

  /** 内容变化后调用；只在跟随态生效。 */
  function follow() {
    if (!following.value || touching) return
    if (raf) return
    raf = requestAnimationFrame(() => {
      raf = 0
      const scroller = target.value
      if (!scroller || !following.value || touching) return
      const bottom = scroller.scrollHeight
      if (settleVirtualBottom) {
        // 有虚拟滚动器时交给它一次对到底，不再补写第二遍（两次写入 = 上下闪）
        settleVirtualBottom()
      } else if (Math.abs(scroller.scrollTop - (bottom - scroller.clientHeight)) > SAME_POS_PX) {
        scroller.scrollTop = bottom
      }
      pinnedTop = scroller.scrollTop
      lastScrollTop = scroller.scrollTop
    })
  }

  /** 无条件回到底部（切会话 / 恢复历史 / 点"回到底部"）。 */
  function jumpToBottom() {
    const el = target.value
    if (!el) return
    following.value = true
    touching = false
    suppressUntil = performance.now() + SMOOTH_MS
    el.scrollTop = el.scrollHeight
    pinnedTop = el.scrollTop
    lastScrollTop = el.scrollTop
    settleVirtualBottom?.()
  }

  function onTouchStart() {
    touching = true
    touchEndAt = 0
    if (touchReleaseTimer) { clearTimeout(touchReleaseTimer); touchReleaseTimer = null }
    const el = target.value
    if (el) lastScrollTop = el.scrollTop
    lastInputTs = performance.now()
  }

  function onTouchMove() {
    lastInputTs = performance.now()
  }

  function onTouchEnd() {
    touching = false
    touchEndAt = performance.now()
    if (touchReleaseTimer) clearTimeout(touchReleaseTimer)
    touchReleaseTimer = setTimeout(() => {
      touchReleaseTimer = null
      const el = target.value
      if (!el) return
      if (!following.value && distanceFromBottom(el) < REATTACH_PX
        && performance.now() - detachedAt > DETACH_COOLDOWN_MS) {
        following.value = true
      }
    }, 120)
  }

  function markUserInput() {
    lastInputTs = performance.now()
    const el = target.value
    if (el) lastScrollTop = el.scrollTop
  }

  function detachListeners(el: HTMLElement | null) {
    if (!el) return
    el.removeEventListener('scroll', onScroll)
    el.removeEventListener('touchstart', onTouchStart)
    el.removeEventListener('touchmove', onTouchMove)
    el.removeEventListener('touchend', onTouchEnd)
    el.removeEventListener('touchcancel', onTouchEnd)
    el.removeEventListener('wheel', markUserInput)
    el.removeEventListener('pointerdown', markUserInput)
  }

  function attach(el: HTMLElement | null) {
    if (attached === el) return
    detachListeners(attached)
    attached = el
    if (!el) return
    lastScrollTop = el.scrollTop
    el.addEventListener('scroll', onScroll, { passive: true })
    el.addEventListener('touchstart', onTouchStart, { passive: true })
    el.addEventListener('touchmove', onTouchMove, { passive: true })
    el.addEventListener('touchend', onTouchEnd, { passive: true })
    el.addEventListener('touchcancel', onTouchEnd, { passive: true })
    el.addEventListener('wheel', markUserInput, { passive: true })
    el.addEventListener('pointerdown', markUserInput, { passive: true })
    follow()
  }

  watch(target, attach, { immediate: true })

  onBeforeUnmount(() => {
    detachListeners(attached)
    attached = null
    if (raf) cancelAnimationFrame(raf)
    if (touchReleaseTimer) clearTimeout(touchReleaseTimer)
  })

  return { following, follow, jumpToBottom }
}
