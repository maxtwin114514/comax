<script setup lang="ts">
/**
 * 云端访问：把手机上的会话通过服务器反代给外部浏览器看。
 *
 * 拓扑（见 apps/coomi-rs/ui/src/cloud_tunnel.rs 与 cloud_relay.cjs）：
 *   浏览器 ──▶ 服务器 nginx ──▶ 中继:22336 ──隧道──▶ 手机引擎:8765
 *
 * 手机在运营商 NAT 后面，服务器主动连不上手机，所以隧道必须由手机先拨出去。
 * 点「连接」之后，引擎会在后台维持这条隧道并自动重拨（断网/切网/锁屏被掐断
 * 都能自己恢复），所以这个页面是轮询状态的，不是"点一下就连好了"。
 *
 * 为什么不是 SSH 反向隧道：手机侧的运行环境里没有 ssh/sshpass（实测只有
 * bash/curl/node/python3/sh），那条路在真机上是走不通的。
 *
 * 权限边界（产品决定）：云端只能看会话、只能对话，改不了任何配置。
 * 拦截在引擎侧（cloud_request_allowed），不是前端藏按钮 —— 藏按钮挡不住人。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHead from '@/components/PageHead.vue'
import CoomiIcon from '@/components/CoomiIcon.vue'
import { apiGet, apiSend } from '@/bridge/http'
import { goBack } from '@/bridge/navigation'

interface TunnelStatus {
  /** 用户是否要求连接（点过「连接」且没点「断开」）。 */
  desired: boolean
  /** 是否已经连上服务器。 */
  running: boolean
  last_error: string
  connected_at: number
}

interface CloudStatus {
  enabled: boolean
  host: string
  user: string
  domain: string
  tunnel_port: number
  has_password: boolean
  access_url: string
  tunnel: TunnelStatus
  updated_at: number
}

const router = useRouter()

const host = ref('')
const user = ref('')
const password = ref('')
const domain = ref('')
const tunnelPort = ref(22336)
const enabled = ref(false)

const status = ref<CloudStatus | null>(null)
const loading = ref(false)
const saving = ref(false)
const connecting = ref(false)
const error = ref('')
const notice = ref('')
const script = ref('')
const copied = ref('')

let poll: ReturnType<typeof setInterval> | null = null
/** 部署脚本默认收起：脚本两三百行，铺开会把这个页面的卡片挤扁（真机上就是"界面重叠"）。 */
const scriptOpen = ref(false)

const running = computed(() => status.value?.tunnel.running === true)
const accessUrl = computed(() => status.value?.access_url ?? '')

function apply(next: CloudStatus) {
  status.value = next
  host.value = next.host
  user.value = next.user
  domain.value = next.domain
  tunnelPort.value = next.tunnel_port
  enabled.value = next.enabled
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    apply(await apiGet<CloudStatus>('/api/cloud-access'))
  } catch (cause) {
    error.value = `读取云端访问配置失败：${String(cause)}`
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const payload: Record<string, unknown> = {
      host: host.value.trim(),
      user: user.value.trim(),
      domain: domain.value.trim(),
      tunnel_port: tunnelPort.value,
      enabled: enabled.value,
    }
    // 密码留空表示「不改动已保存的密码」，避免每次保存都要重打一遍
    if (password.value) payload.password = password.value
    apply(await apiSend<CloudStatus>('/api/cloud-access', 'PUT', payload))
    password.value = ''
    notice.value = '已保存。记得在服务器上跑一次部署脚本。'
  } catch (cause) {
    error.value = `保存失败：${String(cause)}`
  } finally {
    saving.value = false
  }
}

