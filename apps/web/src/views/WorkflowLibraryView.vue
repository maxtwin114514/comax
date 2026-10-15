<script setup lang="ts">
/**
 * 记忆库 —— 动态工作流库（M1）。
 *
 * 这一页取代了原来的「笔记」列表：记忆库里放的是**可回放的工作流脚本**，
 * 不再是笔记条目。引擎侧见 apps/coomi-rs/ui/src/dynwork.rs：
 * 脚本在 node 沙箱里跑，世界访问（文件/命令/子代理/产物）全部经门面原语记账，
 * journal 命中即回放，所以「改一处只重跑其后」是免费拿到的。
 *
 * 交互纪律（来自契约文档）：运行前必须过确认门 —— 展示脚本原文与命令白名单，
 * 用户点了确认才发车。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHead from '@/components/PageHead.vue'
import CoomiIcon from '@/components/CoomiIcon.vue'
import { apiGet, apiSend } from '@/bridge/http'
import { goBack } from '@/bridge/navigation'

interface Draft {
  id: string
  name: string
  description: string
  updated_at: number
}

interface RunMeta {
  run_id: string
  draft_id: string
  name: string
  status: string
  stop_reason: string
  started_at: number
  finished_at?: number | null
  commands?: string[]
  artifacts?: string[]
  seeded_from?: string | null
  result?: unknown
  error?: unknown
}

interface RunEvent {
  seq: number
  kind: string
  ts: number
  [key: string]: unknown
}

const router = useRouter()

const drafts = ref<Draft[]>([])
const runs = ref<RunMeta[]>([])
const sandboxReady = ref(true)
const loading = ref(false)
const error = ref('')
const notice = ref('')

const activeId = ref('')
const name = ref('')
const description = ref('')
const script = ref('')
const diagnostics = ref<string[]>([])
const commands = ref<string[]>([])
const artifacts = ref<string[]>([])

const confirmOpen = ref(false)
const running = ref(false)

const runId = ref('')
const runMeta = ref<RunMeta | null>(null)
const events = ref<RunEvent[]>([])
let poll: ReturnType<typeof setInterval> | null = null
let cursor = 0

const phases = computed(() => events.value.filter(item => item.kind === 'phase').map(item => String(item.name ?? '')))
const timeline = computed(() => events.value.filter(item => !['start', 'phase', 'actor'].includes(item.kind)))
const actorEvents = computed(() => events.value.filter(item => item.kind === 'actor').slice(-40))
const reports = computed(() => events.value.filter(item => item.kind === 'report'))
const artifactCards = computed(() => {
  const latest = new Map<string, RunEvent>()
  for (const item of events.value) {
    if (item.kind === 'artifact') latest.set(String(item.id), item)
  }
  return [...latest.values()]
})
const finalResult = computed(() => {
  const complete = [...events.value].reverse().find(item => item.kind === 'complete')
  return complete?.result ?? runMeta.value?.result ?? null
})

function stamp(value: number): string {
  if (!value) return ''
  const date = new Date(value * 1000)
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function pretty(value: unknown): string {
  if (typeof value === 'string') return value
  try { return JSON.stringify(value, null, 2) } catch { return String(value) }
}

function stopPolling() {
  if (poll) { clearInterval(poll); poll = null }
}

async function loadLibrary() {
  loading.value = true
  error.value = ''
  try {
    const data = await apiGet<{ drafts: Draft[]; runs: RunMeta[]; sandbox: boolean }>('/api/dynamic-workflows')
    drafts.value = data.drafts ?? []
    runs.value = data.runs ?? []
    sandboxReady.value = data.sandbox !== false
  } catch (cause) {
    error.value = `读取记忆库失败：${String(cause)}`
  } finally {
    loading.value = false
  }
}

async function openDraft(id: string) {
  error.value = ''
  notice.value = ''
  try {
    const data = await apiGet<{ draft: Draft; script: string; diagnostics: string[]; commands: string[]; artifacts: string[] }>(`/api/dynamic-workflows/${id}`)
    activeId.value = data.draft.id
    name.value = data.draft.name
    description.value = data.draft.description ?? ''
    script.value = data.script ?? ''
    diagnostics.value = data.diagnostics ?? []
    commands.value = data.commands ?? []
    artifacts.value = data.artifacts ?? []
    stopPolling()
    runId.value = ''
    runMeta.value = null
    events.value = []
    cursor = 0
  } catch (cause) {
    error.value = `打开工作流失败：${String(cause)}`
  }
}

function newDraft() {
  activeId.value = ''
  name.value = ''
  description.value = ''
  script.value = TEMPLATE
  diagnostics.value = []
  commands.value = []
  artifacts.value = []
  runId.value = ''
  runMeta.value = null
  events.value = []
  cursor = 0
  notice.value = '新工作流：改完先「保存」，再「检查」，最后「运行」'
}

async function saveDraft(): Promise<string | null> {
  error.value = ''
  try {
    const data = await apiSend<{ draft: Draft }>('/api/dynamic-workflows', 'POST', {
      id: activeId.value || undefined,
      name: name.value,
      description: description.value,
      script: script.value,
    })
    activeId.value = data.draft.id
    notice.value = '已保存'
    await loadLibrary()
    return data.draft.id
  } catch (cause) {
    error.value = `保存失败：${String(cause)}`
    return null
  }
}

async function checkDraft() {
  const id = activeId.value || await saveDraft()
  if (!id) return
  error.value = ''
  try {
    const data = await apiSend<{ diagnostics: string[]; commands: string[]; artifacts: string[]; sandbox: boolean }>(`/api/dynamic-workflows/${id}/plan`, 'POST', { script: script.value, name: name.value })
    diagnostics.value = data.diagnostics ?? []
    commands.value = data.commands ?? []
    artifacts.value = data.artifacts ?? []
    sandboxReady.value = data.sandbox !== false
    notice.value = diagnostics.value.length ? '检查未通过：脚本里有确定性/越权问题' : `检查通过 · 命令白名单 ${commands.value.length} 条`
  } catch (cause) {
    error.value = `检查失败：${String(cause)}`
  }
}

async function prepareRun() {
  const id = activeId.value || await saveDraft()
  if (!id) return
  await checkDraft()
  if (diagnostics.value.length) return
  confirmOpen.value = true
}

async function confirmRun(seedFrom = '') {
  const id = activeId.value
  if (!id) return
  confirmOpen.value = false
  error.value = ''
  running.value = true
  try {
    const data = await apiSend<{ ok: boolean; run_id?: string; reason?: string; diagnostics?: string[] }>(`/api/dynamic-workflows/${id}/run`, 'POST', {
      confirm: true,
      script: script.value,
      name: name.value,
      seed_from: seedFrom || undefined,
    })
    if (!data.ok || !data.run_id) {
      diagnostics.value = data.diagnostics ?? diagnostics.value
      error.value = data.reason === 'confirm_required' ? '需要先确认脚本与命令白名单' : '运行未启动'
      return
    }
    runId.value = data.run_id
    events.value = []
    cursor = 0
    await pollRun()
    stopPolling()
    poll = setInterval(() => { void pollRun() }, 1200)
    await loadLibrary()
  } catch (cause) {
    error.value = `运行失败：${String(cause)}`
  } finally {
    running.value = false
  }
}

async function pollRun() {
  if (!runId.value) return
  try {
    const data = await apiGet<{ events: RunEvent[]; running: boolean }>(`/api/dynamic-workflows/runs/${runId.value}/events?since=${cursor}`)
    if (data.events?.length) {
      events.value = [...events.value, ...data.events]
      cursor += data.events.length
    }
    if (!data.running) {
      stopPolling()
      const detail = await apiGet<{ meta: RunMeta; journal: RunEvent[] }>(`/api/dynamic-workflows/runs/${runId.value}`)
      runMeta.value = detail.meta
      // 引擎在运行结束后会回收内存事件流；快跑完的运行可能刚好错过最后一拍轮询，
      // 所以结束一律以磁盘 journal 为准补齐时间线，避免"跑完了却什么都不显示"。
      const journal = detail.journal ?? []
      if (journal.length > events.value.length) {
        events.value = journal
        cursor = journal.length
      }
    }
  } catch {
    /* 轮询失败不打断界面，下一拍再试 */
  }
}

