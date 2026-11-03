<script setup lang="ts">
/**
 * 思考过程。
 *
 * 显示形态：
 *   · 流式中且未展开 —— 「成长窗口」：起始 1 行，随内容行数平滑长到 3.5 行；
 *     顶边淡出（旧内容）、底边渐入（刚写出的字），整体像水流往下推；
 *   · 流式中且用户点开 —— 直接铺全文（带最大高度，自己滚动）；
 *   · 结束 —— 折成「思考过程 · N 字」，点开铺全文。
 *
 * 1.5.4o5 第 1 项（收回后空出一点）：旧结构是 `body-wrap > body`，而 body 自己带
 * `margin-top: 4px`。grid 轨道收到 0fr 时 item 高度为 0，但 **margin 不算进 item
 * 尺寸**，那 4px 永远留着。现在改成三层：wrap(grid) > inner(overflow:hidden,
 * min-height:0) > body(真正带间距的内容)，间距进了内层，收起就是干净的 0。
 *
 * 1.5.4o5 第 2 项（思考中点展开/收回没反应）：一是流式中切换窗口/全文走的是 v-if，
 * 节点被直接替换、没有任何过渡；二是内层 overflow 收缩时读者看不到变化。现在流式中
 * 的切换走 120ms 短过渡并保留节点，点击立刻有反馈。
 *
 * 1.5.4o5 第 9 项（从第一行开始、平滑长到三行、新字渐清晰、内容平滑下滑）：
 *   · 容器高度由「当前行数」驱动，CSS transition 从 1 行长到 3.5 行；
 *   · 双端 mask：顶边淡出（旧）+ 底边渐入（新）；
 *   · 内容用 flex 底部对齐 + 顶部裁切，新行从底部冒出、整体往下推，不写 scrollTop。
 *
 * 为什么不用真的 `filter: blur()`：blur 会让文本每帧重新栅格化，正是 o4 之前
 * "思考卡死"的成因。渐变 mask 做出等价的"渐清晰"观感，代价几乎为零。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { ReasoningBlock } from '@/stores/viewModel'
import { useConfigStore } from '@/stores/config'
import CoomiIcon from './CoomiIcon.vue'

const props = defineProps<{ block: ReasoningBlock; live?: boolean }>()
const config = useConfigStore()

/** 用户是否手动展开了这段思考。展开后即使还在流式也铺全文。 */
const open = ref(false)
/** 这段思考是否还在长（内容有更新）。 */
const streaming = ref(props.live === true)

let idleTimer: ReturnType<typeof setTimeout> | null = null
function clearIdle() {
  if (idleTimer) { clearTimeout(idleTimer); idleTimer = null }
}

watch(() => props.block.content, () => {
  streaming.value = true
  clearIdle()
  idleTimer = setTimeout(() => { idleTimer = null; streaming.value = false }, 900)
})

// 整轮结束（父级 live 变 false）：立刻收尾，不等 900ms 计时器。
watch(() => props.live, value => {
  if (!value) { clearIdle(); streaming.value = false }
})

onBeforeUnmount(clearIdle)

/**
 * 尾巴窗口取多少字。
 * 手机上一行约 20–24 个汉字，3.5 行 ≈ 80 字；600 字是 7 屏余量，
 * 既保证窗口里永远有内容，又不会因为字符串太长拖慢每帧更新。
 */
const TAIL_CHARS = 600

/** 只渲染末尾一段；截断处丢掉半行，避免出现半截字。 */
const tailText = computed(() => {
  const content = props.block.content
  if (content.length <= TAIL_CHARS) return content
  const slice = content.slice(-TAIL_CHARS)
  const newline = slice.indexOf('\n')
  return newline >= 0 ? slice.slice(newline + 1) : slice
})

/**
 * 当前"占几行"（1 → 3.5）。
 *
 * 只按换行数与长度粗估，不做真实测量：真测量要读布局，流式下每帧一次就是掉帧。
 * 粗估误差由「窗口固定 3.5 行上限 + 底部对齐」吸收，用户看不出差别。
 */
const MAX_LINES = 3.5
const windowLines = computed(() => {
  const text = tailText.value
  if (!text) return 1
  const explicit = text.split('\n').length
  // 一行按 22 个汉字估；两者取大，避免长段落一直停在 1 行。
  const wrapped = Math.ceil(text.length / 22)
  return Math.min(MAX_LINES, Math.max(1, Math.max(explicit, wrapped)))
})

/** 字数只在结束后算一次：流式期间每帧跑全文正则正是旧版卡死的原因之一。 */
const chars = computed(() => (
  streaming.value ? 0 : props.block.content.replace(/\s+/g, '').length
))

