<script setup lang="ts">
/**
 * 插件页（1.5.4 第 11 项）。
 *
 * 一页做三件事：
 *   ① 列出本机 `~/.coomi/plugins/` 里的插件，逐个开关；
 *   ② 把接口文档写清楚（这份文字与 GitHub 的 docs/PLUGINS.md 同源）；
 *   ③ 独占位冲突、加载错误、提示词编辑都在这里可见。
 *
 * 为什么文档要放在软件内：写插件的人不一定能翻到仓库，而"接口怎么用"
 * 恰恰是他们打开这一页时最想知道的事。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PageHead from '@/components/PageHead.vue'
import CoomiIcon from '@/components/CoomiIcon.vue'
import { goBack } from '@/bridge/navigation'
import { pluginErrors } from '@/plugins/host'
import {
  conflicts, enabledCount, lastError, loading, overview, plugins, pluginsDir,
  refresh, savePrompt, setEnabled, togglingId,
} from '@/stores/plugins'

const router = useRouter()
/** 每个插件展开后显示「提示词」编辑框。 */
const openId = ref('')
const promptDrafts = ref<Record<string, string>>({})
const docOpen = ref(false)

watch(overview, value => {
  if (!value) return
  // 引擎里的静态 prompt + 前端保存的动态 prompt 一起放进编辑框，方便直接改。
  for (const plugin of value.plugins) {
    if (promptDrafts.value[plugin.id] == null) promptDrafts.value[plugin.id] = plugin.prompt
  }
}, { immediate: true })

const slotLabels: Record<string, string> = {
  topbar: '顶栏右侧',
  composer: '输入框左侧',
  'chat-overlay': '会话浮层',
  sidebar: '侧边栏底部',
  pet: '桌宠层',
  settings: '设置页',
}

function slotLabel(id: string): string {
  return slotLabels[id] ?? id
}

function toggleDoc(id: string) {
  openId.value = openId.value === id ? '' : id
}

async function submitPrompt(id: string) {
  await savePrompt(id, promptDrafts.value[id] ?? '')
}

const enabledText = computed(() => `${enabledCount.value} 个已启用`)

onMounted(refresh)
</script>

<template>
  <div class="page">
    <PageHead title="插件" @back="goBack(router, 'settings')" />
    <main class="body">
      <p v-if="lastError" class="notice error">{{ lastError }}</p>

      <section class="card intro">
        <p class="lead">
          插件是给<strong>界面和你</strong>加东西的扩展：自己的 UI 位置、动效、图案、桌宠，
          也可以往系统提示词里加一段规则。它和 Skill（给模型加做法）、MCP（给模型加工具）不是一回事。
        </p>
        <p class="lead warn">
          <CoomiIcon name="shield" :size="14" />
          <span>插件脚本与页面同权运行（能读写页面 DOM 与本地存储），只安装你信任的插件。</span>
        </p>
        <p class="dir">插件目录：<code>{{ pluginsDir || '~/.coomi/plugins' }}</code></p>
        <div class="actions">
          <button class="btn ghost" :disabled="loading" @click="refresh">
            <CoomiIcon name="refresh" :size="15" />{{ loading ? '扫描中…' : '重新扫描' }}
          </button>
          <span class="count">{{ enabledText }}</span>
        </div>
      </section>

      <p v-for="conflict in conflicts" :key="conflict.key" class="notice warn">
        冲突：{{ conflict.message }}（{{ conflict.plugins.join(' / ') }}），请在下面关掉其中一个。
      </p>

      <section class="list">
        <p v-if="plugins.length === 0" class="empty">
          还没有插件。在 <code>{{ pluginsDir || '~/.coomi/plugins' }}</code> 下新建一个文件夹，
          放一个 <code>manifest.json</code> 和一个 <code>index.js</code>，再点「重新扫描」。
        </p>
        <article v-for="plugin in plugins" :key="plugin.id" class="plugin" :class="{ off: !plugin.enabled }">
          <header>
            <div class="title">
              <b>{{ plugin.name }}</b>
              <small>v{{ plugin.version || '0.0.0' }}<template v-if="plugin.author"> · {{ plugin.author }}</template></small>
            </div>
            <button
              class="switch" :class="{ on: plugin.enabled }"
              :disabled="togglingId === plugin.id"
              @click="setEnabled(plugin.id, !plugin.enabled)"
            >{{ plugin.enabled ? '已开启' : '已关闭' }}</button>
          </header>

          <p v-if="plugin.description" class="desc">{{ plugin.description }}</p>

          <div class="chips">
            <span v-for="slot in plugin.slots" :key="slot" class="chip">{{ slotLabel(slot) }}</span>
            <span v-for="key in plugin.exclusive" :key="'x' + key" class="chip exclusive">独占 {{ key }}</span>
            <span v-for="permission in plugin.permissions" :key="permission" class="chip perm">{{ permission }}</span>
          </div>

          <p v-for="message in plugin.errors" :key="message" class="warn-line">⚠ {{ message }}</p>
          <p v-if="pluginErrors[plugin.id]" class="warn-line">⚠ 运行错误：{{ pluginErrors[plugin.id] }}</p>

          <div class="row-actions">
            <button class="link" @click="toggleDoc(plugin.id)">
              {{ openId === plugin.id ? '收起提示词' : '编辑提示词' }}
            </button>
            <code class="id">id: {{ plugin.id }}</code>
          </div>

          <div v-if="openId === plugin.id" class="prompt-box">
            <textarea
              v-model="promptDrafts[plugin.id]"
              rows="4"
              spellcheck="false"
              placeholder="写在这里的文字会在下一轮请求时拼进系统提示词（纯聊天模式除外）"
            />
            <div class="row-actions">
              <button class="btn ghost" @click="submitPrompt(plugin.id)"><CoomiIcon name="check" :size="14" />保存提示词</button>
              <span class="hint">manifest 里的 prompt 是默认值；这里保存的会覆盖它。</span>
            </div>
          </div>
        </article>
      </section>

      <section class="card">
        <button class="doc-toggle" @click="docOpen = !docOpen">
          <CoomiIcon :name="docOpen ? 'chevronUp' : 'chevronDown'" :size="15" />
          插件接口文档（写一个自己的插件）
        </button>
        <div v-if="docOpen" class="doc">
          <h4>1. 目录结构</h4>
          <pre>~/.coomi/plugins/my-pet/
