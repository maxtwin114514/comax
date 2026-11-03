<script setup lang="ts">
/**
 * 过程块：把一轮里**多段**思考 + 多次工具调用折叠到同一个标题下。
 *
 * 为什么需要它：长任务里模型会「思考→调工具→再思考→再调工具」好几轮，
 * 时间线上就散成十几个块，把整屏冲满。这里把它们收在一个可折叠的过程块里，
 * 标题给出「已思考 N 段 · 使用工具 M 次」，点开才铺开细节；内部各段仍复用
 * ReasoningBlock / ToolGroup，不重做它们的样式。
 *
 * 展开/收回用 `grid-template-rows: 0fr ↔ 1fr` 过渡：不需要量高度，内容再长也能
 * 平滑收放，被打断时浏览器从当前计算值继续。
 *
 * 1.5.4o4 第 4 项：原来「运行中」禁止收起（toggle 里有 `if (live && open) return`），
 * 用户点了没反应，被理解成「思考过程中点展开和收起无反应」。现在点击永远生效，
 * 只在「新一轮开始」时自动展开一次，之后用户说了算。
 */
import { computed, ref, watch } from 'vue'
import type { ProcessBlockData } from '@/utils/chatTimeline'
import CoomiIcon from './CoomiIcon.vue'
import ReasoningBlock from './ReasoningBlock.vue'
import ToolGroup from './ToolGroup.vue'

const props = defineProps<{ block: ProcessBlockData; live?: boolean }>()

const open = ref(props.live === true)
/**
 * 内容「一旦挂上就不再摘」—— 只有首次需要时才挂。
 *
 * 1.5.4o5 第 1 项：旧版在收起后 `v-if` 把内容摘掉，摘的那一刻高度归零但
 * margin/padding 残留，于是「收回思考后会空出来一点」；重新展开还要重建整棵
 * 子树，观感上像"卡一下"。现在挂住不收，收起只收高度，空隙是干干净净的 0。
 *
 * 为什么不是无条件挂：历史会话里几十个过程块全挂上会让首屏 DOM 暴涨。
 * 这里保持「没展开过就不挂」，一展开就一直留着。
 */
const mounted = ref(props.live === true)

watch(() => props.live, (value, previous) => {
  if (value) {
    // 新一轮开始：自动展开一次，让用户看到进度。
    open.value = true
    mounted.value = true
    return
  }
  // 这一轮结束：折叠回一行摘要。用户之后手动展开不会被再强制合上
  // （只有 live 由真变假这一个时刻会收起一次）。
  if (previous) open.value = false
}, { immediate: true })

/** 用户手动开关：任何时候都生效，运行中也一样。 */
function toggle() {
  open.value = !open.value
  if (open.value) mounted.value = true
}

/** 最后一段思考的下标：只有它才是「正在写」的那段，其余都已写完。 */
const lastReasoningIndex = computed(() => {
  for (let i = props.block.stages.length - 1; i >= 0; i -= 1) {
    if (props.block.stages[i].s === 'reasoning') return i
  }
  return -1
})

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
</script>

<template>
  <div class="pblock">
    <button type="button" class="phead" :aria-expanded="open" @click="toggle">
      <span class="picon">
        <CoomiIcon name="sparkle" :size="14" :class="{ pulse: live }" />
      </span>
      <span class="psum">{{ summary }}</span>
      <CoomiIcon name="chevronRight" :size="13" class="chev" :class="{ open }" />
    </button>

    <div class="pwrap" :class="{ open, live }">
      <div class="pinner">
        <div v-if="mounted" class="pbody">
          <template v-for="(stage, index) in block.stages" :key="index">
            <ReasoningBlock
              v-if="stage.s === 'reasoning'"
              :block="stage.item"
              :live="live && index === lastReasoningIndex"
            />
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
/* 1.5.4o5 第 1 项：真正的「收回后空出一点」在这里。
   旧版 `.pblock` 用 `gap: 6px` 分隔标题与内容 —— gap 是**容器**属性，
   内容收到 0 高度后那 6px 依然存在，于是每次收回都在下面留一条缝。
   现在把间距挪到内容块自己的 margin-top 上，并随开合归零。 */
.pblock { display: flex; flex-direction: column; gap: 0; }
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

.pwrap {
  display: grid; grid-template-rows: 0fr;
  margin-top: 0; transition: margin-top .26s cubic-bezier(.22,.68,.19,1);
}
.pwrap.open { grid-template-rows: 1fr; margin-top: 6px; }
/* 运行中内容每个 token 都在长，过渡会让它一直「追不上」；结束后的开合才走过渡。 */
.pwrap:not(.live) { transition: grid-template-rows .26s cubic-bezier(.22,.68,.19,1), margin-top .26s cubic-bezier(.22,.68,.19,1); }
.pwrap.live { transition: margin-top .12s ease; }
.pinner { min-height: 0; overflow: hidden; }
/* 收起时高度必须真的到 0：内层的 padding/margin 都要算在 body 里，不能留在 pinner 上。 */
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
