<script setup lang="ts">
/**
 * 全局用量统计（1.5.4 第 7 项）。
 *
 * 数据源只有一处：引擎的 `~/.coomi/usage/ledger.jsonl`，由它在每一轮
 * `TurnCompleted` 时追加一行（时间、厂商、模型、输入/输出/缓存 token、耗时）。
 * 页面不做任何本地估算 —— 没有记录就是 0，而不是按字数猜。
 *
 * 这一版新增：
 *   · 按模型汇总（哪个模型在烧 token，一眼能看出来）；
 *   · 按天柱状图（最近 N 天，纯 SVG，不引第三方图表库）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { apiGet } from '@/bridge/http'
import { goBack } from '@/bridge/navigation'
import PageHead from '@/components/PageHead.vue'

interface LedgerRecord {
  timestamp_ms: number
  reasoning_effort: string
  provider_id?: string
  model?: string
  input_tokens: number
  cached_input_tokens?: number
  output_tokens: number
  total_tokens: number
  elapsed_ms: number
}
interface ModelRow {
  provider_id: string
  model: string
  input_tokens: number
  cached_input_tokens: number
  output_tokens: number
  total_tokens: number
  elapsed_ms: number
  requests: number
}
interface DayRow {
  date: string
  input_tokens: number
  output_tokens: number
  total_tokens: number
  requests: number
}
interface UsagePayload {
  input_tokens: number
  cached_input_tokens: number
  output_tokens: number
  total_tokens: number
  requests: number
  by_model?: ModelRow[]
  by_day?: DayRow[]
  records: LedgerRecord[]
}

const router = useRouter()
const data = ref<UsagePayload>({
  input_tokens: 0, cached_input_tokens: 0, output_tokens: 0, total_tokens: 0, requests: 0, records: [],
})
const error = ref('')
const busy = ref(false)
/** 时间范围：只保留最近 N 天，避免老记录把图挤成一条线。 */
const rangeDays = ref(7)
const RANGE_OPTIONS = [7, 30, 90]

