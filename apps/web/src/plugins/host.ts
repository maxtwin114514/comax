/**
 * 插件宿主（前端侧）。
 *
 * 引擎只负责「读 manifest、存开关、拼提示词、发静态资源」；
 * 真正让插件动起来的是这里：把 `index.js` 求值，交给它一个 `comax` 对象。
 *
 * 设计取舍（写清楚，避免以后有人以为这是沙箱）：
 *   · 插件代码用 `new Function('comax', code)` 执行，**没有** iframe / worker 隔离，
 *     因此它和页面同权（能碰 DOM、localStorage）。这等价于用户自己写一段脚本，
 *     所以「装插件」本身就该是一个需要用户确认的动作，而不是偷偷加载；
 *   · 每个插件只能拿到自己的 `comax`，其中 UI 插槽是白名单（见引擎 PLUGIN_SLOTS）；
 *   · 一处出错不影响别人：每个插件的求值/回调都单独 try/catch，并记录到
 *     `pluginErrors`，在插件页里能看到具体堆栈。
 */
import { ref } from 'vue'

export interface PluginManifestView {
  id: string
  name: string
  version: string
  author: string
  description: string
  entry: string
  api_version: number
  prompt: string
  slots: string[]
  exclusive: string[]
  permissions: string[]
  enabled: boolean
  has_style: boolean
  has_entry: boolean
  errors: string[]
}

export interface PluginOverview {
  api_version: number
  slots: string[]
  dir: string
  plugins: PluginManifestView[]
  slot_usage: Record<string, string[]>
  conflicts: { kind: string; key: string; plugins: string[]; message: string }[]
}

/** 插槽名 → 已挂载的渲染函数。组件里用 `PluginSlot` 读取。 */
export interface PluginContribution {
  pluginId: string
  /** 直接操作宿主容器元素：布局与动效由插件自己决定。 */
  render: (host: HTMLElement) => void | (() => void)
}

type Hook = (payload: unknown) => void

export interface PluginApi {
  id: string
  apiVersion: number
  /** 注册插槽渲染。返回清理函数（卸载时调用）。 */
  slot: (name: string, render: (host: HTMLElement) => void | (() => void)) => void
  /** 往系统提示词里加一段（前端保存到引擎，下一轮生效）。 */
  prompt: (text: string) => void
  /** 注入样式；同一插件重复调用会替换上一次的。 */
  css: (text: string) => void
  /** 事件钩子：turn-start / turn-end / message / ready。 */
  on: (event: string, hook: Hook) => void
  /** 桌宠：在 pet 层放一个可拖动的小家伙。 */
  pet: (spec: PetSpec) => void
  /** 页内轻提示。 */
  toast: (text: string) => void
  /** 插件私有存储（localStorage 命名空间隔离）。 */
  storage: { get: <T>(key: string, fallback?: T) => T | undefined; set: (key: string, value: unknown) => void }
}

export interface PetSpec {
  /** 用一个字符/emoji 当身体，或提供一段 HTML。 */
  face?: string
  html?: string
  size?: number
  /** 待机时的上下浮动幅度（px）。 */
  bob?: number
  /** 点击时的反应。 */
  onClick?: () => void
}

export const contributions = ref<Record<string, PluginContribution[]>>({})
export const pluginErrors = ref<Record<string, string>>({})
export const loadedPlugins = ref<string[]>([])

const hooks: Record<string, Record<string, Hook[]>> = {}
const promptDrafts: Record<string, string> = {}
const styleTags: Record<string, HTMLStyleElement> = {}
let petLayer: HTMLElement | null = null

/** 桌宠层由 App 挂载时注册，插件可能在层出现之前就调用 `pet()`。 */
export function registerPetLayer(element: HTMLElement | null) {
  petLayer = element
}

function emit(event: string, payload?: unknown) {
  for (const [pluginId, handlers] of Object.entries(hooks)) {
    for (const handler of handlers[event] ?? []) {
      try {
        handler(payload)
      } catch (error) {
        pluginErrors.value = { ...pluginErrors.value, [pluginId]: String(error) }
      }
    }
  }
}

export function emitPluginEvent(event: string, payload?: unknown) {
  emit(event, payload)
}

function ensureStyle(pluginId: string): HTMLStyleElement {
  let tag = styleTags[pluginId]
  if (!tag) {
    tag = document.createElement('style')
    tag.dataset.plugin = pluginId
    document.head.appendChild(tag)
    styleTags[pluginId] = tag
  }
  return tag
}

/** 由宿主写入插件自带的 style.css（卸载时会被 `unloadPlugin` 一起清掉）。 */
export function setPluginCss(pluginId: string, css: string) {
  ensureStyle(pluginId).textContent = css
}