async function openRun(id: string) {
  stopPolling()
  error.value = ''
  runId.value = id
  events.value = []
  cursor = 0
  try {
    const detail = await apiGet<{ meta: RunMeta; journal: RunEvent[]; running: boolean }>(`/api/dynamic-workflows/runs/${id}`)
    runMeta.value = detail.meta
    events.value = detail.journal ?? []
    cursor = events.value.length
    if (detail.running) poll = setInterval(() => { void pollRun() }, 1200)
  } catch (cause) {
    error.value = `读取运⾏记录失败：${String(cause)}`
  }
}

async function stopRun() {
  if (!runId.value) return
  try {
    await apiSend(`/api/dynamic-workflows/runs/${runId.value}/stop`, 'POST', {})
    notice.value = '已请求停止'
    await pollRun()
    stopPolling()
  } catch (cause) {
    error.value = `停止失败：${String(cause)}`
  }
}

async function removeDraft(id: string) {
  try {
    await apiSend(`/api/dynamic-workflows/${id}`, 'DELETE')
    if (activeId.value === id) newDraft()
    await loadLibrary()
  } catch (cause) {
    error.value = `删除失败：${String(cause)}`
  }
}

const TEMPLATE = [
  '// 动态工作流：脚本里不做世界访问（会抛错），',
  '// 文件 / 命令 / 子代理都要经门面原语，引擎会记账并可原样回放。',
  "phase('侦察');",
  "const listing = await world.run('ls', ['-1']);",
  "const entries = String(listing.stdout).split('\\n').filter(Boolean);",
  'log(`工作目录里 ${entries.length} 个条目`);',
  '',
  "phase('逐项审查');",
  "const reviewer = agent('审查员', { system: '你只回答被问到的一件事，结论带证据' });",
  'const found = [];',
  'for (const entry of entries.slice(0, 5)) {',
  '  const verdict = await reviewer.ask(`看下 ${entry} 有没有明显问题，一句话结论`);',
  '  found.push({ file: entry, verdict: verdict.text });',
  "  report({ file: entry, verdict: verdict.text }, '审查');",
  '}',
  '',
  "phase('交付');",
  "artifact.markdown('summary', '# 审查小结\\n\\n' + found.map(item => `- ${item.file}：${item.verdict}`).join('\\n'), { title: '审查小结', primary: true });",
  'return {',
  '  conclusion: `共检查 ${found.length} 个条目`,',
  '  findings: found,',
  "  verified: ['ls 输出', '逐个问过审查员'],",
  "  notCovered: ['未做机器闸门（world.run 退出码）复核'],",
  '};',
  '',
].join('\n')