├── manifest.json   # 必需
├── index.js        # 默认入口，可用 manifest.entry 改
├── style.css       # 可选，自动注入
└── assets/icon.png # 可选，用 /api/plugins/my-pet/asset/assets/icon.png 取</pre>

          <h4>2. manifest.json</h4>
          <pre>{
  "id": "my-pet",
  "name": "我的桌宠",
  "version": "1.0.0",
  "author": "你",
  "description": "屏幕右下角的一只小黑猫",
  "entry": "index.js",
  "apiVersion": 1,
  "prompt": "回答时偶尔用「喵」结尾。",
  "slots": ["pet", "composer"],
  "exclusive": ["pet"],
  "permissions": ["ui", "prompt"],
  "enabledByDefault": false
}</pre>
          <p class="doc-note">
            <code>slots</code> 只能写宿主认识的插槽：topbar / composer / chat-overlay / sidebar / pet / settings。
            <code>exclusive</code> 声明独占位（例如只能有一只桌宠），两个插件抢同一个独占位会被标红。
          </p>

          <h4>3. index.js：拿到的 comax 对象</h4>
          <pre>// 注册一个插槽；host 是宿主给你的容器元素，随便改它的内容与样式
comax.slot('composer', (host) =&gt; {
  host.innerHTML = '&lt;button&gt;点我&lt;/button&gt;'
  host.querySelector('button').onclick = () =&gt; comax.toast('喵')
  return () =&gt; { host.innerHTML = '' }   // 可选：卸载时清理
})

// 往系统提示词里加一段（会保存到引擎，下一轮生效）
comax.prompt('回答尽量简短。')

// 注入样式
comax.css('.my-badge { color: #5b6cff; }')

// 事件钩子：turn-start / turn-end / message / ready
comax.on('turn-end', (payload) =&gt; console.log('这一轮结束', payload))

// 桌宠：face 或 html 二选一，可拖动
comax.pet({ face: '🐈‍⬛', size: 64, bob: 6, onClick: () =&gt; comax.toast('喵～') })

// 私有存储（按插件命名空间隔离）
comax.storage.set('count', (comax.storage.get('count', 0) || 0) + 1)

// 轻提示
comax.toast('插件已就绪')</pre>

          <h4>4. 能做什么、不能做什么</h4>
          <ul class="doc-list">
            <li>能：改插槽里的 DOM、加动画/图案、放桌宠、注入提示词与样式、监听会话事件。</li>
            <li>能：用 <code>fetch</code> 访问网络（受页面同源策略约束），用 localStorage 存小状态。</li>
            <li>不能：调用模型、读写引擎内部文件、注册新的工具函数 —— 那些请用 MCP 或 Skill。</li>
            <li>不能：在用户没开启时生效。开关存在 <code>~/.coomi/config/plugins.json</code>。</li>
          </ul>

          <h4>5. 调试</h4>
          <p class="doc-note">
            插件抛出的异常会显示在上面每个插件的「运行错误」里，不会连累其它插件。
            改完 <code>index.js</code> 后点「重新扫描」即可重载（不必重启引擎）。
          </p>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.page { display: flex; flex-direction: column; height: 100%; min-height: 0; background: var(--page); }
