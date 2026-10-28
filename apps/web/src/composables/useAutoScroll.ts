import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

/**
 * 瀑布流跟随滚动。
 *
 * - 默认贴底：每次内容增长用 rAF 合并一次滚动，流式输出下不会一秒 setState 几十次；
 * - 用户往上滑超过阈值就脱离跟随（不再跟着往下跑），并露出「回到底部」；
 * - 重新滑到底部自动恢复跟随。
 *
 * 关键修复：
 * - 区分「用户真正滚动」和「虚拟列表自身测量导致的 scrollTop 抖动」。
 *   只有在 wheel/pointer/touch 输入后 250ms 内、且 scrollTop 真正移动了 >4px，
 *   才判定为用户上翻，脱离跟随。避免虚拟回收行重测量把「正在跟随」误判成「用户跑了」。
 * - jumpToBottom 不再连续调度 rAF + smooth scroll，避免持续抢滚动位置导致页面乱跳。
 */
/**
 * 脱离 / 重新跟随的距离阈值（1.5.4o3 第 5 项）。
 *
 * 用户原话：「自动置底的判定距离太长了，都滑到上面了还会自动置底」。
 * 40px 在手感上等于「刚想往上翻一屏，它又把你拽回去」—— 手机上 40px 还不到一行。
 * 现在缩到 12px：只要真的离开底部一行以内就停止跟随；回到底部 8px 内再恢复。
 * 判断依据仍然只有**用户输入事件**（见 onScroll），所以缩小阈值不会误伤。
 */
const DETACH_PX = 12
const REATTACH_PX = 8
/** scrollTop 是小数，写进去再读回来可能差一点点。 */
const SAME_POS_PX = 2
/** 「回到底部」的平滑滚动期间不判定脱离 —— 那一路的 scroll 事件都是我们自己发的。 */
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
  let lastInputTop = -1

  function distanceFromBottom(el: HTMLElement): number {
    return el.scrollHeight - el.scrollTop - el.clientHeight
  }

  function onScroll() {
    const el = target.value
    if (!el) return
    const now = performance.now()
    const deltaFromLast = Math.abs(el.scrollTop - lastInputTop)
    const userScrolled = (now - lastInputTs < 250) && deltaFromLast > 4
    const d = distanceFromBottom(el)
    if (following.value) {
      const moved = Math.abs(el.scrollTop - pinnedTop) > SAME_POS_PX
      if ((touching || (userScrolled && moved)) && d > DETACH_PX && now >= suppressUntil) following.value = false
    } else if (d < REATTACH_PX) {
      following.value = true
    }
  }

  /** 内容变化后调用；只在跟随态生效。 */
  function follow() {
    if (!following.value || touching) return
    if (raf) return
    raf = requestAnimationFrame(() => {
      raf = 0
      const scroller = target.value
      if (!scroller || !following.value) return
      /**
       * 1.5.4o3 第 5 项：输出时"上下闪"的根因就在这里。
       *
       * 原来每帧先调 settleVirtualBottom()（虚拟列表内部会自己把滚动器挪到底），
       * 紧接着又写一次 el.scrollTop = scrollHeight。两次写入如果落在同一帧里
       * 且中间内容高度刚变化，浏览器会按第一个位置先绘制一次、再按第二个位置绘制
       * 一次 —— 看到的就是"上下闪一下"。流式输出时每帧都在重复这个过程。
       *
       * 现在只做一件事：如果虚拟列表需要收尾就让它收尾，否则直接写到底；
       * 且写之前先比一下目标位置，位置本来就对就完全不碰（避免无意义的重排）。
       */
      const bottom = scroller.scrollHeight
      if (settleVirtualBottom) {
        // 有虚拟滚动器时交给它：它会同时把内部容器与 scrollTop 一次对到底，
        // 我们不再补写，避免两次写入。
        settleVirtualBottom()
      } else if (Math.abs(scroller.scrollTop - (bottom - scroller.clientHeight)) > SAME_POS_PX) {
        scroller.scrollTop = bottom
      }
      pinnedTop = scroller.scrollTop
    })
  }

  function jumpToBottom() {
    const el = target.value
    if (!el) return
    following.value = true
    touching = false
    suppressUntil = performance.now() + SMOOTH_MS
    el.scrollTop = el.scrollHeight
    pinnedTop = el.scrollTop
    settleVirtualBottom?.()
  }

  function onTouchStart() {
    touching = true
    if (touchReleaseTimer) { clearTimeout(touchReleaseTimer); touchReleaseTimer = null }
  }

  function onTouchEnd() {
    if (touchReleaseTimer) clearTimeout(touchReleaseTimer)
    touchReleaseTimer = setTimeout(() => {
      touching = false
      const el = target.value
      if (el && distanceFromBottom(el) < REATTACH_PX) following.value = true
    }, 90)
  }

  function markUserInput() {
    lastInputTs = performance.now()
    const el = target.value
    lastInputTop = el ? el.scrollTop : -1
  }

  function detach(el: HTMLElement | null) {
    if (!el) return
    el.removeEventListener('scroll', onScroll)
    el.removeEventListener('touchstart', onTouchStart)
    el.removeEventListener('touchend', onTouchEnd)
    el.removeEventListener('touchcancel', onTouchEnd)
    el.removeEventListener('wheel', markUserInput)
    el.removeEventListener('pointerdown', markUserInput)
  }

  function attach(el: HTMLElement | null) {
    if (attached === el) return
    detach(attached)
    attached = el
    if (!el) return
    el.addEventListener('scroll', onScroll, { passive: true })
    el.addEventListener('touchstart', onTouchStart, { passive: true })
    el.addEventListener('touchend', onTouchEnd, { passive: true })
    el.addEventListener('touchcancel', onTouchEnd, { passive: true })
    el.addEventListener('wheel', markUserInput, { passive: true })
    el.addEventListener('pointerdown', markUserInput, { passive: true })
    follow()
  }

  watch(target, attach, { immediate: true })

  onBeforeUnmount(() => {
    detach(attached)
    attached = null
    if (raf) cancelAnimationFrame(raf)
    if (touchReleaseTimer) clearTimeout(touchReleaseTimer)
  })

  return { following, follow, jumpToBottom }
}
