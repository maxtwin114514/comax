<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterView, useRouter } from 'vue-router'
import { useConfigStore } from './stores/config'
import { gsap } from './composables/useGsap'
import { useSessionStore } from './stores/session'
import { refresh as refreshPlugins, savePrompt as savePluginPrompt } from './stores/plugins'
import { emitPluginEvent, registerPetLayer } from './plugins/host'

const router = useRouter()
const direction = ref<'forward' | 'back'>('forward')
const config = useConfigStore()
const session = useSessionStore()

// ── 插件宿主（1.5.4 第 11 项）────────────────────────────────────────────
const petLayer = ref<HTMLElement | null>(null)
const toast = ref('')
let toastTimer: ReturnType<typeof setTimeout> | null = null

function showToast(text: string) {
  toast.value = text
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = ''; toastTimer = null }, 2400)
}

/** 插件通过 comax.prompt() 提交的文字，由宿主落盘（前端不直接发请求）。 */
function onPluginPrompt(event: Event) {
  const detail = (event as CustomEvent<{ id: string; text: string }>).detail
  if (detail?.id) void savePluginPrompt(detail.id, detail.text ?? '')
}
function onPluginToast(event: Event) {
  const detail = (event as CustomEvent<{ id: string; text: string }>).detail
  if (detail?.text) showToast(`[${detail.id}] ${detail.text}`)
}

// 会话事件转给插件：turn-start / turn-end。插件据此做动效或桌宠反应。
watch(() => session.isBusy, busy => {
  emitPluginEvent(busy ? 'turn-start' : 'turn-end', { sessionId: session.sessionId })
})

onMounted(() => {
  registerPetLayer(petLayer.value)
  window.addEventListener('comax:plugin-prompt', onPluginPrompt)
  window.addEventListener('comax:plugin-toast', onPluginToast)
  // 引擎没起来时 refresh 会失败，这是正常的：插件页里会显示错误，不阻塞界面。
  void refreshPlugins()
})

onBeforeUnmount(() => {
  window.removeEventListener('comax:plugin-prompt', onPluginPrompt)
  window.removeEventListener('comax:plugin-toast', onPluginToast)
  registerPetLayer(null)
})

router.beforeEach((to, from) => {
  const depth = (path: string) => path.split('/').filter(Boolean).length
  direction.value = depth(to.path) < depth(from.path) ? 'back' : 'forward'
})

// 全局动画总开关：同步到 <html data-all-animations-off> 让 CSS 选择器一次性生效。
// 开启「关闭所有动画」时，顺手清掉正在跑的 GSAP tween / timeline，
// 并通知所有组件收尾。
watch(() => config.allAnimationsOff, (off) => {
  const root = document.documentElement
  if (off) {
    root.dataset.allAnimationsOff = '1'
    // 不销毁 GSAP 根时间线，否则重新开启动画后新补间也无法播放。
    gsap.globalTimeline.getChildren(true, true, true).forEach(animation => {
      animation.progress(1)
      animation.kill()
    })
    // 触发组件自行清理
    window.dispatchEvent(new CustomEvent('coomi:all-animations-off'))
  } else {
    delete root.dataset.allAnimationsOff
  }
}, { immediate: true })
</script>

<template>
  <RouterView v-slot="{ Component }">
    <Transition :name="direction === 'back' ? 'page-back' : 'page-forward'" mode="out-in">
      <component :is="Component" />
    </Transition>
  </RouterView>
  <!-- 桌宠层：插件通过 comax.pet() 往里放东西；层级低于弹窗、高于页面 -->
  <div ref="petLayer" class="comax-pet-layer" aria-hidden="true" />
  <Transition name="comax-toast">
    <p v-if="toast" class="comax-plugin-toast" role="status">{{ toast }}</p>
  </Transition>
</template>

<style>
.page-forward-enter-active,.page-forward-leave-active,.page-back-enter-active,.page-back-leave-active{transition:transform .22s cubic-bezier(.22,.68,.19,1),opacity .18s ease;will-change:transform,opacity}
.page-forward-enter-from{transform:translateX(18px);opacity:0}.page-forward-leave-to{transform:translateX(-10px);opacity:0}
.page-back-enter-from{transform:translateX(-14px);opacity:0}.page-back-leave-to{transform:translateX(16px);opacity:0}
@media(prefers-reduced-motion:reduce){.page-forward-enter-active,.page-forward-leave-active,.page-back-enter-active,.page-back-leave-active{transition:opacity .1s ease}.page-forward-enter-from,.page-forward-leave-to,.page-back-enter-from,.page-back-leave-to{transform:none}}
[data-all-animations-off] .page-forward-enter-active,
[data-all-animations-off] .page-forward-leave-active,
[data-all-animations-off] .page-back-enter-active,
[data-all-animations-off] .page-back-leave-active{transition:opacity .08s ease}
[data-all-animations-off] .page-forward-enter-from,
[data-all-animations-off] .page-forward-leave-to,
[data-all-animations-off] .page-back-enter-from,
[data-all-animations-off] .page-back-leave-to{transform:none}

/* ── 插件宿主的基础样式（插件自己的样式由它自己负责）── */
.comax-pet-layer{position:fixed;inset:0;z-index:60;pointer-events:none}
.comax-pet-layer .comax-pet{
  position:fixed;right:14px;bottom:calc(var(--safe-bottom, 0px) + 96px);
  display:grid;place-items:center;pointer-events:auto;cursor:grab;
  filter:drop-shadow(0 4px 10px rgba(0,0,0,.18));
  animation:comax-pet-bob 3.2s ease-in-out infinite;
  user-select:none;-webkit-user-select:none;touch-action:none;
}
@keyframes comax-pet-bob{0%,100%{transform:translateY(0)}50%{transform:translateY(calc(-1 * var(--bob, 5px)))}}
.comax-plugin-toast{
  position:fixed;left:50%;bottom:calc(var(--safe-bottom, 0px) + 120px);z-index:80;
  transform:translateX(-50%);margin:0;padding:8px 14px;border-radius:999px;
  background:rgba(24,24,27,.9);color:#fff;font-size:12.5px;line-height:1.4;
  max-width:80vw;overflow-wrap:anywhere;
}
.comax-toast-enter-active,.comax-toast-leave-active{transition:opacity .2s ease,transform .2s ease}
.comax-toast-enter-from,.comax-toast-leave-to{opacity:0;transform:translate(-50%,8px)}
</style>
