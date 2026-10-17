<script setup lang="ts">
/**
 * 过程块：把一轮里**多段**思考 + 多次工具调用折叠到同一个标题下。
 *
 * 为什么需要它：Coomi 式的长任务里，模型会「思考→调工具→再思考→再调工具」好几轮，
 * 时间线上就散成十几个块，把整屏冲满。上游的做法是每轮只留一个可折叠的过程块，
 * 标题给出「已思考 N 段 · 使用工具 M 次」，点开才铺开细节 —— 这里照搬这个结构，
 * 内部各段仍然复用现有的 ReasoningBlock / ToolGroup，不重做它们的样式。
 *
 * 展开/收回用 `grid-template-rows: 0fr ↔ 1fr` 过渡：不需要量高度，内容再长也能
 * 平滑收放，且被打断时浏览器从当前计算值继续。
 */
import { computed, ref, watch } from 'vue'
import type { ProcessBlockData } from '@/utils/chatTimeline'
import CoomiIcon from './CoomiIcon.vue'
import ReasoningBlock from './ReasoningBlock.vue'
import ToolGroup from './ToolGroup.vue'

const props = defineProps<{ block: ProcessBlockData; live?: boolean }>()

const open = ref(props.live === true)
/** 曾经展开过就把内容挂在 DOM 上，收回时只收高度，不再闪烁重建。 */
const mounted = ref(props.live === true)

watch(() => props.live, (value, previous) => {
  if (value) {
    open.value = true
    mounted.value = true
    return
  }
  // 思考结束：按收回动画折叠回一行摘要（用户要的是"思考结束就折叠"）。
  // 只在 live 由真变假时折叠一次，避免用户手动展开后又被强制合上。
  if (previous) open.value = false
}, { immediate: true })

const reasoningStages = computed(() => props.block.stages.filter(stage => stage.s === 'reasoning').length)
const cards = computed(() => props.block.stages.flatMap(stage => (stage.s === 'tools' ? stage.cards : [])))
const running = computed(() => cards.value.filter(card => (
  card.status === 'running' || card.status === 'starting' || card.status === 'awaiting_approval'
)).length)
const succeeded = computed(() => cards.value.filter(card => (
  card.status === 'success' || card.status === 'cache_hit'
)).length)
const failed = computed(() => cards.value.filter(card => (
  card.status === 'error' || card.status === 'cancelled'
)).length)

const summary = computed(() => {
  const parts: string[] = []
  parts.push(reasoningStages.value > 0 ? `已思考 ${reasoningStages.value} 段` : '已思考')
  if (cards.value.length > 0) parts.push(`使用工具 ${cards.value.length} 次`)
  if (props.live && running.value > 0) parts.push(`进行中 ${running.value}`)
  if (!props.live && succeeded.value > 0) parts.push(`成功 ${succeeded.value}`)
  if (!props.live && failed.value > 0) parts.push(`失败 ${failed.value}`)
  return parts.join(' · ')
})

function toggle() {
  // 运行中保持展开：正在思考/调工具的时候收起来会看不到进度。
  if (props.live && open.value) return
  open.value = !open.value
  if (open.value) mounted.value = true
}
</script>

<template>
  <div class="pblock">
    <button type="button" class="phead" :aria-expanded="open" @click="toggle">
      <span class="picon">
        <CoomiIcon v-if="live" name="sparkle" :size="14" class="pulse" />
        <CoomiIcon v-else name="sparkle" :size="14" />
      </span>
      <span class="psum">{{ summary }}</span>
      <CoomiIcon name="chevronRight" :size="13" class="chev" :class="{ open }" />
    </button>

    <div class="pwrap" :class="{ open, live }">
      <div class="pinner">
        <div v-if="mounted" class="pbody">
          <template v-for="(stage, index) in block.stages" :key="index">
            <ReasoningBlock v-if="stage.s === 'reasoning'" :block="stage.item" />
            <ToolGroup v-else-if="stage.s === 'tools'" :cards="stage.cards" />
            <div v-else-if="stage.s === 'text' && stage.item.content" class="narr">
              {{ stage.item.content }}
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pblock { display: flex; flex-direction: column; gap: 6px; }
.phead {
  display: flex; align-items: center; gap: 7px;
  width: 100%; min-height: 32px; padding: 4px 2px;
  border: 0; background: none; text-align: left;
  font-size: 13px; color: var(--text-3);
}
.picon { display: inline-flex; align-items: center; min-width: 16px; color: var(--text-3); }
.pulse { color: var(--blue); animation: coomi-blink 1.4s ease-in-out infinite; }
.psum { flex: 1; min-width: 0; font-weight: 600; }
.chev { flex-shrink: 0; transition: transform .18s cubic-bezier(.22,.68,.19,1); }
.chev.open { transform: rotate(90deg); }

.pwrap { display: grid; grid-template-rows: 0fr; }
.pwrap.open { grid-template-rows: 1fr; }
/* 运行中不做高度过渡：内容每个 token 都在长，过渡会让它一直「追不上」。
   一旦不再运行，收回/展开就走过渡。 */
.pwrap:not(.live) { transition: grid-template-rows .26s cubic-bezier(.22,.68,.19,1); }
.pwrap.live { transition: none; }
.pinner { min-height: 0; overflow: hidden; }
.pbody {
  display: flex; flex-direction: column; gap: 8px;
  margin-left: 6px; padding-left: 10px;
  border-left: 2px solid var(--border);
}
.narr {
  font-size: 12.5px; line-height: 1.7; color: var(--text-3);
  white-space: pre-wrap; word-break: break-word;
}
</style>