const reduced = typeof window !== 'undefined'
  && typeof window.matchMedia === 'function'
  && window.matchMedia('(prefers-reduced-motion: reduce)').matches
const animate = computed(() => !reduced && !config.allAnimationsOff)

/** 任何时候点击都生效：流式中也能展开/收起。 */
function toggle() {
  open.value = !open.value
}
</script>

<template>
  <div class="reasoning">
    <button class="toggle" type="button" :aria-expanded="open" @click="toggle">
      <CoomiIcon name="sparkle" :size="14" class="spark" :class="{ live: streaming && !open }" />
      <template v-if="streaming && !open">
        <span class="label">思考中</span>
        <span class="count dots">···</span>
      </template>
      <template v-else>
        <span class="label">思考过程</span>
        <span class="count">{{ chars }} 字</span>
      </template>
      <CoomiIcon name="chevronRight" :size="13" class="chev" :class="{ open }" />
    </button>

    <!-- 成长窗口：流式中、未展开。高度随行数从 1 行长到 3.5 行。 -->
    <div
      v-if="streaming && !open"
      class="thinking"
      :class="{ reduced: !animate }"
      :style="{ '--lines': windowLines }"
    >
      <div class="thinking-inner">
        <div class="thinking-tail">{{ tailText }}</div>
      </div>
    </div>

    <!-- 全文：用户展开（流式/结束后都可用）。三层结构，收起后不留空隙。 -->
    <div v-else class="body-wrap" :class="{ open, animate, streaming }">
      <div class="body-inner">
        <div class="body">{{ block.content }}</div>
      </div>
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
.dots { letter-spacing: .12em; color: var(--blue); opacity: .7; }
.chev { flex-shrink: 0; transition: transform .18s; }
.chev.open { transform: rotate(90deg); }

/* ── 成长窗口 ── */
.thinking {
  /* font-size 与行高同一基准：容器高度用 em 算，必须和内部行高对齐 */
  font-size: 13px;
  --tline-h: 1.4em;
  /* 高度由 --lines 驱动：1 行 → 3.5 行。transition 只在行数变化时触发一次。 */
  height: calc(var(--lines, 1) * var(--tline-h));
  margin: 4px 0 0 6px;
  padding: 0 13px;
  border-left: 2px solid var(--blue-border);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  background: var(--fill);
  overflow: hidden;
  transition: height .42s cubic-bezier(.22,.68,.19,1);
  /* 顶边淡出（旧内容） + 底边渐入（刚写出的字）：等价于"从模糊到清晰"，
     但不需要每帧重栅格化文本。 */
  -webkit-mask-image: linear-gradient(to bottom,
    transparent 0, #000 22%, #000 74%, rgba(0, 0, 0, .38) 100%);
  mask-image: linear-gradient(to bottom,
    transparent 0, #000 22%, #000 74%, rgba(0, 0, 0, .38) 100%);
}
.thinking.reduced {
  height: auto;
  max-height: calc(3.5 * var(--tline-h));
  transition: none;
}
/* 内层 flex 底部对齐：内容高于容器时顶部被裁掉，最新一行留在底部。 */
.thinking-inner {
  display: flex;
  align-items: flex-end;
  height: 100%;
  overflow: hidden;
}
.thinking-tail {
  width: 100%;
  font-size: 1em;
  line-height: var(--tline-h);
  /* 终端黑版思考为白、白版为黑；其它主题等于原来的 --text-2 */
  color: var(--term-think);
  white-space: pre-wrap;
  word-break: break-word;
}

/* ── 全文（三层：wrap → inner → body）── */
.body-wrap {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows .22s cubic-bezier(.22,.68,.19,1);
}
.body-wrap.open { grid-template-rows: 1fr; }
/* 流式中给更短的过渡：内容每帧都在长，长过渡会一直"追不上"；
   但完全不过渡又会让点击像没反应（1.5.4o5 第 2 项）。 */
.body-wrap.streaming { transition-duration: .12s; }
.body-wrap:not(.animate) { transition: none; }
.body-wrap:not(.open):not(.animate) { display: none; }
/* 内层负责裁剪；间距与背景都在内层的 body 上，收起后不会残留 4px 空隙。 */
.body-inner { min-height: 0; overflow: hidden; }
.body {
  margin: 4px 0 0 6px; padding: 9px 13px;
  border-left: 2px solid var(--blue-border);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  background: var(--fill);
  font-size: 13px; line-height: 1.7; color: var(--text-2);
  white-space: pre-wrap; word-break: break-word;
}
/* 还在长的时候展开：给上限自己滚，别把整页顶下去 */
.body-wrap.streaming .body { max-height: 42vh; overflow-y: auto; }
</style>
