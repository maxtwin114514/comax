<script setup lang="ts">
/**
 * 思考过程。
 * 正在想的时候只占一行：sparkle + 最后一句 + 渐变流光，像跑马灯一样滚过去；
 * 停下来之后折成「思考过程 · N 字」，点开才铺全文。
 * 没有 streaming 标记可用，所以用「最近 900ms 内还在长」判定活跃。
 * 展开/收回用高度过渡（grid 0fr→1fr 之外，这里直接量算内容高度做 height 补间，
 * 避免 transition 对 height:auto 无效），尊重「关闭所有动画」总开关与 reduced-motion。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { ReasoningBlock } from '@/stores/viewModel'
import { useConfigStore } from '@/stores/config'
import CoomiIcon from './CoomiIcon.vue'

const props = defineProps<{ block: ReasoningBlock }>()
const config = useConfigStore()

const open = ref(false)
const live = ref(false)
const bodyRef = ref<HTMLElement | null>(null)
const bodyHeight = ref(0)
let timer: ReturnType<typeof setTimeout> | null = null

watch(() => props.block.content, () => {
  live.value = true
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { live.value = false }, 900)
  if (open.value) void measure()
})
onBeforeUnmount(() => { if (timer) clearTimeout(timer) })

const reduced = typeof window !== 'undefined'
  && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
const animate = computed(() => !reduced && !config.allAnimationsOff)

async function measure() {
  await nextTick()
  bodyHeight.value = bodyRef.value?.scrollHeight ?? 0
}

async function toggle() {
  open.value = !open.value
  if (open.value) await measure()
}

const chars = computed(() => props.block.content.replace(/\s+/g, '').length)
const tick = computed(() => {
  const lines = props.block.content.split('\n').map(s => s.trim()).filter(Boolean)
  const t = lines[lines.length - 1] ?? ''
  return t.length > 46 ? '…' + t.slice(-46) : t
})
</script>

<template>
  <div class="reasoning fade-in">
    <button class="toggle" @click="toggle">
      <CoomiIcon name="sparkle" :size="14" class="spark" :class="{ live }" />
      <span v-if="live" class="ticker shimmer-text">{{ tick || '正在思考…' }}</span>
      <template v-else>
        <span class="label">思考过程</span>
        <span class="count">{{ chars }} 字</span>
      </template>
      <CoomiIcon name="chevronRight" :size="13" class="chev" :class="{ open }" />
    </button>
    <div
      class="body-wrap"
      :class="{ open, animate }"
      :style="animate ? { height: open ? bodyHeight + 'px' : '0px' } : undefined"
    >
      <div ref="bodyRef" class="body">{{ block.content }}</div>
    </div>
  </div>
</template>

<style scoped>
.reasoning { padding: 0; }
.toggle {
  display: flex; align-items: center; gap: 7px;
  width: 100%; min-height: 32px; padding: 4px 2px;
  border: 0; background: none; text-align: left;
  font-size: 13px; color: var(--text-3);
}
.spark { flex-shrink: 0; color: var(--text-3); }
.spark.live { color: var(--blue); animation: coomi-blink 1.4s ease-in-out infinite; }
.ticker {
  flex: 1; min-width: 0; font-size: 12.8px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.label { font-weight: 600; }
.count { flex: 1; font-size: 11.5px; color: var(--text-3); }
.chev { flex-shrink: 0; transition: transform .18s; }
.chev.open { transform: rotate(90deg); }

/* 展开容器：动画开启时用 height 补间，关闭时直接显示/隐藏 */
.body-wrap { overflow: hidden; }
.body-wrap.animate { transition: height .22s cubic-bezier(.22,.68,.19,1), opacity .18s ease; }
.body-wrap:not(.open) { opacity: 0; }
.body-wrap.open { opacity: 1; }
.body-wrap:not(.animate):not(.open) { display: none; }

.body {
  margin: 4px 0 0 6px; padding: 9px 13px;
  border-left: 2px solid var(--blue-border);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  background: var(--fill);
  font-size: 13px; line-height: 1.7; color: var(--text-2);
  white-space: pre-wrap; word-break: break-word;
}
</style>
