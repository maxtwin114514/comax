/**
 * 记忆库（动态工作流库）契约测试。
 *
 * 这一页的重写边界很容易被无意改掉，所以在源码层面钉住三件事：
 *  1. 记忆库不再渲染笔记（NoteLibrary / 笔记列表已下线）；
 *  2. 运行前必须过确认门（确认窗里有命令白名单）；
 *  3. 引擎侧的 journal/回放接口就是它依赖的那几个路由。
 */
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const view = await readFile(new URL('../src/views/WorkflowLibraryView.vue', import.meta.url), 'utf8')
const router = await readFile(new URL('../src/router/index.ts', import.meta.url), 'utf8')
const engine = await readFile(
  new URL('../../coomi-rs/ui/src/dynwork.rs', import.meta.url),
  'utf8',
)
const sandbox = await readFile(
  new URL('../../coomi-rs/ui/src/workflow_sandbox.cjs', import.meta.url),
  'utf8',
)

test('记忆库页面不再展示笔记', () => {
  assert.doesNotMatch(view, /NoteLibrary/)
  assert.doesNotMatch(view, /\/api\/notes/)
  assert.match(router, /'\/notes',[\s\S]{0,120}WorkflowLibraryView\.vue/)
})

test('运行前有确认门，展示命令白名单与脚本原文', () => {
  assert.match(view, /confirmOpen/)
  assert.match(view, /命令白名单/)
  assert.match(view, /确认运行/)
  assert.match(view, /confirm: true/)
  assert.match(view, /seed_from/)
})

test('页面只依赖记账引擎的公开路由', () => {
  for (const route of [
    "'/api/dynamic-workflows'",
    '`/api/dynamic-workflows/${id}/plan`',
    '`/api/dynamic-workflows/${id}/run`',
    '`/api/dynamic-workflows/runs/${runId.value}/events?since=${cursor}`',
  ]) {
    assert.ok(view.includes(route), `缺少路由调用：${route}`)
  }
})

test('引擎保留 M1 的四条不变量', () => {
  // 缓存键 = kind + 调用点(file:line:col) + 出现序号
  assert.match(sandbox, /function callKey/)
  assert.match(sandbox, /instanceCounters/)
  assert.match(sandbox, /new Error\(\)\.stack/)
  // 回放优先，命中即取记录值；世界调用绝不重跑
  assert.match(engine, /struct ReplayIndex/)
  assert.match(engine, /fn handle_call/)
  // 命令白名单来自脚本文本里的字面量
  assert.match(engine, /read_first_string_literal/)
  assert.match(engine, /命令白名单/)
  // 非确定性拦截（静态扫描 + 运行时抛错）
  assert.match(engine, /Date\.now/)
  assert.match(engine, /Math\.random/)
  assert.match(sandbox, /DeterministicDate/)
  assert.match(sandbox, /DeterministicMath/)
})
