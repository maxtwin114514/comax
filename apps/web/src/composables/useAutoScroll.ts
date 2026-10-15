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
const DETACH_PX = 40
const REATTACH_PX = 14
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
      const el = target.value
      if (!el || !following.value) return
      settleVirtualBottom?.()
      el.scrollTop = el.scrollHeight
      pinnedTop = el.scrollTop
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