.body { flex: 1; min-height: 0; overflow-y: auto; padding: 14px 12px calc(var(--safe-bottom) + 24px); display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.body > * { flex: 0 0 auto; }
.card { border: 1px solid var(--border); border-radius: var(--r-card); background: var(--bg); padding: 12px 13px; display: flex; flex-direction: column; gap: 9px; min-width: 0; }
.lead { margin: 0; color: var(--text-2); font-size: 12.6px; line-height: 1.65; }
.lead.warn { display: flex; gap: 7px; color: var(--orange); }
.lead.warn :deep(svg) { flex-shrink: 0; margin-top: 1px; }
.dir { margin: 0; color: var(--text-3); font-size: 11.5px; overflow-wrap: anywhere; }
.dir code { font-family: var(--font-mono); font-size: 11px; }
.actions { display: flex; align-items: center; gap: 9px; }
.count { color: var(--text-3); font-size: 11.5px; }
.btn { display: inline-flex; align-items: center; gap: 6px; min-height: 34px; padding: 0 12px; border: 0; border-radius: 10px; font-size: 12.5px; font-weight: 600; }
.btn.ghost { background: var(--fill); color: var(--text-2); }
.btn:disabled { opacity: .55; }
.notice { margin: 0; padding: 9px 11px; border-radius: var(--r-md); font-size: 12.2px; line-height: 1.55; overflow-wrap: anywhere; }
.notice.error { background: var(--danger-soft); color: var(--danger); }
.notice.warn { background: var(--fill); color: var(--orange); }
.list { display: flex; flex-direction: column; gap: 9px; }
.plugin { border: 1px solid var(--border); border-radius: var(--r-card); background: var(--bg); padding: 11px 12px; display: flex; flex-direction: column; gap: 7px; min-width: 0; }
.plugin.off { opacity: .78; }
.plugin header { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.title { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.title b { font-size: 13.5px; color: var(--text); overflow-wrap: anywhere; }
.title small { color: var(--text-3); font-size: 10.8px; }
.switch { flex-shrink: 0; min-height: 30px; padding: 0 12px; border: 0; border-radius: 999px; background: var(--fill); color: var(--text-3); font-size: 12px; font-weight: 650; }
.switch.on { background: var(--blue-soft); color: var(--blue); }
.desc { margin: 0; color: var(--text-2); font-size: 12px; line-height: 1.55; }
.chips { display: flex; flex-wrap: wrap; gap: 5px; }
.chip { padding: 2px 8px; border-radius: 999px; background: var(--fill); color: var(--text-3); font-size: 10.5px; }
.chip.exclusive { color: var(--orange); }
.chip.perm { color: var(--blue); }
.warn-line { margin: 0; color: var(--orange); font-size: 11.5px; line-height: 1.5; overflow-wrap: anywhere; }
.row-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.link { padding: 0; border: 0; background: none; color: var(--blue); font-size: 12px; }
.id { color: var(--text-3); font-family: var(--font-mono); font-size: 10.5px; overflow-wrap: anywhere; }
.prompt-box { display: flex; flex-direction: column; gap: 7px; }
.prompt-box textarea { width: 100%; min-width: 0; padding: 9px 10px; border: 1px solid var(--border); border-radius: 10px; background: var(--fill); color: var(--text); font-size: 12.5px; line-height: 1.5; font-family: var(--font-mono); }
.hint { color: var(--text-3); font-size: 10.8px; }
.empty { margin: 0; padding: 16px 12px; border-radius: var(--r-card); background: var(--bg); color: var(--text-3); font-size: 12.5px; line-height: 1.6; }
.empty code { font-family: var(--font-mono); font-size: 11px; }
.doc-toggle { display: flex; align-items: center; gap: 7px; border: 0; background: none; color: var(--text-2); font-size: 13px; font-weight: 650; padding: 0; }
.doc { display: flex; flex-direction: column; gap: 8px; }
.doc h4 { margin: 6px 0 0; color: var(--text); font-size: 12.5px; }
.doc pre { margin: 0; padding: 10px; border-radius: 10px; background: var(--code-bg); color: var(--code-text); font-family: var(--font-mono); font-size: 10.8px; line-height: 1.55; white-space: pre-wrap; word-break: break-word; overflow-x: auto; }
.doc-note { margin: 0; color: var(--text-3); font-size: 11.5px; line-height: 1.6; }
.doc-note code, .doc-list code { font-family: var(--font-mono); font-size: 10.8px; }
.doc-list { margin: 0; padding-left: 18px; color: var(--text-2); font-size: 11.8px; line-height: 1.7; }
</style>