function makePet(pluginId: string, spec: PetSpec) {
  if (!petLayer) return
  const node = document.createElement('div')
  node.className = 'comax-pet'
  node.dataset.plugin = pluginId
  const size = Math.max(24, Math.min(160, spec.size ?? 56))
  node.style.width = `${size}px`
  node.style.height = `${size}px`
  if (spec.html) {
    node.innerHTML = spec.html
  } else {
    node.textContent = spec.face ?? '🐾'
  }
  node.style.fontSize = `${Math.round(size * 0.72)}px`
  if (spec.bob) node.style.setProperty('--bob', `${spec.bob}px`)
  node.addEventListener('click', () => spec.onClick?.())
  // 简单的拖动：按住移动，松手留在原地（位置存到插件自己的存储里）。
  let dragging = false
  let offsetX = 0
  let offsetY = 0
  node.addEventListener('pointerdown', event => {
    dragging = true
    node.setPointerCapture(event.pointerId)
    const rect = node.getBoundingClientRect()
    offsetX = event.clientX - rect.left
    offsetY = event.clientY - rect.top
  })
  node.addEventListener('pointermove', event => {
    if (!dragging) return
    node.style.left = `${event.clientX - offsetX}px`
    node.style.top = `${event.clientY - offsetY}px`
    node.style.bottom = 'auto'
    node.style.right = 'auto'
  })
  node.addEventListener('pointerup', event => {
    dragging = false
    try { node.releasePointerCapture(event.pointerId) } catch { /* 指针已释放 */ }
  })
  petLayer.appendChild(node)
  return node
}

/** 求值一个插件的入口代码。重复调用会先卸载旧实例。 */
export function activatePlugin(manifest: PluginManifestView, code: string) {
  unloadPlugin(manifest.id)
  const cleanups: (() => void)[] = []
  const api: PluginApi = {
    id: manifest.id,
    apiVersion: manifest.api_version,
    slot(name, render) {
      if (!manifest.slots.includes(name)) {
        // 引擎侧已经过滤过白名单，这里再兜一层：防止 JS 里临时用一个没声明的槽位。
        throw new Error(`插件未在 manifest.slots 中声明插槽 ${name}`)
      }
      const list = contributions.value[name] ?? []
      contributions.value = {
        ...contributions.value,
        [name]: [...list, { pluginId: manifest.id, render }],
      }
    },
    prompt(text) {
      promptDrafts[manifest.id] = text
      // 交给上层（store）落盘到引擎；宿主不直接发请求，避免循环依赖。
      window.dispatchEvent(new CustomEvent('comax:plugin-prompt', { detail: { id: manifest.id, text } }))
    },
    css(text) {
      ensureStyle(manifest.id).textContent = text
    },
    on(event, hook) {
      hooks[manifest.id] ||= {}
      hooks[manifest.id][event] ||= []
      hooks[manifest.id][event].push(hook)
    },
    pet(spec) {
      const node = makePet(manifest.id, spec)
      if (node) cleanups.push(() => node.remove())
    },
    toast(text) {
      window.dispatchEvent(new CustomEvent('comax:plugin-toast', { detail: { id: manifest.id, text } }))
    },
    storage: {
      get<T>(key: string, fallback?: T): T | undefined {
        try {
          const raw = localStorage.getItem(`comax.plugin.${manifest.id}.${key}`)
          return raw == null ? fallback : (JSON.parse(raw) as T)
        } catch {
          return fallback
        }
      },
      set(key: string, value: unknown) {
        try {
          localStorage.setItem(`comax.plugin.${manifest.id}.${key}`, JSON.stringify(value))
        } catch {
          /* 配额满了就算了，插件不该因此崩掉 */
        }
      },
    },
  }
  try {
    // eslint-disable-next-line no-new-func
    const factory = new Function('comax', `"use strict";\n${code}\n//# sourceURL=comax-plugin://${manifest.id}/${manifest.entry}`)
    const result = factory(api)
    if (typeof result === 'function') cleanups.push(result as () => void)
    loadedPlugins.value = [...loadedPlugins.value, manifest.id]
    const next = { ...pluginErrors.value }
    delete next[manifest.id]
    pluginErrors.value = next
    emit('ready')
  } catch (error) {
    pluginErrors.value = { ...pluginErrors.value, [manifest.id]: error instanceof Error ? error.message : String(error) }
  }
  cleanupsRef[manifest.id] = cleanups
}

const cleanupsRef: Record<string, (() => void)[]> = {}

export function unloadPlugin(id: string) {
  for (const [slot, list] of Object.entries(contributions.value)) {
    const filtered = list.filter(item => item.pluginId !== id)
    if (filtered.length === 0) delete contributions.value[slot]
    else contributions.value = { ...contributions.value, [slot]: filtered }
  }
  for (const cleanup of cleanupsRef[id] ?? []) {
    try { cleanup() } catch { /* 清理失败不阻塞其它插件 */ }
  }
  delete cleanupsRef[id]
  delete hooks[id]
  styleTags[id]?.remove()
  delete styleTags[id]
  loadedPlugins.value = loadedPlugins.value.filter(item => item !== id)
}

export function promptDraftFor(id: string): string | undefined {
  return promptDrafts[id]
}
