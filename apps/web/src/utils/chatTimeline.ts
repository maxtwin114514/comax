import type { AssistantMessage, Timelineitem, ToolCard } from '@/stores/viewModel'

export const MAX_TRANSCRIPT_ITEMS = 400

/** 一轮「过程」里的一段：思考 / 一组工具 / 中间叙述文字。 */
export type ProcessStage =
  | { s: 'reasoning'; key: string; item: Extract<Timelineitem, { kind: 'reasoning' }> }
  | { s: 'tools'; key: string; cards: ToolCard[] }
  | { s: 'text'; key: string; item: AssistantMessage }

/** 一轮过程块：多段思考 + 多次工具调用折叠在同一个标题下，本轮最后一条正文留在块外。 */
export interface ProcessBlockData {
  key: string
  stages: ProcessStage[]
  answer: AssistantMessage | null
}

export type TimelineBlockItem =
  | { t: 'one'; key: string; item: Timelineitem }
  | { t: 'process'; key: string; block: ProcessBlockData }

function itemId(item: Timelineitem): string {
  return 'id' in item ? item.id : item.callId
}

/** 过程块里可以折叠的条目。 */
function isProcessItem(item: Timelineitem): boolean {
  return item.kind === 'reasoning' || item.kind === 'tool' || item.kind === 'assistant'
}

type ProcessItem = { t: 'process'; key: string; block: ProcessBlockData }
type CachedBlock = { item: ProcessItem; snapshot: readonly unknown[] }

const oneCache = new Map<string, OneBlock>()
const processCache = new Map<string, CachedBlock>()
let lastBlocks: TimelineBlockItem[] = []

type OneBlock = { t: 'one'; key: string; item: Timelineitem }

/** 快照里放的是原始对象引用：结构没变就复用同一个块，虚拟列表不会重排。 */
function processSnapshot(block: ProcessBlockData): unknown[] {
  const snapshot: unknown[] = []
  for (const stage of block.stages) {
    if (stage.s === 'tools') {
      snapshot.push('tools', stage.key)
      for (const card of stage.cards) snapshot.push(card)
    } else {
      snapshot.push(stage.s, stage.key, stage.item)
    }
  }
  snapshot.push('answer', block.answer)
  return snapshot
}

function sameSnapshot(a: readonly unknown[], b: readonly unknown[]): boolean {
  if (a.length !== b.length) return false
  for (let i = 0; i < a.length; i += 1) if (a[i] !== b[i]) return false
  return true
}

function buildProcessBlock(run: readonly Timelineitem[]): ProcessBlockData {
  const last = run[run.length - 1]
  const answer = last?.kind === 'assistant' ? (last as AssistantMessage) : null
  const stages: ProcessStage[] = []
  let toolGroup: ToolCard[] | null = null

  const flushTools = () => {
    if (toolGroup && toolGroup.length > 0) {
      stages.push({ s: 'tools', key: `g:${toolGroup[0].callId}`, cards: toolGroup.slice() })
    }
    toolGroup = null
  }

  for (const item of run) {
    if (item === answer) continue
    if (item.kind === 'tool') {
      if (!toolGroup) toolGroup = []
      toolGroup.push(item)
      continue
    }
    flushTools()
    if (item.kind === 'reasoning') {
      stages.push({ s: 'reasoning', key: `r:${item.id}`, item })
    } else if (item.kind === 'assistant' && item.content.trim()) {
      stages.push({ s: 'text', key: `a:${item.id}`, item })
    }
  }
  flushTools()

  return {
    key: `p:${itemId(run[0])}`,
    stages,
    answer,
  }
}

/**
 * 时间线分块，并保证「结构没变就复用同一个对象」。
 *
 * 为什么必须复用：ChatView 把这些块喂给 DynamicScroller，而
 * DynamicScrollerItem 会对 size-dependencies 的每一项挂一个浅 watcher
 * （vue-virtual-scroller `this.$watch(() => sizeDependencies[k], onDataUpdate)`）。
 * 只要元素引用变了就会重新量尺寸并触发整条虚拟列表重排 —— 从前每来一个 token
 * 都重建全部块对象，于是「一输出文字/一调工具」整个列表闪一下。
 * 现在只有真正新增或替换的块才会换对象，稳定块原样复用。
 *
 * 分块规则：
 *   · user / notice / question → 独立块
 *   · 连续的 reasoning / tool / assistant → 合并成**一个过程块**（多段思考折叠），
 *     其中最后一条 assistant 正文作为「回答」留在块外，前面的正文算过程叙述。
 */
export function buildTimelineBlocks(items: readonly Timelineitem[]): TimelineBlockItem[] {
  const blocks: TimelineBlockItem[] = []
  const seenOne = new Set<string>()
  const seenProcess = new Set<string>()

  let index = 0
  while (index < items.length) {
    const item = items[index]
    if (isProcessItem(item)) {
      const start = index
      while (index < items.length && isProcessItem(items[index])) index += 1
      const run = items.slice(start, index)
      const fresh = buildProcessBlock(run)
      const key = fresh.key
      const cached = processCache.get(key)
      const snapshot = processSnapshot(fresh)
      let item: ProcessItem
      if (cached && sameSnapshot(cached.snapshot, snapshot)) {
        // 复用同一个块对象：虚拟列表的 size-dependencies 才不会重排。
        item = cached.item
        cached.snapshot = snapshot
      } else {
        item = { t: 'process', key, block: fresh }
        processCache.set(key, { item, snapshot })
      }
      seenProcess.add(key)
      blocks.push(item)
      continue
    }
    const key = `${item.kind}:${itemId(item)}`
    const cached = oneCache.get(key)
    const block = cached && cached.item === item ? cached : { t: 'one' as const, key, item }
    if (!cached || cached.item !== item) oneCache.set(key, block)
    seenOne.add(key)
    blocks.push(block)
    index += 1
  }

  // 清理本次没出现的键，避免长时间会话把缓存越堆越大。
  for (const key of oneCache.keys()) if (!seenOne.has(key)) oneCache.delete(key)
  for (const key of processCache.keys()) if (!seenProcess.has(key)) processCache.delete(key)

  // 结构完全没变时返回同一个数组：DynamicScroller 的 items watcher 也就不会跑。
  if (blocks.length === lastBlocks.length && blocks.every((block, i) => block === lastBlocks[i])) {
    return lastBlocks
  }
  lastBlocks = blocks
  return blocks
}

export function transcriptTail(
  items: readonly Timelineitem[],
  limit = MAX_TRANSCRIPT_ITEMS,
): Timelineitem[] {
  if (!Number.isInteger(limit) || limit <= 0) return []
  return items.slice(-limit).map(item => {
    if (item.kind !== 'user') return item
    return item.attachments?.length
      ? { kind: item.kind, id: item.id, mid: item.mid, content: item.content, attachments: item.attachments }
      : { kind: item.kind, id: item.id, mid: item.mid, content: item.content }
  })
}


export function parseTranscript(raw: string | null): Timelineitem[] {
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return (parsed as Timelineitem[]).map(item => item.kind === 'user'
      ? { ...item, morphing: false, morphArrived: false }
      : item)
  } catch {
    return []
  }
}