async function toggleTunnel(action: 'start' | 'stop') {
  connecting.value = true
  error.value = ''
  notice.value = ''
  try {
    // 先落盘再连，保证引擎用的是刚填的参数
    apply(await apiSend<CloudStatus>('/api/cloud-access', 'PUT', {
      host: host.value.trim(),
      user: user.value.trim(),
      domain: domain.value.trim(),
      tunnel_port: tunnelPort.value,
      enabled: true,
      ...(password.value ? { password: password.value } : {}),
    }))
    password.value = ''
    const next = await apiSend<CloudStatus>('/api/cloud-access/tunnel', 'POST', { action })
    apply(next)
    notice.value = action === 'start'
      ? (next.tunnel.running ? '隧道已建立' : '已开始连接，引擎会在后台保持并自动重拨')
      : '已断开，引擎不会自动重连（再点「连接」即可恢复）'
  } catch (cause) {
    error.value = `${action === 'start' ? '连接' : '断开'}失败：${String(cause)}`
    await load()
  } finally {
    connecting.value = false
  }
}

async function loadScript() {
  error.value = ''
  try {
    const data = await apiGet<{ script: string }>('/api/cloud-access/server-script')
    script.value = data.script
    scriptOpen.value = true
    notice.value = '把下面的脚本复制到服务器上，用 root 执行一次'
  } catch (cause) {
    error.value = `生成脚本失败：${String(cause)}`
  }
}

async function copy(text: string, what: string) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = what
    setTimeout(() => { if (copied.value === what) copied.value = '' }, 1600)
  } catch {
    error.value = '复制失败，请长按选中文本手动复制'
  }
}

onMounted(() => {
  void load()
  poll = setInterval(() => { void load() }, 5000)
})
onBeforeUnmount(() => { if (poll) clearInterval(poll) })
</script>

