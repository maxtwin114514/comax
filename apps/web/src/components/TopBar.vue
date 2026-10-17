<script setup lang="ts">
/**
 * 顶栏：汉堡 / 模型名 / 上下文用量。
 * 忙的时候底边跑一条 2px 蓝色扫光，让「正在干活」这件事在最顶层也能看见。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useConfigStore } from '@/stores/config'
import { useSessionStore } from '@/stores/session'
import { useConnectionStore } from '@/stores/connection'
import { apiGet } from '@/bridge/http'
import CoomiIcon from './CoomiIcon.vue'
import { CNY_PER_USD, costWithPrice, fmtMoney, priceFor, type ModelPrice } from '@/utils/modelPrices'
import CoomiMark from './CoomiMark.vue'
import MorphBurger from './MorphBurger.vue'

const props = defineProps<{ menuOpen?: boolean }>()
defineEmits<{ menu: [] }>()

const config = useConfigStore()
const session = useSessionStore()
const connection = useConnectionStore()
const router = useRouter()
const modelOpen = ref(false)
const usageOpen = ref(false)
const pathPickerOpen = ref(false)
const pathInput = ref('')
const pathNotice = ref('')
const pathQuickOptions = computed(() => session.cwd ? [session.cwd] : [])
const showSessionBrand = computed(() => session.timeline.length > 0)
const settlingBrand = ref(false)
const enteringBrand = ref(false)
let settleTimer: ReturnType<typeof setTimeout> | null = null
let enterTimer: ReturnType<typeof setTimeout> | null = null
watch(() => session.timeline.length, (n, prev) => {
  if (prev === 0 && n > 0) {
    enteringBrand.value = true
    if (enterTimer) clearTimeout(enterTimer)
    enterTimer = setTimeout(() => { enteringBrand.value = false; enterTimer = null }, 1200)
  }
})
const activeProviderId = ref('')
const modelGroups = computed(() => [...config.providers]
  .filter(provider => provider.models.length > 0)
  .sort((a, b) => Number(b.id === config.activeId) - Number(a.id === config.activeId))
  .map(provider => ({
    id: provider.id,
    label: provider.name,
    items: provider.models.map(model => ({ providerId: provider.id, provider: provider.name, model })),
  })))
const activeModelGroup = computed(() => modelGroups.value.find(group => group.id === activeProviderId.value) ?? modelGroups.value[0] ?? { id:'', label:'模型厂商', items:[] })
const usagePercent = computed(() => Math.min(100, Math.max(0, Math.round((session.usage?.contextRatio ?? 0) * 100))))
const usageStroke = computed(() => `${usagePercent.value} ${100 - usagePercent.value}`)
const effortLabels = { auto: '自动', low: '低', medium: '中', high: '高', xhigh: '超高' } as const
const categoryLabels = { system_tools: '系统工具', messages: '消息', skills: '技能', mcp_tools: 'MCP 工具', system_prompt: '系统提示', other: '其他' } as const
const categoryTotal = computed(() => Object.values(session.usage?.contextCategories ?? {}).reduce((sum, value) => sum + (value ?? 0), 0))
function categoryPercent(value: number | undefined): string {
  return categoryTotal.value > 0 ? `${((value ?? 0) / categoryTotal.value * 100).toFixed(1)}%` : '--'
}

/**
 * 上下文构成分段（右上下文圆环 + 图例 + 明细共用同一份数据）。
 *
 * 参照上游 1.6.x 的「上下文用量」面板：
 *   · 各分段按 token 降序，最多 5 段 + 「其他」合并；
 *   · 百分比以**上下文窗口**为分母（窗口未知时退回各段之和），
 *     这样「1.2k / 128k」的环看起来才是真实的占用比例；
 *   · offset 是累计百分比取负，SVG 里 dasharray 的起点因此首尾相接。
 */
const CATEGORY_COLORS = ['#5b6cff', '#14b8a6', '#f59e0b', '#f43f5e', '#8b5cf6', '#0ea5e9']
const usageSegments = computed(() => {
  const categories = session.usage?.contextCategories ?? {}
  const window = session.usage?.contextWindow ?? 0
  const total = Object.values(categories).reduce((sum, value) => sum + (value ?? 0), 0)
  const denominator = window > 0 ? window : total
  if (!denominator) return []
  let cumulative = 0
  const entries = Object.entries(categories)
    .filter(([, tokens]) => (tokens ?? 0) > 0)
    .sort((a, b) => (b[1] ?? 0) - (a[1] ?? 0))
  const merged = entries.length > 6
    ? [...entries.slice(0, 5), ['other', entries.slice(5).reduce((sum, [, tokens]) => sum + (tokens ?? 0), 0)] as const]
    : entries
  return merged.map(([key, tokens], index) => {
    const percent = Math.min(100, ((tokens ?? 0) / denominator) * 100)
    const segment = {
      key,
      label: (categoryLabels as Record<string, string>)[key] ?? key,
      tokens: tokens ?? 0,
      percent,
      percentText: `${percent.toFixed(1)}%`,
      color: CATEGORY_COLORS[index % CATEGORY_COLORS.length],
      offset: -cumulative,
    }
    cumulative += percent
    return segment
  })
})
const contextLeft = computed(() => Math.max(0, (session.usage?.contextWindow ?? 0) - (session.usage?.contextUsed ?? 0)))
const messageCount = computed(() => session.timeline.filter(item => item.kind === 'user' || item.kind === 'assistant').length)
const toolElapsedMs = computed(() => session.timeline.reduce((sum, item) => (item.kind === 'tool' ? sum + (item.elapsed ?? 0) : sum), 0))
const rateText = computed(() => {
  const usage = session.usage
  if (!usage) return '--'
  const rate = usage.turnRateTps || (usage.turnElapsedMs > 0 ? usage.turnOutputTokens / (usage.turnElapsedMs / 1000) : 0)
  return rate > 0 ? `${rate.toFixed(1)} tok/s` : '--'
})

