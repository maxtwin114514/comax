<script setup lang="ts">
import type { TimelineBlockItem } from '@/utils/chatTimeline'
import MessageBubble from '@/components/MessageBubble.vue'
import ProcessBlock from '@/components/ProcessBlock.vue'
import ReasoningBlock from '@/components/ReasoningBlock.vue'
import NoticeItem from '@/components/NoticeItem.vue'

defineProps<{ block: TimelineBlockItem; live?: boolean }>()
</script>

<template>
  <!-- 过程块：多段思考 + 多次工具调用折叠在一起，回答气泡留在块外 -->
  <div v-if="block.t === 'process'" class="process">
    <ProcessBlock v-if="block.block.stages.length > 0" :block="block.block" :live="live" />
    <MessageBubble v-if="block.block.answer" :msg="block.block.answer" />
  </div>
  <template v-else>
    <MessageBubble
      v-if="block.item.kind === 'user' || block.item.kind === 'assistant'"
      :msg="block.item"
    />
    <ReasoningBlock v-else-if="block.item.kind === 'reasoning'" :block="block.item" />
    <NoticeItem v-else-if="block.item.kind === 'notice'" :notice="block.item" />
    <div
      v-else-if="block.item.kind === 'question' && block.item.answered"
      class="q-answered cascade"
    >
      <span class="q-label">已回答</span>
      {{ Object.values(block.item.answers ?? {}).filter(Boolean).join('；') || '已跳过' }}
    </div>
  </template>
</template>

<style scoped>
.process { display: flex; flex-direction: column; gap: 6px; }
.q-answered {
  align-self: flex-end;
  max-width: 84%;
  padding: 7px 13px;
  border-radius: var(--r-pill);
  background: var(--fill);
  color: var(--text-2);
  font-size: 12.5px;
}
.q-label { color: var(--blue); font-weight: 600; }

/*
 * ──────────────────────────────────────────────────────────────
 * 修复「工具调用 / 文字入场时闪现空出一段」
 * ──────────────────────────────────────────────────────────────
 * global.css 里既有的入场动画：
 *
 *   @keyframes coomi-cascade {
 *     from { opacity: 0; transform: translateY(8px); }
 *     to   { opacity: 1; transform: none; }
 *   }
 *   .cascade { animation: coomi-cascade 0.26s ... both; }
 *
 * 它的问题不是 fill-mode，而是**第一帧就是 opacity:0**：新块一插进文档流就
 * 占住了位置，却完全透明 —— 用户先看到一段空白（"闪现空出一段"），
 * 再看着内容淡进来。opacity 从 0 起跳的动画本质上都会有这个空档，
 * 改成 `forwards` 也救不了（第一帧照样是 0）。
 *
 * 所以这里改成：**起始就可见**（opacity 不为 0），只做向下的位移，
 * 元素从上方缓慢落到位，与它自己的调用动画同时进行 —— 不再有"先空一段"。
 * ──────────────────────────────────────────────────────────────
 */
.cascade {
  animation-name: coomi-cascade-slide;
  animation-duration: 0.32s;
  animation-timing-function: cubic-bezier(0.22, 0.68, 0.19, 1);
  animation-fill-mode: none;
  animation-delay: 0s;
}
/* 助手正文稍微带一点重量感：落位时轻微回弹，但仍然全程可见 */
.row.assistant .cascade {
  animation-name: coomi-cascade-assistant;
  animation-duration: 0.38s;
}
@keyframes coomi-cascade-slide {
  0%   { opacity: 0.65; transform: translateY(-7px); }
  100% { opacity: 1; transform: none; }
}
@keyframes coomi-cascade-assistant {
  0%   { opacity: 0.7; transform: translateY(-9px); }
  62%  { opacity: 1; transform: translateY(1px); }
  100% { opacity: 1; transform: none; }
}
@media (prefers-reduced-motion: reduce) {
  .cascade, .row.assistant .cascade { animation: none; }
}
</style>
