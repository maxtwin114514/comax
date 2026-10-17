<script setup lang="ts">
/**
 * 思考过程。
 * 思考进行中：显示 3 行 + 半行模糊（第 4 行只露一半且模糊），底部对齐滚动。
 * 思考结束后：折成「思考过程 · N 字」，点开铺全文。
 * 没有 streaming 标记可用，所以用「最近 900ms 内还在长」判定活跃。
 * 展开/收回用高度过渡（直接量算内容高度做 height 补间，避免 transition 对 height:auto 无效），
 * 尊重「关闭所有动画」总开关与 reduced-motion。
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
const thinkingRef = ref<HTMLElement | null>(null)
const bodyHeight = ref(0)
let timer: ReturnType<typeof setTimeout> | null = null

watch(() => props.block.content, () => {
  live.value = true
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { live.value = false }, 900)
  if (open.value) void measure()
  // 思考内容在长：把 thinking-inner 滚到底，保持最新一行可见
  nextTick(() => {
    const el = thinkingRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
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
/** 思考中显示用的行：最多取「最后 4 行」（3 行完整 + 半行模糊） */
const visibleLines = computed(() => props.block.content.split('\n').slice(-4))
</script>

<template>
  <div class="reasoning fade-in">
    <button class="toggle" @click="toggle">
      <CoomiIcon name="sparkle" :size="14" class="spark" :class="{ live }" />
      <template v-if="!live">
        <span class="label">思考过程</span>
        <span class="count">{{ chars }} 字</span>
      </template>
      <CoomiIcon name="chevronRight" :size="13" class="chev" :class="{ open }" />
    </button>

    <!-- 思考进行中：3 行 + 半行模糊，底部对齐 -->
    <div v-if="live" class="thinking" :class="{ reduced: !animate }">
      <div class="thinking-inner" ref="thinkingRef">
        <template v-for="(line, idx) in visibleLines" :key="idx">
          <div
            class="tline"
            :class="{ faded: idx === visibleLines.length - 1 && visibleLines.length >= 4 }"
          >{{ line }}</div>
        </template>
      </div>
    </div>

    <!-- 思考结束：可折叠全文（保留原有展开/收回逻辑） -->
    <div
      v-else
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
.label { font-weight: 600; }
.count { flex: 1; font-size: 11.5px; color: var(--text-3); }
.chev { flex-shrink: 0; transition: transform .18s; }
.chev.open { transform: rotate(90deg); }

/* ── 思考进行中：3 行 + 半行模糊，底部对齐 ── */
.thinking {
  overflow: hidden;
  width: 100%;
  /* 固定字号：容器高度用 em 计算，必须和 .tline 的 font-size 同基准，
     否则容器 1.4em 与行 1.4em 用的是两个字号，3 行半的裁切会错位。 */
  font-size: 13px;
  --tline-h: 1.4em;
  /* 容器高度 = 3.5 行 × 行高 */
  height: calc(3.5 * var(--tline-h));
  margin: 4px 0 0 6px;
  padding: 0 13px;
  border-left: 2px solid var(--blue-border);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  background: var(--fill);
  display: flex;
  align-items: flex-end;
}
.thinking.reduced { height: auto; max-height: calc(3.5 * var(--tline-h)); }
.thinking-inner {
  width: 100%;
  max-height: 100%;
  overflow-x: hidden;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  scrollbar-width: none;          /* firefox */
}
.thinking-inner::-webkit-scrollbar { display: none; }  /* chrome */
.tline {
  font-size: 1em;
  line-height: var(--tline-h);
  /* 终端黑版要求思考过程为白色，白版为黑色；其他主题下等于原来的 --text-2 */
  color: var(--term-think);
  white-space: pre-wrap;
  word-break: break-word;
}
/* 第 4 行（最后一行）—— 只露一半 + 模糊，暗示还有更多内容 */
.tline.faded {
  max-height: 0.7em;
  overflow: hidden;
  filter: blur(2px);
  opacity: 0.55;
  -webkit-mask-image: linear-gradient(to bottom, #000 35%, transparent 100%);
  mask-image: linear-gradient(to bottom, #000 35%, transparent 100%);
}

/* ── 思考结束：折叠全文（保留原逻辑） ── */
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