<template>
  <div class="page">
    <PageHead title="云端访问" @back="goBack(router, 'settings')" />
    <main class="body">
      <p v-if="error" class="notice error">{{ error }}</p>
      <p v-if="notice" class="notice ok">{{ notice }}</p>

      <section class="card">
        <p class="lead">
          在外面用浏览器查看并继续这台手机上的会话。手机在运营商 NAT 后面，
          所以由手机主动向你的服务器拨一条隧道；服务器上的中继把它接住，
          nginx 再把域名反代到中继 —— 浏览器打开域名就能看到这台手机。
        </p>
        <p class="lead warn">
          <CoomiIcon name="shield" :size="14" />
          云端是<strong>只读对话</strong>模式：能看会话、能发消息，改不了任何配置（API Key、设置、删会话都不行）。
        </p>
      </section>

      <section class="card">
        <p class="qhead">连接信息</p>
        <label class="field"><span>公网 IP</span>
          <input v-model="host" inputmode="url" placeholder="例如 1.2.3.4" autocomplete="off" />
        </label>
        <label class="field"><span>访问用户名</span>
          <input v-model="user" placeholder="随便起一个，用来给中继鉴权，例如 comax" autocomplete="username" />
        </label>
        <label class="field"><span>密码</span>
          <input v-model="password" type="password" autocomplete="current-password"
                 :placeholder="status?.has_password ? '已保存（留空则不修改）' : '和中继账号配套的密码'" />
          <span class="input-hint">留空表示不修改已保存的密码</span>
        </label>
        <label class="field"><span>服务器访问域名（选填）</span>
          <input v-model="domain" inputmode="url" placeholder="留空则用公网 IP 直连" autocomplete="off" />
          <span class="input-hint">填了域名后，访问地址会显示这个域名；浏览器侧会自动切到宽屏排版</span>
        </label>
        <label class="field"><span>访问端口</span>
          <input
            :value="tunnelPort"
            inputmode="numeric"
            @input="event => tunnelPort = Number((event.target as HTMLInputElement).value.replace(/[^0-9]/g, '')) || 0"
          />
        </label>
        <div class="row-actions">
          <button class="btn ghost" :disabled="saving" @click="save">
            <CoomiIcon name="check" :size="15" />{{ saving ? '保存中…' : '保存' }}
          </button>
          <button v-if="!running" class="btn primary" :disabled="connecting || !host.trim() || !user.trim()" @click="toggleTunnel('start')">
            <CoomiIcon name="plug" :size="15" />{{ connecting ? '连接中…' : '连接' }}
          </button>
          <button v-else class="btn danger" :disabled="connecting" @click="toggleTunnel('stop')">
            <CoomiIcon name="close" :size="15" />断开
          </button>
        </div>
      </section>

      <section class="card card-script">
        <p class="qhead">部署脚本（在服务器上跑一次）</p>
        <p class="hint">
          脚本会用 root 做四件事：把中继程序装到 /opt/comax-cloud、注册成 systemd 服务、
          新增一个 nginx vhost（含 WebSocket 升级）、重载 nginx。
          它只新增自己的文件，不动你已有的站点。
        </p>
        <p class="hint warn">
          <CoomiIcon name="alert" :size="14" />
          <span>开始之前：请先在<strong>云服务商的安全组</strong>里放行 <strong>TCP {{ tunnelPort }}</strong> 端口，
          否则 nginx 也到不了这条隧道。</span>
        </p>
        <div class="row-actions">
          <button class="btn ghost" @click="loadScript"><CoomiIcon name="terminal" :size="15" />生成脚本</button>
          <button v-if="script" class="btn ghost" @click="copy(script, 'script')">
            <CoomiIcon name="copy" :size="15" />{{ copied === 'script' ? '已复制' : '复制' }}
          </button>
          <button v-if="script" class="btn ghost" @click="scriptOpen = !scriptOpen">
            <CoomiIcon :name="scriptOpen ? 'chevronUp' : 'chevronDown'" :size="15" />
            {{ scriptOpen ? '收起' : '展开查看' }}
          </button>
        </div>
        <p v-if="script && !scriptOpen" class="hint">脚本已生成（{{ script.split('\n').length }} 行），点「展开查看」预览或「复制」直接取走。</p>
        <pre v-if="script && scriptOpen" class="script">{{ script }}</pre>
      </section>

      <section v-if="accessUrl" class="card">
        <p class="qhead">访问地址</p>
        <p class="hint">地址里带着引擎访问令牌，等同于密码，别外传。</p>
        <p class="url">{{ accessUrl }}</p>
        <div class="row-actions">
          <button class="btn primary" @click="copy(accessUrl, 'url')">
            <CoomiIcon name="copy" :size="15" />{{ copied === 'url' ? '已复制' : '复制地址' }}
          </button>
        </div>
      </section>

      <section class="card">
        <p class="qhead">隧道状态</p>
        <div class="line"><span>状态</span>
          <strong :class="running ? 'ok' : 'off'">{{ running ? '已连接' : '未连接' }}</strong>
        </div>
        <div v-if="connecting" class="line"><span>进度</span><strong>正在拨号…</strong></div>
        <div v-if="status?.tunnel.desired && !running" class="line">
          <span>进度</span><strong>正在重试（引擎每 15 秒拨一次）</strong>
        </div>
        <div v-if="status?.tunnel.last_error" class="line err-line">
          <span>最近一次错误</span><strong>{{ status.tunnel.last_error }}</strong>
        </div>
        <p class="hint">
          隧道断了引擎会自动重拨（每 15 秒一次），切网、锁屏、手机重启都不需要你手动处理。
          想彻底停下就点「断开」—— 断开后引擎不会再自动连。
        </p>
      </section>
    </main>
  </div>
</template>

<style scoped>
.page { display: flex; flex-direction: column; height: 100%; min-height: 0; background: var(--page); }
.body {
  flex: 1; min-height: 0; min-width: 0; overflow-y: auto; overflow-x: hidden;
  -webkit-overflow-scrolling: touch; overscroll-behavior-y: contain;
  padding: 14px 12px calc(var(--safe-bottom) + 24px);
  display: flex; flex-direction: column; gap: 12px;
}
/*
 * 重叠的真正原因：.body 是一个 overflow-y:auto 的 flex column，而卡片默认
 * flex-shrink:1 —— 内容一多，浏览器先把每张卡片**压扁**（不是滚动），
 * 卡片内部的输入框、文字就互相叠在一起。修法是让子项一律不收缩。
 */
