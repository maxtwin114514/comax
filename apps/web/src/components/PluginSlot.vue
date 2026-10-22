<script setup lang="ts">
/**
 * 插件插槽容器。
 *
 * 用法：`<PluginSlot name="composer" />`。它只负责"给一块地"，
 * 插件拿到的就是这块 div —— 内容和样式都由插件决定，宿主不插手，
 * 这样插件作者可以自由做布局与动效，也不会被宿主的 scoped 样式压住。
 *
 * 卸载时调用插件返回的清理函数（约定俗成返回 function），
 * 否则插件注册的定时器 / 事件监听会在页面切换后继续跑。
 */
import { onBeforeUnmount, ref, watch } from 'vue'
import { contributions, type PluginContribution } from '@/plugins/host'

const props = defineProps<{ name: string }>()
const host = ref<HTMLElement | null>(null)
const cleanups = new Map<string, () => void>()

function cleanupAll() {
  for (const [pluginId, dispose] of cleanups) {
    try { dispose() } catch { /* 插件清理失败不影响宿主 */ }
    cleanups.delete(pluginId)
  }
}

function mount(list: PluginContribution[] | undefined) {
  const element = host.value
  if (!element) return
  // 每次都重建：插件可能已经换了实现，残留的旧 DOM 会叠加。
  cleanupAll()
  element.replaceChildren()
  for (const contribution of list ?? []) {
    const slotEl = document.createElement('div')
    slotEl.className = 'comax-plugin-slot'
    slotEl.dataset.plugin = contribution.pluginId
    element.appendChild(slotEl)
    try {
      const dispose = contribution.render(slotEl)
      if (typeof dispose === 'function') cleanups.set(contribution.pluginId, dispose)
    } catch (error) {
      slotEl.textContent = ''
      slotEl.dataset.error = String(error)
    }
  }
}

// flush: 'post' 很关键：插槽根节点是 v-if，贡献到达时 DOM 还没更新，
// 用默认的 pre 时机拿不到 host，插件会"注册成功但什么都不显示"。
watch(
  () => contributions.value[props.name],
  list => mount(list),
  { immediate: true, deep: false, flush: 'post' },
)

onBeforeUnmount(() => {
  cleanupAll()
  host.value?.replaceChildren()
})
</script>

<template>
  <div v-if="(contributions[name]?.length ?? 0) > 0" ref="host" class="plugin-slot" />
</template>

<style scoped>
.plugin-slot {
  display: contents;
}
.plugin-slot :deep(.comax-plugin-slot) {
  display: contents;
}
</style>