onMounted(() => { void loadLibrary() })
onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="library">
    <PageHead title="记忆库" @back="goBack(router, 'dashboard')">
      <template #right>
        <button class="icon-btn blue" aria-label="新建工作流" @click="newDraft"><CoomiIcon name="plus" /></button>
      </template>
    </PageHead>

    <main class="body">
      <p v-if="error" class="notice err">{{ error }}</p>
      <p v-if="notice" class="notice ok">{{ notice }}</p>
      <p v-if="!sandboxReady" class="notice warn">
        <CoomiIcon name="alert" :size="14" /> 运行环境里没找到 node，动态工作流暂时跑不起来（需要 Debian 侧的 /usr/bin/node）。
      </p>

      <section v-if="!activeId && !script" class="block">
        <p class="hint">记忆库里存的是可回放的工作流脚本：世界调用会记账，重跑时命中的步骤零成本。</p>
        <div class="row-actions">
          <button class="btn primary" @click="newDraft"><CoomiIcon name="plus" :size="15" />新建工作流</button>
        </div>
      </section>

      <template v-if="activeId || script">
        <section class="block">
          <label class="field"><span>名称</span><input v-model="name" placeholder="例如：改动文件逐项审查" /></label>
          <label class="field"><span>说明</span><input v-model="description" placeholder="这个工作流什么时候用" /></label>
          <label class="field"><span>脚本</span><textarea v-model="script" spellcheck="false" rows="14" /></label>
          <div class="row-actions">
            <button class="btn ghost" @click="saveDraft"><CoomiIcon name="check" :size="15" />保存</button>
            <button class="btn ghost" @click="checkDraft"><CoomiIcon name="scan" :size="15" />检查</button>
            <button class="btn primary" :disabled="!script.trim()" @click="prepareRun"><CoomiIcon name="play" :size="15" />运行</button>
          </div>
          <div v-if="diagnostics.length" class="diag">
            <p v-for="(item, index) in diagnostics" :key="index">{{ item }}</p>
          </div>
          <div v-else-if="commands.length || artifacts.length" class="scan-meta">
            <p v-if="commands.length"><b>命令白名单</b>：<code v-for="c in commands" :key="c">{{ c }}</code></p>
            <p v-if="artifacts.length"><b>产物 id</b>：<code v-for="a in artifacts" :key="a">{{ a }}</code></p>
          </div>
        </section>
      </template>

      <section v-if="runId || runMeta" class="block run">
        <header class="run-head">
          <span class="pill" :class="runMeta?.status ?? 'running'">{{ runMeta?.status ?? 'running' }}</span>
          <span v-if="runMeta?.stop_reason" class="sub">{{ runMeta.stop_reason }}</span>
          <span v-if="runMeta?.seeded_from" class="sub">（种子：{{ runMeta.seeded_from.slice(0, 8) }}）</span>
          <span class="gap" />
          <button v-if="!runMeta" class="btn ghost small" @click="stopRun"><CoomiIcon name="stop" :size="14" />停止</button>
        </header>

        <div v-if="phases.length" class="phases">
          <span v-for="(item, index) in phases" :key="index" class="phase">{{ item }}</span>
        </div>

        <ul class="lines">
          <li v-for="item in timeline" :key="item.seq" :class="item.kind">
            <template v-if="item.kind === 'phase'">◆ {{ item.name }}</template>
            <template v-else-if="item.kind === 'log'">{{ item.message }}</template>
            <template v-else-if="item.kind === 'world'">
              <code>{{ item.op }}</code>
              <em v-if="item.cached" class="cached">命中缓存</em>
            </template>
            <template v-else-if="item.kind === 'ask_start'">问 {{ item.actor }}：{{ String(item.prompt ?? '').slice(0, 120) }}</template>
            <template v-else-if="item.kind === 'ask'">{{ item.actor }} 答（{{ item.cached ? '缓存' : '新跑' }}）</template>
            <template v-else-if="item.kind === 'artifact'">产物 {{ item.id }} v{{ item.version }}</template>
            <template v-else-if="item.kind === 'stderr'">stderr：{{ item.message }}</template>
            <template v-else-if="item.kind === 'error'">✕ {{ item.message }}</template>
            <template v-else>{{ item.kind }}</template>
          </li>
        </ul>

        <details v-if="reports.length" class="fold" open>
          <summary>随产随报 · {{ reports.length }} 条</summary>
          <pre>{{ pretty(reports.map(item => item.item)) }}</pre>
        </details>

        <div v-if="artifactCards.length" class="cards">
          <article v-for="card in artifactCards" :key="String(card.id)" class="card">
            <b>{{ card.title || card.id }}</b>
            <span>{{ card.kind }} · v{{ card.version }}</span>
            <pre v-if="card.body">{{ String(card.body).slice(0, 2000) }}</pre>
          </article>
        </div>

        <details v-if="actorEvents.length" class="fold">
          <summary>子代理活动 · {{ actorEvents.length }}</summary>
          <ul class="lines small">
            <li v-for="(item, index) in actorEvents" :key="index">
              {{ item.actor }} · {{ item.event }} {{ item.tool ?? '' }} {{ item.isError ? '（失败）' : '' }}
            </li>
          </ul>
        </details>

        <details v-if="finalResult" class="fold" open>
          <summary>最终返回值</summary>
          <pre>{{ pretty(finalResult) }}</pre>
        </details>

        <div class="row-actions" v-if="runMeta">
          <button class="btn ghost" @click="confirmRun(runId)"><CoomiIcon name="refresh" :size="15" />用这次的 journal 作种子重跑</button>
        </div>
      </section>

      <section class="block">
        <p class="qhead">工作流 · {{ drafts.length }}</p>
        <div v-if="!drafts.length" class="hint">还没有工作流，点右上角「+」建一个。</div>
        <button v-for="draft in drafts" :key="draft.id" class="row" :class="{ active: draft.id === activeId }" @click="openDraft(draft.id)">
          <CoomiIcon name="subtask" :size="16" />
          <span class="row-main"><b>{{ draft.name }}</b><small>{{ draft.description || '未填说明' }}</small></span>
          <span class="sub">{{ stamp(draft.updated_at) }}</span>
          <span class="row-del" role="button" aria-label="删除" @click.stop="removeDraft(draft.id)"><CoomiIcon name="trash" :size="14" /></span>
        </button>
      </section>

      <section v-if="runs.length" class="block">
        <p class="qhead">最近运行 · {{ runs.length }}</p>
        <button v-for="run in runs" :key="run.run_id" class="row" :class="{ active: run.run_id === runId }" @click="openRun(run.run_id)">
          <CoomiIcon name="clock" :size="16" />
          <span class="row-main"><b>{{ run.name }}</b><small>{{ run.stop_reason || run.status }}</small></span>
          <span class="sub">{{ stamp(run.started_at) }}</span>
        </button>
      </section>
    </main>

    <div v-if="confirmOpen" class="mask" @click.self="confirmOpen = false">
      <div class="dialog" role="alertdialog" aria-modal="true" aria-label="确认运行工作流">
        <p class="dialog-title">确认运行「{{ name || '未命名' }}」？</p>
        <p class="dialog-text">脚本会在沙箱里跑，世界访问全部记账可回放。下面是它要用到的外部命令白名单（来自脚本里的字面量）：</p>
        <p v-if="commands.length" class="whitelist"><code v-for="c in commands" :key="c">{{ c }}</code></p>
        <p v-else class="whitelist none">没有外部命令，只用文件读取与子代理提问。</p>
        <pre class="script-preview">{{ script.slice(0, 4000) }}</pre>
        <div class="dialog-actions">
          <button class="btn ghost" @click="confirmOpen = false">取消</button>
          <button class="btn primary" @click="confirmRun()">确认运行</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.library { height: 100%; min-height: 0; display: flex; flex-direction: column; background: var(--page); }