function formatTokens(value: number): string {
  if (value >= 1000000) return (value / 1000000).toFixed(1) + 'M'
  if (value >= 1000) return (value / 1000).toFixed(1) + 'k'
  return String(value)
}
function formatPercent(value: number | null | undefined): string {
  return value == null ? '暂无缓存数据' : `${(value * 100).toFixed(1)}%`
}
function formatDuration(value: number | null | undefined): string {
  if (value == null) return '--'
  return value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${value}ms`
}

function choose(providerId: string, model: string) {
  session.selectModel(providerId, model)
  modelOpen.value = false
}

// ── 运行环境（上下文环卡片底部徽标，来自 /api/runtime/doctor 真检测）──
interface RuntimeDoctor {
  runtime: { backend?: string; status?: string; active_version?: string | null; error?: string | null }
  facts?: {
    backend?: string; sh?: boolean; python?: string | null; git?: string | null
    node?: string | null; curl?: string | null; workspace?: boolean; tmp_writable?: boolean
  } | null
  termux_available?: boolean
}
const runtimeInfo = ref<RuntimeDoctor | null>(null)
const envBadgeClass = computed(() => {
  const runtime = runtimeInfo.value?.runtime
  if (runtime?.status === 'ready' && runtimeInfo.value?.facts?.sh) return 'ok'
  if (runtime?.status === 'ready') return 'warn'
  if (runtimeInfo.value?.termux_available) return 'warn'
  return 'down'
})
const envBadgeLabel = computed(() => {
  const doctor = runtimeInfo.value
  const runtime = doctor?.runtime
  if (runtime?.status === 'ready') {
    return doctor?.facts?.sh ? 'Debian 12 · proot' : '环境异常'
  }
  if (doctor?.termux_available) return 'Termux 降级'
  return '运行环境未就绪'
})
const envDetail = computed(() => {
  const facts = runtimeInfo.value?.facts
  if (!facts) return ''
  const parts: string[] = []
  if (facts.python) parts.push(`python ${facts.python.replace(/^Python /, '')}`)
  if (facts.git) parts.push(`git ${facts.git.replace(/^git version /, '')}`)
  if (facts.node) parts.push(`node ${facts.node}`)
  if (facts.workspace !== false) parts.push('/workspace ✓')
  return parts.join(' · ')
})
onMounted(async () => {
  try {
    runtimeInfo.value = await apiGet<RuntimeDoctor>('/api/runtime/doctor')
  } catch {
    runtimeInfo.value = null
  }
})

watch(() => session.isBusy, busy => {
  if (busy) {
    settlingBrand.value = false
    if (settleTimer) { clearTimeout(settleTimer); settleTimer = null }
    return
  }
  if (!showSessionBrand.value) return
  settlingBrand.value = true
  if (settleTimer) clearTimeout(settleTimer)
  settleTimer = setTimeout(() => { settlingBrand.value = false; settleTimer = null }, 1000)
})

function toggleModel() {
  modelOpen.value = !modelOpen.value
  balanceOpen.value = false
  usageOpen.value = false
  if (modelOpen.value) {
    const selected = modelGroups.value.find(group => group.items.some(item => (
      item.providerId === config.currentProviderId && item.model === config.currentModel
    )))
    activeProviderId.value = selected?.id ?? modelGroups.value[0]?.id ?? ''
  }
}

function toggleUsage() {
  usageOpen.value = !usageOpen.value
  modelOpen.value = false
  balanceOpen.value = false
}

// ── 会话标记路径（第三批 5：绑定为会话执行目录）──
function openPathPicker() {
  pathInput.value = session.cwd || ''
  pathNotice.value = ''
  pathPickerOpen.value = true
  usageOpen.value = false
}

function pickPath(path: string) {
  pathInput.value = path
}

async function savePath() {
  const path = pathInput.value.trim()
  if (!path) return
  const ok = await session.setSessionCwd(path)
  pathNotice.value = ok ? '已设置，后续对话将在此目录执行' : '设置失败：路径不存在或引擎不可用'
  if (ok) setTimeout(() => { pathPickerOpen.value = false }, 900)
}

function browseInFileManager() {
  pathPickerOpen.value = false
  router.push('/files')
}

// Currency and prices come from the currently selected session provider, not global active config.
const balanceOpen = ref(false)
const balances = ref<Array<{ currency: string; amount: number }>>([])
const balanceLoading = ref(false), balanceError = ref('')
const livePrice = ref<ModelPrice | null>(null)
const priceLoading = ref(false)
const modelUnit = computed(() => livePrice.value ?? priceFor(config.currentModel))
interface BillingRecord { turn_id: string; provider_id: string; model: string; input_tokens: number; cached_input_tokens: number; output_tokens: number }
const billingRecords = ref<BillingRecord[]>([])
const billingNote = ref(''), billingError = ref('')
let fetchVersion = 0
async function fetchBalance() {
  const version = ++fetchVersion, provider = config.currentProviderId, model = config.currentModel, id = session.sessionId
  balanceLoading.value = true; priceLoading.value = true; balanceError.value = ''; billingError.value = ''
  const query = `provider_id=${encodeURIComponent(provider)}&model=${encodeURIComponent(model)}`
  await Promise.all([
    apiGet<{ ok: boolean; balances: Array<{ currency: string; amount: number }> }>(`/api/balance?${query}`)
      .then(data => { if (version===fetchVersion) balances.value = data.balances ?? [] })
      .catch(e => { if (version===fetchVersion) { balances.value=[]; balanceError.value=String(e) } })
      .finally(() => {if(version===fetchVersion) balanceLoading.value=false}),
    apiGet<ModelPrice>(`/api/model-pricing?${query}`)
      .then(data => {if(version===fetchVersion) livePrice.value=data})
      .catch(() => {if(version===fetchVersion) livePrice.value=null})
      .finally(() => {if(version===fetchVersion) priceLoading.value=false}),
    apiGet<{ records: BillingRecord[]; note: string }>(`/api/sessions/${id}/billing`)
      .then(data => {if(version===fetchVersion) {billingRecords.value=data.records;billingNote.value=data.note}})
      .catch(e => {if(version===fetchVersion) {billingRecords.value=[];billingError.value=String(e)}}),
  ])
}
function toggleBalance() { balanceOpen.value=!balanceOpen.value; usageOpen.value=false; modelOpen.value=false; if(balanceOpen.value) void fetchBalance() }
function totals(records: BillingRecord[]): { amounts: Record<string,number>; unknown: number } {
  const amounts:Record<string,number>={};let unknown=0
  for(const record of records) {
    const p = record.model===config.currentModel && record.provider_id===config.currentProviderId ? modelUnit.value : priceFor(record.model)
    const cost=costWithPrice(p,record.input_tokens,record.output_tokens,record.cached_input_tokens)
    if(cost==null||!p) {unknown++;continue}
    // 统一按人民币结算：USD 单价 × 汇率，其余币种原样保留。
    const cny = p.currency === 'USD' ? cost*CNY_PER_USD : cost
    amounts['CNY']=(amounts['CNY']??0)+cny
  }
  return {amounts,unknown}
}
const sessionCost = computed(() => totals(billingRecords.value))
const turnCost = computed(() => {
  const last=billingRecords.value[billingRecords.value.length - 1]?.turn_id
  return totals(billingRecords.value.filter(r=>r.turn_id===last))
})
watch([()=>config.currentProviderId,()=>config.currentModel,()=>session.sessionId],()=>{livePrice.value=null;balances.value=[];billingRecords.value=[];if(balanceOpen.value)void fetchBalance()})
watch(()=>session.runState,(v)=>{if(v==='idle'&&balanceOpen.value)void fetchBalance()})
</script>

<template>
  <header class="topbar">
    <MorphBurger :open="props.menuOpen ?? false" @toggle="$emit('menu')" />

    <button class="center" :aria-expanded="modelOpen" @click="toggleModel">
      <span class="model">{{ config.currentModel }}</span>
        <span role="button" tabindex="0" v-if="session.mode === 'life'" class="life-orbit" aria-label="查看数字生命统计" title="查看生命统计" @click.stop="session.toggleLifeStats()">
          <i class="orbit outer" /><i class="orbit inner" />
        </span>
      <CoomiMark v-if="showSessionBrand" :size="22" class="session-brand" :class="{ spinning: session.isBusy, settling: settlingBrand, enter: enteringBrand }" />
      <span v-if="connection.demo" class="demo">演示</span>
      <span v-if="config.planMode" class="plan">计划</span>
      <CoomiIcon name="chevronDown" :size="13" class="caret" />
    </button>

    <button v-if="modelOpen" class="model-scrim" aria-label="关闭模型选择" @click="modelOpen = false" />
    <div v-if="modelOpen" class="model-menu">
      <div class="model-tabs" role="tablist" aria-label="模型厂商">
        <button
          v-for="group in modelGroups"
          :key="group.id"
          role="tab"
          :aria-selected="activeProviderId === group.id"
          :class="{ active: activeProviderId === group.id }"
          @click="activeProviderId = group.id"
        >{{ group.label }}</button>
      </div>
      <section class="model-list" role="tabpanel">
        <button
          v-for="item in activeModelGroup.items" :key="item.providerId + ':' + item.model" class="model-row"
          :class="{ selected: item.providerId === config.currentProviderId && item.model === config.currentModel }"
          @click="choose(item.providerId, item.model)"
        >
          <span><b>{{ item.model }}</b><small>{{ item.provider }}</small></span>
          <CoomiIcon v-if="item.providerId === config.currentProviderId && item.model === config.currentModel" name="check" :size="15" />
        </button>
        <p v-if="activeModelGroup.items.length === 0" class="model-empty">该厂商暂无可用模型</p>
      </section>
    </div>
    

    <button class="usage-button" :aria-expanded="usageOpen" aria-label="上下文用量" @click="toggleUsage">
      <svg class="usage-ring" viewBox="0 0 36 36" aria-hidden="true">
        <circle class="usage-track" cx="18" cy="18" r="15" pathLength="100" />
        <template v-if="usageSegments.length">
          <circle
            v-for="seg in usageSegments" :key="seg.key" class="usage-seg"
            cx="18" cy="18" r="15" pathLength="100"
            :style="{
              stroke: seg.color,
              strokeDasharray: `${Math.max(0.8, seg.percent - 1.5)} ${100 - Math.max(0.8, seg.percent - 1.5)}`,
              strokeDashoffset: seg.offset,
            }"
          />
        </template>
        <circle v-else class="usage-value" cx="18" cy="18" r="15" pathLength="100" :stroke-dasharray="usageStroke" />
      </svg>
    </button>
      <button class="balance-button" :aria-expanded="balanceOpen" aria-label="余额与消耗" @click="toggleBalance">
        <CoomiIcon name="bolt" :size="16" />

      </button>

    <Teleport to="body">
      <button v-if="balanceOpen" class="usage-scrim" aria-label="关闭余额与计费" @click="balanceOpen=false" />
      <section v-if="balanceOpen" class="usage-menu" aria-label="余额与计费">
        <p class="usage-title">余额与计费</p>
        <div class="balance-row"><span>{{ config.currentProviderId }}</span><button @click="fetchBalance">刷新</button></div>
        <p v-if="balanceLoading" class="usage-empty">余额查询中…</p>
        <p v-else-if="balanceError" class="usage-empty">{{ balanceError }}</p>
        <div v-for="item in balances" :key="item.currency" class="balance-row"><span>账户余额 · {{ item.currency }}</span><strong>{{ fmtMoney(item.amount) }}</strong></div>
        <p class="usage-subtitle">当前模型单价 · 每百万 Token</p>
        <template v-if="modelUnit">
          <div class="balance-row"><span>输入 / 输出</span><strong>{{ modelUnit.currency }} {{ fmtMoney(modelUnit.in) }} / {{ fmtMoney(modelUnit.out) }}</strong></div>
          <div class="balance-row"><span>缓存命中输入</span><strong>{{ modelUnit.cached == null ? '未公布，按普通输入估算' : modelUnit.currency+' '+fmtMoney(modelUnit.cached) }}</strong></div>
          <p class="usage-empty">{{ modelUnit.source }} · {{ modelUnit.note || '参考单价；最终扣费以服务商账单为准' }}</p>
        </template>
        <p v-else class="usage-empty">{{ priceLoading ? '价格查询中…' : '该服务商未公布单价，且本地未收录；不会使用虚构默认价' }}</p>
        <p class="usage-subtitle">本轮消耗 · 含输入、输出、缓存</p>
        <div v-for="(cost,currency) in turnCost.amounts" :key="currency" class="balance-row"><span>{{ currency }}</span><strong>≈ {{ fmtMoney(cost) }}</strong></div>
        <p v-if="turnCost.unknown" class="usage-empty">{{ turnCost.unknown }} 次请求缺少单价，未计入估算</p>
        <p class="usage-subtitle">当前会话消耗 · 按实际调用模型累计</p>
        <div v-for="(cost,currency) in sessionCost.amounts" :key="currency" class="balance-row"><span>{{ currency }}</span><strong>≈ {{ fmtMoney(cost) }}</strong></div>
        <p v-if="sessionCost.unknown" class="usage-empty">{{ sessionCost.unknown }} 次请求缺少单价，未计入估算</p>
        <p class="usage-empty">{{ billingError || billingNote || '尚无已记录的 API 请求' }}</p>
      </section>
    <button v-if="usageOpen" class="usage-scrim" aria-label="关闭上下文数据" @click="usageOpen = false" />
    <div v-if="usageOpen" class="usage-menu">
      <p class="usage-title">上下文用量</p>
      <template v-if="session.usage">
        <!-- 顶栏那张"上下文环"的放大版：左边分段环 + 中心百分比，右边四行关键数字 -->
        <div class="uc-top">
          <div class="uc-ring">
            <svg class="uc-ring-svg grown" viewBox="0 0 36 36" aria-hidden="true">
              <circle class="usage-track" cx="18" cy="18" r="15" pathLength="100" />
              <circle
                v-for="seg in usageSegments" :key="seg.key" class="usage-seg"
                cx="18" cy="18" r="15" pathLength="100"
                :style="{
                  stroke: seg.color,
                  strokeDasharray: `${Math.max(0.8, seg.percent - 1.5)} ${100 - Math.max(0.8, seg.percent - 1.5)}`,
                  strokeDashoffset: seg.offset,
                }"
              />
            </svg>
            <div class="uc-center"><strong>{{ usagePercent }}%</strong><span>已用</span></div>
          </div>
          <div class="uc-side">
            <div class="uc-line"><span>已用</span><strong>{{ formatTokens(session.usage.contextUsed) }}</strong></div>
            <div class="uc-line"><span>剩余</span><strong>{{ formatTokens(contextLeft) }}</strong></div>
            <div class="uc-line"><span>消息数</span><strong>{{ messageCount }}</strong></div>
            <div class="uc-line"><span>主要占用</span><strong>{{ usageSegments[0]?.label ?? '--' }}</strong></div>
          </div>
        </div>

        <div v-if="usageSegments.length" class="uc-legend">
          <span v-for="seg in usageSegments" :key="seg.key" class="uc-legend-item">
            <i :style="{ background: seg.color }" />{{ seg.label }} {{ seg.percentText }}
          </span>
        </div>

        <div v-if="usageSegments.length" class="uc-details">
          <p class="usage-subtitle">分段明细</p>
          <div v-for="seg in usageSegments" :key="seg.key" class="uc-detail-row">
            <span class="uc-detail-label"><i :style="{ background: seg.color }" />{{ seg.label }}</span>
            <span class="uc-detail-val">{{ seg.percentText }} · {{ formatTokens(seg.tokens) }}</span>
          </div>
        </div>

        <div class="uc-stats">
          <div><span>本轮耗时</span><strong>{{ formatDuration(session.usage.turnElapsedMs) }}</strong></div>
          <div><span>工具累计</span><strong>{{ formatDuration(toolElapsedMs) }}</strong></div>
          <div><span>输出速率</span><strong>{{ rateText }}</strong></div>
          <div><span>会话 Token</span><strong>{{ formatTokens(session.usage.total) }}</strong></div>
        </div>

        <div class="uc-cache">
          <span>缓存命中 <strong>{{ formatPercent(session.usage.cacheHitRate) }}</strong></span>
          <span>本轮 <strong>{{ formatPercent(session.usage.turnCacheHitRate) }}</strong></span>
          <span>输出 <strong>{{ formatTokens(session.usage.output) }}</strong></span>
        </div>

        <p class="usage-subtitle">各推理强度均轮统计</p>
        <div class="effort-table">
          <div class="effort-head"><span>强度</span><span>命中</span><span>耗时</span><span>用量</span></div>
          <div v-for="(label, effort) in effortLabels" :key="effort" class="effort-row">
            <span>{{ label }}</span>
            <span>{{ formatPercent(session.usage.reasoningEfforts[effort]?.cache_hit_rate) }}</span>
            <span>{{ formatDuration(session.usage.reasoningEfforts[effort]?.average_duration_ms) }}</span>
            <span>{{ session.usage.reasoningEfforts[effort]?.average_total_tokens == null ? '--' : formatTokens(session.usage.reasoningEfforts[effort]!.average_total_tokens!) }}</span>
          </div>
        </div>
      </template>
      <p v-else class="usage-empty">此对话尚无用量数据</p>
      <div class="usage-path">
        <span>会话标记路径</span>
        <button class="path-btn" @click="openPathPicker">{{ session.cwd || '点击选择' }}</button>
      </div>
      <div v-if="runtimeInfo" class="usage-env">
        <span>运行环境</span>
        <span class="env-row">
          <em class="env-badge" :class="envBadgeClass">{{ envBadgeLabel }}</em>
          <small v-if="envDetail" class="env-detail">{{ envDetail }}</small>
        </span>
      </div>
    </div>
    </Teleport>

    <div v-if="pathPickerOpen" class="path-mask" @click="pathPickerOpen = false">
      <div class="path-sheet" @click.stop>
        <p class="path-title">会话标记路径</p>
        <p class="path-desc">绑定为当前会话的执行目录，coomi 将在此目录下工作。</p>
        <input v-model="pathInput" class="path-input" placeholder="输入运行时路径" spellcheck="false" @keyup.enter="savePath" />
        <div class="path-quick">
          <button v-for="p in pathQuickOptions" :key="p" class="chip" @click="pickPath(p)">当前工作目录</button>
          <button class="chip" @click="browseInFileManager">在文件管理器中浏览…</button>
        </div>
        <p v-if="pathNotice" class="path-notice">{{ pathNotice }}</p>
        <div class="path-actions">
          <button class="btn ghost" @click="pathPickerOpen = false">取消</button>
          <button class="btn primary" @click="savePath">设置</button>
        </div>
      </div>
    </div>

    <div v-if="session.isBusy" class="sweep"><i /></div>
  </header>
</template>

<style scoped>
.topbar {
  position: relative;
  display: flex; align-items: center; justify-content: space-between;
  min-height: 52px; padding: calc(var(--safe-top) + 6px) 8px 6px;
  background: var(--bg);
}
.model-scrim { position: fixed; inset: 0; z-index: 19; border: 0; background: rgba(0,0,0,0.3); }
.model-menu {
    position: absolute; z-index: 20; top: calc(var(--safe-top) + 49px); left: 50%;
    width: min(78vw, 300px); max-height: min(52vh, 380px); overflow-y: auto;
    transform: translateX(-50%); padding: 6px; border: 1px solid var(--border);
  border-radius: var(--r-card); background: var(--bg); box-shadow: var(--shadow-2);
  animation: menu-pop .2s cubic-bezier(.2, .9, .3, 1.2) both;
}
@keyframes menu-pop { from { opacity: 0; transform: translateX(-50%) translateY(-6px) scale(.97); } to { opacity: 1; transform: translateX(-50%) translateY(0) scale(1); } }
.model-tabs {
    display: grid; grid-template-columns: repeat(auto-fit, minmax(96px, 1fr));
    min-height: 42px; border-bottom: 1px solid var(--border);
    overflow-x: auto;
}
.model-tabs::-webkit-scrollbar { display: none; }
.model-tabs button {
    position: relative; min-width: 0; padding: 0 3px;
    color: var(--text-3); font-size: 12px; font-weight: 600;
}
.model-tabs button.active { color: var(--blue); }
.model-tabs button.active::after {
  content: ''; position: absolute; right: 18%; bottom: -1px; left: 18%;
  height: 2px; border-radius: 2px; background: var(--blue);
}
.model-list { max-height: min(43vh, 322px); overflow-y: auto; padding-top: 5px; scrollbar-width: none; }
.model-list::-webkit-scrollbar { display: none; }
.usage-scrim { position: fixed; inset: 0; z-index: 19; border: 0; background: transparent; }
.usage-menu {
  position: absolute; z-index: 20; top: calc(var(--safe-top) + 49px); right: 8px;
  width: min(92vw, 390px); max-height: min(72vh, 560px); overflow-y: auto; padding: 12px 13px;
  /* 胶囊装竖向排列：顶部小圆角、主体大圆角，像从右上角挂下来的胶囊 */
  border: 1px solid var(--border);
  border-radius: 26px 26px 18px 18px;
  background: var(--bg); box-shadow: var(--shadow-2);
  transform-origin: top right;
  animation: capsule-drop .2s cubic-bezier(.22,.68,.19,1) both;
  will-change: transform, opacity;
}
/* 胶囊展开动画：从右上角小点放大成胶囊，竖向展开 */
@keyframes capsule-drop {
  from { opacity:0; transform:translate3d(0,-7px,0) scale(.96,.88); }
  to { opacity:1; transform:translate3d(0,0,0) scale(1); }
}
[data-all-animations-off="true"] .usage-menu { animation: none; }
@media (prefers-reduced-motion: reduce) { .usage-menu { animation: none; } }
.usage-title { margin: 0 0 9px; font-size: 12px; font-weight: 650; color: var(--text-2); }
.usage-stats { display: grid; gap: 8px; }
.usage-stats div { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.usage-stats span { font-size: 12px; color: var(--text-3); }
.usage-stats strong { font-family: var(--font-mono); font-size: 12.5px; color: var(--text); }
.usage-empty { margin: 0; font-size: 12px; line-height: 1.5; color: var(--text-3); }
.usage-subtitle { margin: 12px 0 6px; padding-top: 10px; border-top: 1px solid var(--border); font-size: 11.5px; font-weight: 650; color: var(--text-2); }
.category-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:5px 12px; }
.category-grid div { display:flex; justify-content:space-between; gap:8px; font-size:11px; }
.category-grid span { color:var(--text-3); }
.category-grid strong { color:var(--text-2); font-family:var(--font-mono); }
.effort-table { display: grid; gap: 1px; font-variant-numeric: tabular-nums; }
.effort-head, .effort-row { display: grid; grid-template-columns: 44px minmax(82px, 1.4fr) 54px 50px; align-items: center; gap: 5px; min-height: 27px; }
.effort-head { color: var(--text-3); font-size: 10.5px; }
.effort-row { border-top: 1px solid var(--border); color: var(--text-2); font-size: 11px; }
.effort-head span:not(:first-child), .effort-row span:not(:first-child) { text-align: right; }
/* ── 上下文用量面板（1.6.x 版式）：分段环 + 图例 + 明细 + 统计 ── */
.uc-top { display: flex; align-items: center; gap: 12px; margin: 6px 0 8px; }
.uc-ring { position: relative; flex: none; width: 104px; height: 104px; }
.uc-ring::before { content: ""; position: absolute; inset: 16%; border-radius: 50%; background: color-mix(in srgb, var(--fill) 70%, transparent); }
.uc-ring-svg { width: 100%; height: 100%; transform: rotate(-90deg) scale(1); transform-origin: 50%; transition: transform .34s cubic-bezier(.22,.68,.19,1), opacity .3s ease; }
.uc-ring-svg:not(.grown) { transform: rotate(-90deg) scale(.26); opacity: .35; }
.uc-ring-svg circle { fill: none; }
.uc-center { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 1px; }
.uc-center strong { font-size: 20px; font-weight: 700; color: var(--text); font-variant-numeric: tabular-nums; }
.uc-center span { font-size: 10.5px; color: var(--text-3); }
.uc-side { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 5px; }
.uc-line { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.uc-line span { font-size: 11.5px; color: var(--text-3); }
.uc-line strong { font-family: var(--font-mono); font-size: 12px; color: var(--text); }
.uc-legend { display: flex; flex-wrap: wrap; gap: 4px 10px; margin-bottom: 6px; }
.uc-legend-item { display: inline-flex; align-items: center; gap: 4px; font-size: 10.5px; color: var(--text-2); }
.uc-legend-item i { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
.uc-detail-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 4px 0; }
.uc-detail-label { display: inline-flex; align-items: center; gap: 6px; font-size: 11.5px; color: var(--text-2); }
.uc-detail-label i { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
.uc-detail-val { font-family: var(--font-mono); font-size: 11px; color: var(--text-3); }
.uc-stats { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 12px; margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--border); }
.uc-stats div { display: flex; flex-direction: column; gap: 2px; }
.uc-stats span { font-size: 10.5px; color: var(--text-3); }
.uc-stats strong { font-size: 12px; color: var(--text); font-variant-numeric: tabular-nums; }
.uc-cache { display: flex; flex-wrap: wrap; gap: 4px 12px; margin-top: 8px; }
.uc-cache span { font-size: 10.5px; color: var(--text-3); }
.uc-cache strong { color: var(--text-2); font-family: var(--font-mono); font-weight: 600; }
.usage-seg { fill: none; stroke-width: 5px; stroke-linecap: round; transition: stroke-dasharray .5s ease, stroke-dashoffset .5s ease; }
.usage-ring .usage-seg { stroke-width: 3.8px; }
.uc-ring-svg .usage-seg { stroke-width: 3.6px; }
.uc-ring-svg .usage-track { stroke-width: 3.2px; }

.usage-path {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  margin-top: 10px; padding-top: 9px; border-top: 1px solid var(--border);
}
.usage-path span { font-size: 12px; color: var(--text-3); flex-shrink: 0; }
.usage-env {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  margin-top: 6px; padding-top: 9px; border-top: 1px solid var(--border);
}
.usage-env > span { font-size: 12px; color: var(--text-3); flex-shrink: 0; }
.usage-env .env-row { display: flex; flex-direction: column; gap: 3px; align-items: flex-end; min-width: 0; }
.env-badge {
  font-style: normal; font-size: 11px; padding: 3px 9px; border-radius: var(--r-pill);
  white-space: nowrap;
}
.env-badge.ok { background: var(--ok-soft, #e8f5ee); color: var(--ok, #18794e); }
.env-badge.warn { background: var(--warn-soft, #fdf3e2); color: var(--focus, #b4690e); }
.env-badge.down { background: var(--fill); color: var(--text-3); }
.env-detail { font-size: 10.5px; color: var(--text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 220px; }
.path-btn {
  min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-family: var(--font-mono); font-size: 11.5px; color: var(--blue);
  background: var(--blue-soft); border-radius: var(--r-sm); padding: 5px 9px;
}
.path-mask { position: fixed; inset: 0; z-index: 60; background: rgba(0, 0, 0, 0.4); display: flex; align-items: flex-end; }
.path-sheet {
  width: 100%;
  background: var(--bg-card);
  border-radius: 18px 18px 0 0;
  padding: 18px 16px calc(16px + var(--safe-bottom));
}
.path-title { margin: 0; font-size: 16px; font-weight: 650; }
.path-desc { margin: 4px 0 12px; font-size: 12.5px; color: var(--text-3); }
.path-input {
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-sm);
  background: var(--bg-input);
  color: var(--text);
  font-family: var(--font-mono);
  font-size: 12.5px;
}
.path-quick { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.chip {
  padding: 6px 12px;
  border-radius: var(--r-pill);
  background: var(--fill-strong);
  color: var(--text-2);
  font-size: 12px;
}
.path-notice { margin: 10px 0 0; font-size: 12.5px; color: var(--ok); }
.path-actions { display: flex; gap: 10px; margin-top: 16px; }
.path-actions .btn { flex: 1; }
.btn.primary { background: var(--blue); color: #fff; }
.btn.ghost { background: var(--fill-strong); color: var(--text); }
.model-row { display: flex; align-items: center; gap: 8px; width: 100%; min-height: 38px; padding: 7px 9px; border: 0; border-radius: var(--r-sm); background: none; color: var(--text); text-align: left; }
.model-row span { display: flex; flex: 1; min-width: 0; flex-direction: column; overflow: hidden; }
.model-row b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: var(--font-mono); font-size: 12.5px; font-weight: 500; }
.model-row small { overflow: hidden; color: var(--text-3); font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.model-row.selected { background: var(--blue-soft); color: var(--blue); }
.model-row:active { background: var(--fill-press); }
.model-empty { padding: 16px 10px; text-align: center; font-size: 12.5px; color: var(--text-3); }
.icon-btn {
  display: grid; place-items: center; flex-shrink: 0;
  width: 40px; height: 40px;
  border: 0; border-radius: 50%; background: none; color: var(--text-2);
}
.icon-btn:active { background: var(--fill); }
.balance-button {
  display: inline-flex; align-items: center; gap: 4px;
  min-width: 34px; height: 34px; padding: 6px 8px;
  border: 0; border-radius: 50%; background: none; color: var(--text-2);
  font-size: 12.5px; font-weight: 650;
}
.balance-button:active { background: var(--fill); }
.balance-num { color: var(--ok); }
.usage-balance {
  display: flex; flex-direction: column; gap: 6px;
  margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--line);
}
.balance-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; font-size: 12.5px; color: var(--text-2); }
.balance-row strong { font-weight: 700; color: var(--text); }
.balance-row .cost { color: var(--orange); }
.usage-button {
  position: relative; display: grid; place-items: center; flex-shrink: 0;
  width: 40px; height: 40px; border: 0; border-radius: 50%; background: none; color: var(--text-2);
}
.usage-button:active { background: var(--fill); }
.usage-ring { width: 30px; height: 30px; transform: rotate(-90deg); }
.usage-ring circle { fill: none; stroke-width: 3.8; }
.usage-track { stroke: var(--border-strong); }
.usage-value { stroke: var(--blue); stroke-linecap: round; transition: stroke-dasharray .22s ease; }

.center {
    position: relative; flex: 1; min-width: 0;
    display: inline-flex; align-items: center; justify-content: center; gap: 5px;
    height: 36px; padding: 0 10px;
    border: 0; border-radius: var(--r-pill); background: none; color: var(--text);
}
.center:active { background: var(--fill); }
.session-brand { flex-shrink:0; }
.session-brand.enter { animation: session-brand-enter 1.1s cubic-bezier(.12,.8,.22,1) both; }
.session-brand.spinning { animation:session-brand-spin 1s cubic-bezier(.62,.02,.2,.92) infinite; will-change:transform; }
.session-brand.settling { animation:session-brand-settle 1s cubic-bezier(.2,.8,.2,1) both; }
.life-orbit { position: relative; display: inline-grid; place-items: center; width: 20px; height: 20px; margin: 0 3px; padding: 0; border: 0; border-radius: 50%; background: transparent; cursor: pointer; flex-shrink: 0; }
.orbit { position: absolute; inset: 2px; border-radius: 50%; }
.orbit.outer { border: 1.6px solid transparent; border-top-color: var(--blue); border-right-color: var(--blue); animation: topbar-life-spin 1.8s linear infinite; }
.orbit.inner { inset: 5px; border: 1.6px solid transparent; border-bottom-color: var(--orange); border-left-color: var(--orange); animation: topbar-life-spin-reverse 1.15s linear infinite; }
@keyframes topbar-life-spin { to { transform: rotate(360deg); } }
@keyframes topbar-life-spin-reverse { to { transform: rotate(-360deg); } }
@media (prefers-reduced-motion: reduce) { .orbit.outer, .orbit.inner { animation-duration: 6s; } }
@keyframes session-brand-enter {
  0% { transform:translate3d(-46vw,0,0) scale(3.2) rotate(0deg); opacity:0; }
  18% { opacity:1; }
  62% { transform:translate3d(-4vw,0,0) scale(1.15) rotate(300deg); }
  100% { transform:translate3d(0,0,0) scale(1) rotate(360deg); opacity:1; }
}
@keyframes session-brand-spin { 0% { transform:rotate(0deg); } 72% { transform:rotate(300deg); } 100% { transform:rotate(360deg); } }
@keyframes session-brand-settle { from { transform:rotate(0deg); } to { transform:rotate(360deg); } }
.model {
  font-size: 15.5px; font-weight: 600; letter-spacing: -.1px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.plan {
  flex-shrink: 0; padding: 2px 7px; border-radius: var(--r-pill);
  background: var(--blue-soft); color: var(--blue);
  font-size: 11px; font-weight: 600;
}
/* 演示标记用点缀橙，和蓝色的功能性标记（计划）区分开。 */
.demo {
  flex-shrink: 0; padding: 2px 7px; border-radius: var(--r-pill);
  background: var(--orange-soft); color: var(--orange);
  font-size: 11px; font-weight: 600;
}
.caret { color: var(--text-3); }

/* 底边扫光：不表示进度，只表示「还在动」。 */
.sweep {
  position: absolute; left: 0; right: 0; bottom: 0;
  height: 2px; overflow: hidden;
}
.sweep i {
  display: block; width: 100%; height: 100%;
  background: linear-gradient(90deg, transparent, var(--blue), transparent);
  animation: coomi-sweep 1.25s ease-in-out infinite;
}
</style>