function fmt(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + 'M'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
  return String(n)
}
function time(ms: number): string {
  return new Date(ms).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

async function query() {
  busy.value = true
  error.value = ''
  const to = Date.now()
  const from = to - rangeDays.value * 86_400_000
  try {
    const payload = await apiGet<UsagePayload>(`/api/usage?from=${from}&to=${to}`)
    data.value = {
      ...payload,
      by_model: payload.by_model ?? [],
      by_day: payload.by_day ?? [],
      records: payload.records ?? [],
    }
  } catch (cause) {
    error.value = String(cause)
  } finally {
    busy.value = false
  }
}

function setRange(days: number) {
  if (rangeDays.value === days) return
  rangeDays.value = days
  void query()
}

/** 按天柱状图：补齐没有记录的日子（0 也要占一个位置），否则日期会挤在一起。 */
const chart = computed(() => {
  const byDate = new Map<string, DayRow>()
  for (const row of data.value.by_day ?? []) byDate.set(row.date, row)
  const days: { label: string; value: number; requests: number; full: string }[] = []
  const today = new Date()
  for (let offset = rangeDays.value - 1; offset >= 0; offset--) {
    const date = new Date(today.getTime() - offset * 86_400_000)
    const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
    const row = byDate.get(key)
    days.push({
      label: `${date.getMonth() + 1}/${date.getDate()}`,
      value: row?.total_tokens ?? 0,
      requests: row?.requests ?? 0,
      full: key,
    })
  }
  const max = Math.max(1, ...days.map(day => day.value))
  return { days, max }
})

/** 每根柱子的高度百分比；有值的最小 4%，避免"用过但看不出"的一根线。 */
function barHeight(value: number): string {
  if (value <= 0) return '0%'
  return `${Math.max(4, (value / chart.value.max) * 100).toFixed(1)}%`
}

const modelRows = computed(() => [...(data.value.by_model ?? [])].sort((a, b) => b.total_tokens - a.total_tokens))
const recent = computed(() => [...data.value.records].reverse().slice(0, 60))

onMounted(query)
</script>

<template>
  <div class="page">
    <PageHead title="用量统计" @back="goBack(router, 'dashboard')" />
    <main class="body">
      <div class="range">
        <button
          v-for="days in RANGE_OPTIONS" :key="days"
          class="range-btn" :class="{ on: rangeDays === days }"
          @click="setRange(days)"
        >{{ days }} 天</button>
        <button class="range-refresh" :disabled="busy" @click="query">{{ busy ? '读取中…' : '刷新' }}</button>
      </div>
      <p v-if="error" class="error">{{ error }}</p>

      <section class="cards">
        <div><span>总 Token</span><strong>{{ fmt(data.total_tokens) }}</strong></div>
        <div><span>输入</span><strong>{{ fmt(data.input_tokens) }}</strong></div>
        <div><span>输出</span><strong>{{ fmt(data.output_tokens) }}</strong></div>
        <div><span>请求次数</span><strong>{{ data.requests.toLocaleString('zh-CN') }}</strong></div>
      </section>

      <p class="sec-label">按天用量</p>
      <section class="chart-card">
        <div v-if="data.requests === 0" class="empty">这个时间范围内还没有调用记录。</div>
        <div v-else class="chart">
          <div v-for="day in chart.days" :key="day.full" class="bar-col" :title="`${day.full} · ${fmt(day.value)} tokens · ${day.requests} 次`">
            <div class="bar-track"><i :style="{ height: barHeight(day.value) }" :class="{ zero: day.value === 0 }" /></div>
            <span>{{ day.label }}</span>
          </div>
        </div>
      </section>

      <p class="sec-label">按模型统计</p>
      <section class="model-list">
        <p v-if="modelRows.length === 0" class="empty">还没有模型维度的数据（旧版本流水没有记录模型名）。</p>
        <div v-for="row in modelRows" :key="row.provider_id + ':' + row.model" class="model-row">
          <div class="model-head">
            <b>{{ row.model }}</b>
            <small>{{ row.provider_id }}</small>
          </div>
          <div class="model-stats">
            <span>总 <strong>{{ fmt(row.total_tokens) }}</strong></span>
            <span>入 <strong>{{ fmt(row.input_tokens) }}</strong></span>
            <span>出 <strong>{{ fmt(row.output_tokens) }}</strong></span>
            <span>{{ row.requests }} 次</span>
          </div>
        </div>
      </section>

      <p class="sec-label">最近记录</p>
      <section class="group">
        <p v-if="recent.length === 0" class="empty">暂无记录</p>
        <div v-for="(record, index) in recent" :key="index" class="row">
          <span class="time">{{ time(record.timestamp_ms) }}</span>
          <span class="model">{{ record.model || '未记录模型' }}</span>
          <span class="effort">{{ record.reasoning_effort }}</span>
          <span class="tokens">{{ fmt(record.total_tokens) }} tokens</span>
        </div>
      </section>
      <p class="footnote">
        数据来自引擎的本地流水文件 <code>~/.coomi/usage/ledger.jsonl</code>；只统计这个版本之后真实发生过的请求。
      </p>
    </main>
  </div>
</template>

<style scoped>
.page { display: flex; flex-direction: column; height: 100%; min-height: 0; background: var(--page); }
.body { flex: 1; min-height: 0; overflow-y: auto; padding: 14px 12px calc(var(--safe-bottom) + 24px); display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.body > * { flex: 0 0 auto; }
.range { display: flex; gap: 7px; align-items: center; flex-wrap: wrap; }
.range-btn, .range-refresh {
  min-height: 32px; padding: 0 12px; border: 0; border-radius: 999px;
  background: var(--fill); color: var(--text-2); font-size: 12.5px; font-weight: 600;
}
.range-btn.on { background: var(--blue-soft); color: var(--blue); }
.range-refresh { margin-left: auto; background: var(--blue); color: #fff; }
.range-refresh:disabled { opacity: .6; }
.cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.cards div { padding: 12px; border-radius: var(--r-card); background: var(--bg); box-shadow: var(--shadow-1); min-width: 0; }
.cards span { display: block; color: var(--text-3); font-size: 11px; }
.cards strong { display: block; margin-top: 4px; color: var(--text); font-size: 19px; font-variant-numeric: tabular-nums; }
.sec-label { margin: 8px 0 0; color: var(--text-3); font-size: 12px; font-weight: 700; }
.chart-card { padding: 12px 10px 8px; border-radius: var(--r-card); background: var(--bg); box-shadow: var(--shadow-1); }
.chart { display: flex; align-items: flex-end; gap: 4px; height: 140px; overflow-x: auto; padding-bottom: 2px; }
.bar-col { flex: 1 0 16px; min-width: 16px; display: flex; flex-direction: column; align-items: center; gap: 5px; height: 100%; }
.bar-track { flex: 1; width: 100%; display: flex; align-items: flex-end; border-radius: 6px; background: var(--fill); overflow: hidden; }
.bar-track i { display: block; width: 100%; border-radius: 6px 6px 0 0; background: linear-gradient(180deg, var(--blue) 0%, color-mix(in srgb, var(--blue) 55%, transparent) 100%); }
.bar-track i.zero { background: transparent; }
.bar-col span { color: var(--text-3); font-size: 9.5px; white-space: nowrap; }
.model-list { display: flex; flex-direction: column; gap: 8px; }
.model-row { padding: 11px 12px; border-radius: var(--r-card); background: var(--bg); box-shadow: var(--shadow-1); min-width: 0; }
.model-head { display: flex; align-items: baseline; gap: 6px; min-width: 0; }
.model-head b { color: var(--text); font-size: 13.5px; overflow-wrap: anywhere; }
.model-head small { color: var(--text-3); font-size: 10.5px; overflow-wrap: anywhere; }
.model-stats { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 6px; color: var(--text-3); font-size: 11.5px; }
.model-stats strong { color: var(--text-2); font-variant-numeric: tabular-nums; }
.group { border-radius: var(--r-card); background: var(--bg); box-shadow: var(--shadow-1); overflow: hidden; }
.row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 8px; padding: 10px 12px; border-bottom: 1px solid var(--border); font-size: 11.8px; align-items: baseline; }
.row:last-child { border-bottom: 0; }
.row .time { color: var(--text-3); }
.row .model { color: var(--text-2); overflow-wrap: anywhere; }
.row .effort { color: var(--blue); }
.row .tokens { color: var(--text-3); font-variant-numeric: tabular-nums; text-align: right; }
.empty { padding: 14px 12px; color: var(--text-3); font-size: 12.5px; }
.error { margin: 0; color: var(--danger); font-size: 12px; }
.footnote { margin: 4px 0 0; color: var(--text-3); font-size: 11px; line-height: 1.55; }
.footnote code { font-family: var(--font-mono); font-size: 10.5px; }
</style>