.body { flex: 1; min-height: 0; overflow-y: auto; padding: 12px 12px calc(20px + var(--safe-bottom)); display: flex; flex-direction: column; gap: 12px; }
.block { border: 1px solid var(--border); border-radius: var(--r-card); background: var(--bg); padding: 12px; display: flex; flex-direction: column; gap: 9px; }
.hint { margin: 0; color: var(--text-3); font-size: 12.5px; line-height: 1.6; }
.notice { margin: 0; padding: 9px 11px; border-radius: var(--r-md); font-size: 12.5px; display: flex; align-items: center; gap: 6px; }
.notice.err { background: var(--danger-soft, #fdecec); color: var(--danger); }
.notice.ok { background: var(--blue-soft); color: var(--blue); }
.notice.warn { background: var(--orange-soft); color: var(--orange); }
.field { display: flex; flex-direction: column; gap: 5px; }
.field span { color: var(--text-3); font-size: 12px; font-weight: 600; }
.field input, .field textarea {
  width: 100%; padding: 9px 11px; border: 1px solid var(--border); border-radius: 10px;
  background: var(--fill); color: var(--text); font-size: 13px;
}
.field textarea { font-family: var(--font-mono); font-size: 12.2px; line-height: 1.55; resize: vertical; }
.row-actions { display: flex; gap: 7px; flex-wrap: wrap; }
.btn { display: inline-flex; align-items: center; gap: 6px; min-height: 36px; padding: 0 13px; border: 0; border-radius: 10px; font-size: 13px; font-weight: 600; }
.btn.primary { background: var(--blue); color: #fff; }
.btn.ghost { background: var(--fill); color: var(--text-2); }
.btn.small { min-height: 30px; padding: 0 10px; font-size: 12px; }
.btn:disabled { opacity: .5; }
.diag { border-radius: 10px; background: var(--orange-soft); color: var(--orange); padding: 9px 11px; font-size: 12.2px; line-height: 1.6; }
.diag p { margin: 0; }
.scan-meta { font-size: 12.2px; color: var(--text-2); display: flex; flex-direction: column; gap: 4px; }
.scan-meta p { margin: 0; }
.scan-meta code, .whitelist code { margin-right: 6px; padding: 1px 6px; border-radius: 6px; background: var(--fill-strong); font-size: 11.5px; }
.qhead { margin: 0; font-size: 12px; font-weight: 700; color: var(--text-3); }
.row { display: flex; align-items: center; gap: 9px; width: 100%; padding: 9px 8px; border: 0; border-radius: 10px; background: none; text-align: left; color: var(--text); }
.row.active { background: var(--blue-soft); }
.row-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.row-main b { font-size: 13.2px; font-weight: 650; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-main small { color: var(--text-3); font-size: 11.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-del { padding: 6px; color: var(--text-3); }
.sub { color: var(--text-3); font-size: 11.5px; flex-shrink: 0; }
.run-head { display: flex; align-items: center; gap: 8px; }
.gap { flex: 1; }
.pill { padding: 2px 9px; border-radius: var(--r-pill); background: var(--fill-strong); color: var(--text-2); font-size: 11.5px; font-weight: 650; }
.pill.completed { background: var(--blue-soft); color: var(--blue); }
.pill.errored { background: var(--danger-soft, #fdecec); color: var(--danger); }
.pill.stopped { background: var(--orange-soft); color: var(--orange); }
.phases { display: flex; flex-wrap: wrap; gap: 6px; }
.phase { padding: 3px 9px; border-radius: var(--r-pill); background: var(--fill); color: var(--text-2); font-size: 11.5px; }
.lines { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; max-height: 320px; overflow-y: auto; }
.lines li { font-size: 12.2px; color: var(--text-2); line-height: 1.55; }
.lines li.error { color: var(--danger); }
.lines li.world code { background: var(--fill-strong); padding: 1px 6px; border-radius: 6px; font-size: 11.5px; }
.lines li.world em.cached { margin-left: 6px; color: var(--blue); font-style: normal; font-size: 11px; }
.lines.small li { font-size: 11.5px; color: var(--text-3); }
.fold summary { cursor: pointer; font-size: 12.2px; font-weight: 650; color: var(--text-2); }
.fold pre, .card pre { margin: 6px 0 0; padding: 9px; border-radius: 10px; background: var(--fill); font-size: 11.6px; line-height: 1.5; max-height: 320px; overflow: auto; white-space: pre-wrap; word-break: break-word; }
.cards { display: flex; flex-direction: column; gap: 8px; }
.card { border: 1px solid var(--border); border-radius: 10px; padding: 9px 10px; display: flex; flex-direction: column; gap: 3px; }
.card b { font-size: 12.6px; }
.card span { color: var(--text-3); font-size: 11.4px; }
.mask { position: fixed; inset: 0; z-index: 90; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; padding: 22px; }
.dialog { width: 100%; max-width: 380px; max-height: 84vh; overflow-y: auto; padding: 15px; border-radius: var(--r-card); background: var(--bg); box-shadow: var(--shadow-2); display: flex; flex-direction: column; gap: 9px; }
.dialog-title { margin: 0; font-size: 15px; font-weight: 750; }
.dialog-text { margin: 0; font-size: 12.4px; line-height: 1.6; color: var(--text-2); }
.whitelist { margin: 0; display: flex; flex-wrap: wrap; gap: 4px; }
.whitelist.none { color: var(--text-3); font-size: 12.2px; }
.script-preview { margin: 0; padding: 9px; border-radius: 10px; background: var(--fill); font-family: var(--font-mono); font-size: 11.4px; line-height: 1.5; max-height: 240px; overflow: auto; white-space: pre-wrap; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