.body > * { flex: 0 0 auto; }
.card {
  flex: 0 0 auto; min-width: 0;
  border: 1px solid var(--border); border-radius: var(--r-card); background: var(--bg);
  padding: 12px 13px; display: flex; flex-direction: column; gap: 10px;
}
.lead { margin: 0; font-size: 12.6px; line-height: 1.65; color: var(--text-2); overflow-wrap: anywhere; }
.lead.warn { display: flex; gap: 7px; color: var(--orange); }
.lead.warn :deep(svg) { flex-shrink: 0; margin-top: 1px; }
.qhead { margin: 0; flex: 0 0 auto; font-size: 12px; font-weight: 700; color: var(--text-3); }
.hint { margin: 0; font-size: 12.1px; line-height: 1.6; color: var(--text-3); overflow-wrap: anywhere; }
.hint.warn { display: flex; gap: 6px; color: var(--orange); }
.hint.warn :deep(svg) { flex-shrink: 0; margin-top: 1px; }
.field { display: flex; flex-direction: column; gap: 5px; flex: 0 0 auto; min-width: 0; }
.field > span { color: var(--text-3); font-size: 12px; font-weight: 600; }
.field input {
  display: block; width: 100%; min-width: 0; flex: 0 0 auto;
  padding: 9px 11px; border: 1px solid var(--border); border-radius: 10px;
  background: var(--fill); color: var(--text); font-size: 14px; line-height: 1.4;
}
.duo { display: grid; grid-template-columns: 1fr 1fr; gap: 9px; }
.row-actions { display: flex; gap: 7px; flex-wrap: wrap; flex: 0 0 auto; }
.btn { display: inline-flex; align-items: center; gap: 6px; flex: 0 0 auto; min-height: 38px; padding: 0 13px; border: 0; border-radius: 10px; font-size: 13px; font-weight: 600; }
.btn.primary { background: var(--blue); color: #fff; }
.btn.ghost { background: var(--fill); color: var(--text-2); }
.btn.danger { background: var(--danger-soft); color: var(--danger); }
.btn:disabled { opacity: .5; }
.notice { margin: 0; flex: 0 0 auto; padding: 9px 11px; border-radius: var(--r-md); font-size: 12.5px; line-height: 1.55; overflow-wrap: anywhere; }
.notice.error { background: var(--danger-soft); color: var(--danger); }
.notice.ok { background: var(--blue-soft); color: var(--blue); }
.script {
  margin: 0; padding: 10px; border-radius: 10px;
  background: var(--code-bg); color: var(--code-text);
  font-family: var(--font-mono); font-size: 11.2px; line-height: 1.5;
  max-height: 300px; max-width: 100%; min-width: 0; overflow: auto;
  /* pre 会让长行撑破容器、横向溢出压到别的内容上；pre-wrap + 断词才安全 */
  white-space: pre-wrap; word-break: break-word;
}
.url { margin: 0; min-width: 0; padding: 9px 10px; border-radius: 10px; background: var(--fill); color: var(--blue); font-family: var(--font-mono); font-size: 11.6px; overflow-wrap: anywhere; word-break: break-all; }
.line { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; font-size: 12.6px; flex: 0 0 auto; min-width: 0; }
.line > span { color: var(--text-3); }
.line strong { color: var(--text); text-align: right; overflow-wrap: anywhere; }
.line strong.ok { color: var(--ok); }
.line strong.off { color: var(--text-3); }
.err-line strong { color: var(--danger); font-weight: 500; font-size: 11.8px; }
.input-hint { font-size: 11px; color: var(--text-3); margin-top: -2px; }
.card-script { background: linear-gradient(180deg, var(--bg) 0%, var(--fill) 100%); }
</style>
