import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  MAX_TRANSCRIPT_ITEMS,
  buildTimelineBlocks,
  parseTranscript,
  transcriptTail,
} from '../src/utils/chatTimeline.ts'
import type { Timelineitem, ToolCard } from '../src/stores/viewModel.ts'

function tool(callId: string, images?: string[]): ToolCard {
  return {
    kind: 'tool',
    callId,
    toolName: 'show_image',
    arguments: { path: `/tmp/${callId}.png` },
    status: 'success',
    images,
  }
}

test('folds one process run (thinking + tools + narration) into a single block', () => {
  const items: Timelineitem[] = [
    { kind: 'user', id: 'u1', content: 'start' },
    { kind: 'reasoning', id: 'r1', content: '想一下', expanded: false },
    tool('t1'),
    tool('t2'),
    { kind: 'assistant', id: 'a1', content: '先看看文件', streaming: false },
    { kind: 'reasoning', id: 'r2', content: '再看看', expanded: false },
    tool('t3'),
    { kind: 'assistant', id: 'a2', content: 'done', streaming: false },
  ]

  const blocks = buildTimelineBlocks(items)
  // user 独立成块；整段过程折叠成一个过程块
  assert.deepEqual(blocks.map(block => block.key), ['user:u1', 'p:r1'])
  const process = blocks[1]
  assert.equal(process.t, 'process')
  if (process.t !== 'process') return
  // 最后一条正文作为回答留在折叠块外
  assert.equal(process.block.answer, items[7])
  // 两段思考、两组工具、一段中间叙述
  assert.deepEqual(process.block.stages.map(stage => stage.s), ['reasoning', 'tools', 'text', 'reasoning', 'tools'])
  const firstTools = process.block.stages[1]
  assert.deepEqual(firstTools.s === 'tools' ? firstTools.cards.map(card => card.callId) : [], ['t1', 't2'])
  // 结构没变时必须复用同一个对象：虚拟列表靠它避免整条重排
  assert.equal(buildTimelineBlocks(items)[1], process)
})

test('builds a 5,000-item variable-height timeline without truncating content', () => {
  const longMarkdown = `${'# Long response\n\n'}${'| cell | value |\n|---|---|\n'.repeat(2_000)}END_MARKER`
  const image = 'data:image/png;base64,iVBORw0KGgo='
  const items: Timelineitem[] = Array.from({ length: 4_997 }, (_, index) => index % 2 === 0
    ? { kind: 'user', id: `m${index}`, content: `message ${index}` }
    : { kind: 'assistant', id: `m${index}`, content: `message ${index}`, streaming: false })
  items.push({ kind: 'assistant', id: 'long', content: longMarkdown, streaming: false })
  items.push(tool('image-1', [image]))
  items.push(tool('image-2', [image]))

  const blocks = buildTimelineBlocks(items)
  assert.equal(items.length, 5_000)
  // 4.997 条 user/assistant 交替 → 4.997 块；末尾 long + 两张图自成一块
  assert.equal(blocks.length, 4_998)
  const finalBlock = blocks.at(-1)
  assert.equal(finalBlock?.t, 'process')
  if (finalBlock?.t !== 'process') return
  assert.deepEqual(
    finalBlock.block.stages.flatMap(stage => (stage.s === 'tools' ? stage.cards.flatMap(card => card.images ?? []) : [])),
    [image, image],
  )
  // 这一轮以工具调用收尾，没有「最终正文」，长文本作为过程叙述留在折叠块里
  assert.equal(finalBlock.block.answer, null)
  const narration = finalBlock.block.stages.find(stage => stage.s === 'text')
  assert.equal(narration?.s === 'text' ? narration.item.content : '', longMarkdown)
})

test('session fallback persistence keeps the newest bounded transcript intact', () => {
  const items: Timelineitem[] = Array.from({ length: 1_200 }, (_, index) => ({
    kind: 'assistant',
    id: `a${index}`,
    content: index === 1_199 ? `${'large markdown\n'.repeat(3_000)}LAST` : `message ${index}`,
    streaming: false,
  }))

  const tail = transcriptTail(items)
  assert.equal(tail.length, MAX_TRANSCRIPT_ITEMS)
  assert.equal('id' in tail[0] ? tail[0].id : '', 'a800')
  const last = tail.at(-1)
  assert.match(last?.kind === 'assistant' ? last.content : '', /LAST$/)
  assert.deepEqual(parseTranscript(JSON.stringify(tail)), tail)
  assert.deepEqual(parseTranscript('{broken'), [])
  assert.deepEqual(transcriptTail(items, 0), [])
})

test('transient send morph state is never restored from persistence', () => {
  const items: Timelineitem[] = [{ kind: 'user', id: 'u1', mid: '', content: 'hello', morphing: true, morphArrived: true }]
  const saved = transcriptTail(items)
  assert.deepEqual(saved, [{ kind: 'user', id: 'u1', mid: '', content: 'hello' }])
  assert.deepEqual(parseTranscript(JSON.stringify(items)), [
    { kind: 'user', id: 'u1', mid: '', content: 'hello', morphing: false, morphArrived: false },
  ])
})

test('chat templates retain dynamic measurement and lazy image contracts', async () => {
  const chat = await readFile(new URL('../src/views/ChatView.vue', import.meta.url), 'utf8')
  const toolCard = await readFile(new URL('../src/components/ToolCardItem.vue', import.meta.url), 'utf8')
  const fileInline = await readFile(new URL('../src/components/FileInline.vue', import.meta.url), 'utf8')

  assert.match(chat, /<DynamicScroller\b/)
  assert.match(chat, /<DynamicScrollerItem\b/)
  assert.match(chat, /key-field="key"/)
  assert.match(chat, /:emit-resize="true"/)
  assert.match(chat, /@resize="follow"/)
  assert.doesNotMatch(chat, /page-mode/)
  // 空态必须与虚拟列表互斥：RecycleScroller 的 empty 插槽是无条件渲染的，
  // 一旦写进滚动内容就会把空态格言一直顶在对话上方（1.5.2o4beta 回归点）。
  assert.match(chat, /v-if="blocks\.length > 0"/)
  assert.match(chat, /v-if="blocks\.length === 0" class="stream"/)
  assert.doesNotMatch(chat, /template #empty/)
  assert.match(toolCard, /loading="lazy"\s+decoding="async"/)
  assert.match(fileInline, /loading="lazy"\s+decoding="async"/)
})

test('every slash command in the composer has a click-to-run action', async () => {
  const composer = await readFile(new URL('../src/components/Composer.vue', import.meta.url), 'utf8')
  const listed = [...composer.matchAll(/\{ name: '(\/[a-z_]+)', desc:/g)].map(match => match[1])
  assert.ok(listed.length >= 7, `expected the slash-command list, got ${listed.length}`)

  const runners = composer.slice(composer.indexOf('const COMMAND_RUNNERS'))
  for (const name of listed) {
    assert.ok(
      runners.includes(`'${name}':`),
      `${name} 必须有点击即执行的实现（COMMAND_RUNNERS）`,
    )
  }
  // 确认弹窗必须是唯一的执行入口，不能退回「填进输入框让用户自己发」。
  assert.match(composer, /@click="requestCommand\(c\)"/)
  assert.doesNotMatch(composer, /class="compact-confirm"/)
})
