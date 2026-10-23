/**
 * 插件状态：从引擎拉清单、加载/卸载入口代码、保存开关与提示词。
 *
 * 所有网络调用只走既有 http bridge（`apiGet`/`apiSend`），
 * 加载顺序固定为：先拿 overview → 逐个 fetch 入口 → activatePlugin。
 * 任何一步失败都只影响那一个插件，并把错误显示在插件页里。
 */
import { computed, ref } from 'vue'
import { apiGet, apiSend } from '@/bridge/http'
import {
  activatePlugin,
  pluginErrors,
  setPluginCss,
  unloadPlugin,
  type PluginManifestView,
  type PluginOverview,
} from '@/plugins/host'

export const overview = ref<PluginOverview | null>(null)
export const loading = ref(false)
export const lastError = ref('')
export const togglingId = ref('')

export const plugins = computed<PluginManifestView[]>(() => overview.value?.plugins ?? [])
export const conflicts = computed(() => overview.value?.conflicts ?? [])
export const enabledCount = computed(() => plugins.value.filter(item => item.enabled).length)
export const pluginsDir = computed(() => overview.value?.dir ?? '')

/** 插件入口取回后缓存在这里：同一次运行里不重复下载。 */
const codeCache = new Map<string, string>()

async function fetchText(path: string): Promise<string> {
  const response = await fetch(path, { credentials: 'same-origin' })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return await response.text()
}

/**
 * 加载所有已启用插件。
 *
 * 幂等：可以反复调用（引擎里装了新插件后点「重新扫描」）。
 * 先全部卸载再按最新清单挂载，避免残留上一份贡献。
 */
export async function loadEnabledPlugins() {
  const current = overview.value
  for (const plugin of current?.plugins ?? []) unloadPlugin(plugin.id)
  for (const plugin of plugins.value) {
    if (!plugin.enabled) continue
    if (plugin.errors.length > 0 || !plugin.has_entry) continue
    try {
      let code = codeCache.get(plugin.id)
      if (code == null) {
        code = await fetchText(`/api/plugins/${encodeURIComponent(plugin.id)}/asset/${plugin.entry}`)
        codeCache.set(plugin.id, code)
      }
      activatePlugin(plugin, code)
      // 顺序很重要：activatePlugin 内部会先卸载旧实例（含旧样式），
      // 所以插件自带的 style.css 必须在这之后再写入。
      if (plugin.has_style) {
        const css = await fetchText(`/api/plugins/${encodeURIComponent(plugin.id)}/asset/style.css`)
        setPluginCss(plugin.id, css)
      }
    } catch (error) {
      pluginErrors.value = {
        ...pluginErrors.value,
        [plugin.id]: error instanceof Error ? error.message : String(error),
      }
    }
  }
}

export async function refresh() {
  loading.value = true
  lastError.value = ''
  try {
    overview.value = await apiGet<PluginOverview>('/api/plugins')
    await loadEnabledPlugins()
  } catch (error) {
    lastError.value = String(error)
  } finally {
    loading.value = false
  }
}

export async function setEnabled(id: string, enabled: boolean) {
  togglingId.value = id
  lastError.value = ''
  try {
    overview.value = await apiSend<PluginOverview>(`/api/plugins/${encodeURIComponent(id)}/toggle`, 'POST', { enabled })
    codeCache.delete(id)
    await loadEnabledPlugins()
  } catch (error) {
    lastError.value = String(error)
  } finally {
    togglingId.value = ''
  }
}

export async function savePrompt(id: string, prompt: string) {
  lastError.value = ''
  try {
    overview.value = await apiSend<PluginOverview>(`/api/plugins/${encodeURIComponent(id)}/prompt`, 'POST', { prompt })
  } catch (error) {
    lastError.value = String(error)
  }
}

/** 导入状态：进行中 / 刚导入的 id / 是否覆盖安装了已有插件。 */
export const importing = ref(false)
export const importedId = ref('')
export const importedUpgraded = ref(false)

/**
 * 从手机选一个 .zip 导入成插件（Harness 风格的「装插件」）。
 *
 * 走的是 Android 的文件选择器（CoomiAndroid.importFiles），不是浏览器上传：
 * 手机上用户找的是文件管理器里的 zip，不是"把文件再点一遍上传"。
 * 导入成功后立刻重新扫描并挂载，不用重启引擎。
 */
export async function importZip() {
  const bridge = (window as any).CoomiAndroid
  if (!bridge || typeof bridge.importFiles !== 'function') {
    lastError.value = '当前环境不支持选择文件（请在本机运行，不要走云端）'
    return
  }
  importing.value = true
  importedId.value = ''
  importedUpgraded.value = false
  lastError.value = ''
  try {
    const files = await new Promise<string[]>((resolve, reject) => {
      const listener = (event: Event) => {
        const detail = (event as CustomEvent<{ requestId: string; paths: string[] }>).detail
        window.removeEventListener('coomi:file-imported', listener)
        resolve(detail.paths)
      }
      window.addEventListener('coomi:file-imported', listener)
      bridge.importFiles('plugin-import', 'application/zip', false)
      setTimeout(() => reject(new Error('选择文件超时')), 120_000)
    })
    const path = files[0]
    if (!path) throw new Error('没有选到文件')
    if (!path.toLowerCase().endsWith('.zip')) throw new Error('只接受 .zip 文件')
    // 读成 ArrayBuffer 再交给 multipart。手机上路径是真实文件，fetch 直接读。
    const response = await fetch(`file://localhost${path}`)
    const buffer = await response.arrayBuffer()
    const form = new FormData()
    form.append('file', new Blob([buffer], { type: 'application/zip' }), path.split('/').pop() ?? 'plugin.zip')
    const result = await fetch('/api/plugins/import', { method: 'POST', body: form })
    if (!result.ok) {
      const text = await result.text()
      throw new Error(text || `HTTP ${result.status}`)
    }
    const payload = await result.json()
    importedId.value = payload.imported ?? ''
    importedUpgraded.value = payload.upgraded === true
    overview.value = payload
    codeCache.clear()
    await loadEnabledPlugins()
  } catch (error) {
    lastError.value = String(error)
  } finally {
    importing.value = false
  }
}

/**
 * 彻底删除一个插件（目录 + 开关/动态提示词配置）。
 *
 * 这是删除，不是停用。界面上必须让用户确认。
 */
export async function removePlugin(id: string) {
  togglingId.value = id
  lastError.value = ''
  try {
    overview.value = await apiSend<PluginOverview>(`/api/plugins/${encodeURIComponent(id)}`, 'DELETE')
    codeCache.delete(id)
    unloadPlugin(id)
  } catch (error) {
    lastError.value = String(error)
  } finally {
    togglingId.value = ''
  }
}
