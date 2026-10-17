<script setup lang="ts">
/**
 * 状态条：只在有话可说的时候出现（忙 / 有用量 / 正在重连）。
 * 停止按钮归输入区的圆形按钮管，这里不重复放一个。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useConnectionStore } from '@/stores/connection'
import CoomiIcon from './CoomiIcon.vue'

const session = useSessionStore()
const connection = useConnectionStore()

/** 本轮输出速率（token/s），后端在 usage_update 里附带，输出过程中持续刷新。 */
const turnRate = computed(() => {
  const value = session.usage?.turnRateTps ?? 0
  return value > 0 ? `${value.toFixed(1)} tok/s` : ''
})

/** 本轮已耗时（毫秒 → 秒/分）。中途显示的是实时经过时间，结束后显示本轮总耗时。 */
const turnElapsed = computed(() => {
  const ms = session.usage?.turnElapsedMs ?? 0
  if (ms <= 0) return ''
  if (ms < 10000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.round(ms / 60000)}m ${Math.round((ms % 60000) / 1000)}s`
})

const THINKING_LABELS = [
  'Comax 正在海底捞针',
  'Comax 数羊把自己数晕了',
  'Comax 正在热身',
  'Comax 被咖啡烫到了',
  'Comax 正在猛猛翻字典',
  'Comax 正在吵架',
  'Comax 正在悄悄摸鱼',
  'Comax 在假装思考中',
  'Comax 正在翻记忆库',
  'Comax 尝试计算宇宙终极答案中',
  'Comax 在捣鼓中',
  'Comax 在乖乖干活',
  'Comax 想睡觉',
  'Comax 眼巴巴等着你夸',
  'Comax 正在编纂借口',
  'Comax 的神经网络冒烟了',
  'Comax 偷瞄了一眼隔壁的答案',
  'Comax 盘腿打坐中',
  'Comax 打了个盹，然后被自己吓醒了',
  'Comax 正在认真比对数据',
  'Comax 被自己的逻辑绕晕了',
  'Comax 偷偷打了个哈欠',
  'Comax 发现线索断了',
  'Comax 在重组答案中',
  'Comax 忘记刚才算到哪了',
  'Comax 正在跟顽固问题激斗',
  'Comax 觉得自己可能错了，重查中',
  'Comax 感觉有人在催，手忙脚乱了一下',
  'Comax 在后台翻箱倒柜',
  'Comax 正在恢复答案中',
  'Comax 想偷懒',
  'Comax 搜索时迷路了',
  'Comax 正在编一段漂亮的解释',
  'Comax 怀疑出问题了，正在自检',
  'Comax 把所有可能都列了一遍，挨个排除',
  'Comax 准备重头再来',
] as const

const thinkingLabel = ref('')
let thinkingTimer: ReturnType<typeof setInterval> | null = null

function rotateThinkingLabel() {
  let next = thinkingLabel.value
  while (next === thinkingLabel.value) {
    next = THINKING_LABELS[Math.floor(Math.random() * THINKING_LABELS.length)]
  }
  thinkingLabel.value = next
}

function stopThinkingRotation() {
  if (thinkingTimer) clearInterval(thinkingTimer)
  thinkingTimer = null
}

watch(() => session.runState, state => {
  stopThinkingRotation()
  if (state !== 'thinking') return
  rotateThinkingLabel()
  thinkingTimer = setInterval(rotateThinkingLabel, 3500)
}, { immediate: true })

onBeforeUnmount(stopThinkingRotation)

const runLabel = computed(() => {
  switch (session.runState) {
    case 'syncing': return '同步中'
    case 'thinking': return thinkingLabel.value
    case 'executing': return '执行中'
    case 'awaiting_approval': return '等你授权'
    case 'awaiting_question': return '等你回答'
    default: return ''
  }
})

</script>

<template>
  <div v-if="session.isBusy || connection.retryMessage" class="sbar">
    <div v-if="connection.retryMessage" class="retry">
      <CoomiIcon name="alert" :size="14" />
      <span>{{ connection.retryMessage }}</span>
    </div>
    <div class="row">
      <span v-if="session.isBusy" class="dots"><i /><i /><i /></span>
      <span
        v-if="runLabel"
        class="run"
        :class="{ 'thinking-shimmer': session.runState === 'thinking' }"
      >{{ runLabel }}</span>
      <span class="gap" />
      <span v-if="turnRate" class="stat">{{ turnRate }}</span>
      <span v-if="turnElapsed" class="stat">{{ turnElapsed }}</span>
    </div>
  </div>
</template>

<style scoped>
.sbar { padding: 2px 16px 4px; background: var(--bg); }
.retry {
  display: flex; align-items: center; gap: 6px; margin-bottom: 3px;
  font-size: 12px; color: var(--orange);
}
.row { display: flex; align-items: center; gap: 8px; min-height: 18px; }
.gap { flex: 1; }
.run { min-width: 0; overflow: hidden; color: var(--text-2); font-size: 12.5px; text-overflow: ellipsis; white-space: nowrap; }
.run.thinking-shimmer {
  color: var(--blue);
  background-image: linear-gradient(90deg, var(--blue) 20%, var(--blue-press) 48%, var(--blue) 76%);
  background-image: linear-gradient(
    90deg,
    var(--blue) 20%,
    color-mix(in srgb, var(--blue), white 55%) 48%,
    var(--blue) 76%
  );
  background-position: 0 0;
  background-size: 200% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: coomi-shimmer 1.6s linear infinite;
}
.stat { color: var(--text-3); font-size: 11.5px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.dots { display: inline-flex; align-items: center; gap: 3px; }
.dots i {
  width: 5px; height: 5px; border-radius: 50%; background: var(--blue);
  animation: bounce 1.2s ease-in-out infinite;
}
.dots i:nth-child(2) { animation-delay: .15s; }
.dots i:nth-child(3) { animation-delay: .3s; }
@keyframes bounce {
  0%, 60%, 100% { opacity: .25; transform: none; }
  30% { opacity: 1; transform: translateY(-3px); }
}
@media (prefers-reduced-motion: reduce) {
  .run.thinking-shimmer {
    background: none;
    -webkit-text-fill-color: var(--blue);
    animation: none;
  }
}
</style>
