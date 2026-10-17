<script setup lang="ts">
/**
 * 左侧会话抽屉。
 * DeepSeek 的抽屉是「推开主内容」而不是盖住，主体的位移由 ChatView 负责，
 * 这里只管面板自己的滑入、搜索、分组列表和行内操作。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { useConfigStore } from '@/stores/config'
import { formatSessionTime, useSessionsStore, type SessionMeta } from '@/stores/sessions'
import { GLOBAL_SESSION_ID } from '@/bridge/life'
import CoomiIcon from './CoomiIcon.vue'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()

const router = useRouter()
const session = useSessionStore()
const sessions = useSessionsStore()
const config = useConfigStore()

const menuFor = ref<SessionMeta | null>(null)
const renamingId = ref('')
const renameText = ref('')

const isEmpty = computed(() => sessions.groups.length === 0)

/** 全局常驻会话：标题跟随生命体开关/名称，未读显示数字徽标，永不消失。 */
const globalMeta = computed(() =>
  sessions.ensure(GLOBAL_SESSION_ID, config.digitalLifeEnabled ? '数字生命体' : '全局对话'),
)
const globalTitle = computed(() => {
  const metaTitle = globalMeta.value.title
  if (session.lifeUnreadName) return session.lifeUnreadName
  if (config.digitalLifeEnabled) {
    return metaTitle && metaTitle !== '新对话' ? metaTitle : '数字生命体'
  }
  return metaTitle && metaTitle !== '新对话' ? metaTitle : '全局对话'
})
const globalRunning = computed(() => sessions.isRunning(GLOBAL_SESSION_ID))
const isGlobalMeta = (m: SessionMeta | null) => m?.id === GLOBAL_SESSION_ID

// 抽屉一关就把临时态清掉；打开时立即刷新一次各会话的「后台运行中」
// 状态（常驻轮询由 ChatView 全局负责，这里只保证打开瞬间是最新的）。
watch(() => props.open, v => {
  if (!v) {
    menuFor.value = null; renamingId.value = ''
    return
  }
  sessions.refreshRunning()
})

function pick(id: string) {
  if (renamingId.value) return
  session.openSession(id)
  emit('close')
}

function startNew() {
  session.newSession()
  emit('close')
}

function closeMenu() { menuFor.value = null }

/** WebView 里 window.prompt 默认被吞掉，所以重命名走行内输入框。 */
async function beginRename() {
  const m = menuFor.value
  if (!m) return
  renamingId.value = m.id
  renameText.value = m.title
  menuFor.value = null
  await nextTick()
  const el = document.querySelector<HTMLInputElement>('.drawer-root .rename')
  el?.focus()
  el?.select()
}

async function commitRename() {
  if (!renamingId.value) return
  const id = renamingId.value
  if (await sessions.rename(id, renameText.value)) renamingId.value = ''
}

async function doPin() {
  if (!menuFor.value) return
  if (await sessions.togglePin(menuFor.value.id)) closeMenu()
}

function doDelete() {
  if (!menuFor.value) return
  session.deleteSession(menuFor.value.id)
  closeMenu()
}

function go(path: string) {
  // 先收起抽屉再跳路由：App.vue 是 out-in 过渡，如果先 push 再 close，
  // 抽屉退场动画和页面切换动画同时跑，Settings 页面会先被旧页盖住一瞬，
  // 看起来就像「点设置没反应」。
  emit('close')
  nextTick(() => router.push(path))
}
/** 会话累计 token 显示：1.2k / 3.4M。 */
function formatTokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k'
  return String(n)
}

function openDashboard() {
  emit('close')
  if (window.CoomiAndroid?.openDashboard) window.CoomiAndroid.openDashboard()
  else window.location.href = 'coomi://dashboard'
}
</script>

<template>
  <div class="drawer-root" :class="{ open }">
    <div class="scrim" @click="emit('close')" />

    <aside class="panel" role="dialog" aria-label="会话历史">
      <header class="dhead">
        <div class="sfield">
          <CoomiIcon name="search" :size="17" />
          <input v-model="sessions.query" type="text" placeholder="搜索历史会话" enterkeyhint="search" />
          <button v-if="sessions.query" class="clr" aria-label="清空" @click="sessions.query = ''">
            <CoomiIcon name="close" :size="12" />
          </button>
        </div>
      </header>

      <button class="newrow" @click="startNew">
        <span class="nicon"><CoomiIcon name="pencil" :size="17" /></span>
        <span>开启新对话</span>
      </button>
      <div class="entry-grid" aria-label="工具入口">
        <button class="entry" @click="go('/studio')"><CoomiIcon name="sparkle" :size="17" /><span>AI 工作台·预览</span></button>
        <button class="entry" @click="go('/collab')"><CoomiIcon name="team" :size="17" /><span>协同工作台·预览</span></button>
        <button class="entry" @click="go('/files')"><CoomiIcon name="folder" :size="17" /><span>文件</span></button>
        <button class="entry" @click="go('/im')"><CoomiIcon name="chat" :size="17" /><span>群聊</span></button>
        <button class="entry" @click="go('/catalog')"><CoomiIcon name="cube" :size="17" /><span>拓展</span></button>
        <button class="entry" @click="go('/browser')"><CoomiIcon name="globe" :size="17" /><span>浏览器</span></button>
        <button class="entry" @click="go('/notes')"><CoomiIcon name="notebook" :size="17" /><span>记忆库</span></button>
        <button class="entry" @click="go('/cloud')"><CoomiIcon name="globe" :size="17" /><span>云端访问</span></button>
      </div>
      <button class="taskrow" @click="go('/tasks')">
        <CoomiIcon name="subtask" :size="17" />
        <span>任务中心</span>
        <span v-if="sessions.runningIds.size" class="task-count">{{ sessions.runningIds.size }}</span>
      </button>

      <div class="list">
        <div
          class="row global-row"
          :class="{ cur: session.sessionId === GLOBAL_SESSION_ID }"
          @click="pick(GLOBAL_SESSION_ID)"
        >
          <span class="gicon"><CoomiIcon name="lifeRings" :size="17" /></span>
          <div class="rmain">
            <p class="rtitle">{{ globalTitle }}</p>
            <p class="rmeta">
              <span>{{ formatSessionTime(globalMeta.updatedAt) }}</span>
              <span v-if="session.lifeUnread.length" class="global-badge" aria-label="生命体未读消息">{{ session.lifeUnread.length }}</span>
              <span v-if="globalRunning" class="rspin" aria-label="后台运行中" />
            </p>
          </div>
          <button class="rmore" aria-label="更多" @click.stop="menuFor = globalMeta">
            <CoomiIcon name="more" :size="17" />
          </button>
        </div>
        <p v-if="isEmpty" class="empty">
          还没有历史会话。<br />随便说点什么，标题会用你的第一句话。
        </p>
        <template v-for="g in sessions.groups" :key="g.label">
          <p class="sec-label">{{ g.label }}</p>
          <div
            v-for="m in g.items"
            :key="m.id"
            class="row"
            :class="{ cur: m.id === session.sessionId }"
            @click="pick(m.id)"
          >
            <div class="rmain">
              <input
                v-if="renamingId === m.id"
                v-model="renameText"
                class="rename"
                @click.stop
                @keyup.enter="commitRename"
                @blur="commitRename"
              />
              <p v-else class="rtitle">{{ m.title }}</p>
              <p class="rmeta">
                <CoomiIcon v-if="m.pinned" name="pin" :size="11" />
                <span>{{ formatSessionTime(m.updatedAt) }}</span>
                <template v-if="m.turns">
                  <span>·</span><span>{{ m.turns }} 轮</span>
                </template>
                <template v-if="m.totalTokens">
                  <span>·</span><span class="token-count">{{ formatTokens(m.totalTokens) }} tokens</span>
                </template>
                <span v-if="sessions.isRunning(m.id)" class="rspin" aria-label="后台运行中" />
              </p>
            </div>
            <button class="rmore" aria-label="更多" @click.stop="menuFor = m">
              <CoomiIcon name="more" :size="17" />
            </button>
          </div>
        </template>
      </div>

      <footer class="dfoot">
        <button class="frow console" @click="openDashboard">
          <CoomiIcon name="terminal" :size="20" />
          <span class="fname">返回控制台</span>
        </button>
        <button class="fsq" aria-label="设置" @click="go('/settings')">
          <CoomiIcon name="settings" :size="20" />
        </button>
      </footer>
    </aside>

    <div v-if="menuFor" class="sheet-wrap" @click.self="closeMenu">
      <div class="sheet">
        <p class="sheet-title">{{ menuFor.title }}</p>
          <button class="sheet-item" @click="beginRename">
            <CoomiIcon name="pencil" :size="18" /><span>重命名</span>
          </button>
          <template v-if="!isGlobalMeta(menuFor)">
            <button class="sheet-item" @click="doPin">
              <CoomiIcon name="pin" :size="18" /><span>{{ menuFor.pinned ? '取消置顶' : '置顶' }}</span>
            </button>
            <button class="sheet-item danger" @click="doDelete">
              <CoomiIcon name="trash" :size="18" /><span>删除会话</span>
            </button>
          </template>
          <p v-else class="sheet-hint">全局常驻会话：永远置顶，不可删除</p>
          <button class="sheet-cancel" @click="closeMenu">取消</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
 * 侧栏是"底图"：层级必须**低于**对话界面（.shell 是 10），这样它平时被对话界面
 * 盖住（不常驻、不显示），只有对话界面向右滑开、让出这条宽度时才露出来。
 *
 * 之前两版各栽过一次：
 *   · z-index 提太高 → 侧栏常驻在界面左侧，一直盖着对话界面；
 *   · 丢了 .open 态的 pointer-events 恢复 → 侧栏看得到却点不动、滑不动。
 *
 * 交互能不能点，靠的是 .open 时恢复 pointer-events；层级低不影响点击 —— 因为
 * 对话界面被 translateX 推开后，它就不在左侧这片区域上了，命中区自然让出来。
 */
.drawer-root {
  position: fixed; inset: 0 auto 0 0;
  width: var(--drawer-w, min(82vw, 340px));
  z-index: 5;
  pointer-events: none;
  /*
   * 侧栏是"底图"：卡片滑开时它一层都不动，但每帧都要被重新露出/裁切。
   * contain: paint 把它的重绘限制在自己的盒子里，避免卡片移动时把整页一起标记脏区；
   * 这两个属性都不改变布局与观感，只在渲染上有收益。
   */
  contain: paint;
  will-change: transform;
}
/* 打开时恢复交互：这是"侧栏能点、能滑"的关键，丢了就是半残废。 */
.drawer-root.open {
  pointer-events: auto;
}

/* 遮罩不再承担"点击关闭"—— 对话界面被推开后点它自己就会关（见 ChatView）。
   这里保留元素但让它隐形：它现在压在对话界面下面，画出来也只会是一层看不见的
   灰，留着反而容易和滑动动画叠出闪一下的观感。 */
.scrim { display: none; }

/*
 * 侧栏面板是「底图」，它自己**不做任何动画**。
 *
 * 露不露出来完全由上面那张卡片（对话界面）滑开多少决定 —— 面板再自己淡入淡出，
 * 就成了"一边滑一边闪"的两套动画叠在一起，观感就是闪一下。所以这里常驻可见、
 * 常驻不动，宽度读 .chat 上那个变量，保证和卡片滑开的距离严丝合缝。
 */
.panel {
  position: absolute; inset: 0;
  display: flex; flex-direction: column;
  width: 100%;
  max-width: none;
  padding-top: var(--safe-top);
  background: var(--bg);
  box-shadow: none;
}

.dhead { display: flex; align-items: center; gap: 6px; padding: 10px 10px 6px 12px; }
.sfield {
  flex: 1; display: flex; align-items: center; gap: 7px;
  height: 38px; padding: 0 10px 0 11px;
  border-radius: var(--r-pill); background: var(--fill); color: var(--text-3);
}
.sfield input {
  flex: 1; min-width: 0; border: 0; background: none; outline: none;
  font: inherit; font-size: 14.5px; color: var(--text);
}
.sfield input::placeholder { color: var(--text-3); }
.clr {
  display: grid; place-items: center; width: 18px; height: 18px;
  border: 0; border-radius: 50%; background: var(--border-strong); color: #fff;
}
.newrow {
  display: flex; align-items: center; gap: 10px;
  margin: 2px 10px 4px; padding: 8px 10px;
  border: 0; border-radius: var(--r-md); background: none;
  font-size: 15.5px; font-weight: 600; color: var(--blue);
}
.newrow:active { background: var(--fill); }
.entry-grid {
  display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 5px;
  margin: 0 10px 6px;
}
.entry {
  display: flex; align-items: center; gap: 8px; min-width: 0; min-height: 44px;
  padding: 7px 10px; border: 1px solid var(--border); border-radius: var(--r-md);
  background: var(--bg); color: var(--text-2); text-align: left; font-size: 13px;
}
.entry span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.entry:active { background: var(--fill-press); color: var(--blue); }
.taskrow {
  display: flex; align-items: center; gap: 10px;
  margin: 0 10px 5px; min-height: 40px; padding: 7px 10px;
  border-radius: var(--r-md); color: var(--text-2); font-size: 14px; text-align: left;
}
.taskrow:active { background: var(--fill); }
.task-count { margin-left: auto; min-width: 22px; padding: 2px 7px; border-radius: var(--r-pill); background: var(--blue); color: #fff; font-size: 11px; text-align: center; }
.nicon {
  display: grid; place-items: center; width: 30px; height: 30px;
  border-radius: 50%; background: var(--blue-soft);
}

.list { flex: 1; overflow-y: auto; padding: 2px 10px 10px; -webkit-overflow-scrolling: touch; }
.empty { margin: 26px 12px; font-size: 13.5px; line-height: 1.8; color: var(--text-3); }

.row {
  display: flex; align-items: center; gap: 4px;
  padding: 9px 6px 9px 10px; border-radius: var(--r-md);
}
.row:active { background: var(--fill); }
.row.cur { background: var(--blue-soft); }
.rmain { flex: 1; min-width: 0; }
.rtitle {
  font-size: 14.8px; color: var(--text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.row.cur .rtitle { color: var(--blue); font-weight: 600; }
.rmeta {
  display: flex; align-items: center; gap: 4px; margin-top: 3px;
  font-size: 11.5px; color: var(--text-3);
}
/* 会话在后台执行中的小圈（放在时间/轮数之后，与 meta 文字同高） */
.rspin {
  flex: none;
  width: 9px; height: 9px; border-radius: 50%;
  border: 2px solid var(--blue-soft);
  border-top-color: var(--blue);
  animation: coomi-rspin 0.9s linear infinite;
}
@keyframes coomi-rspin { to { transform: rotate(360deg); } }
/* 全局常驻会话：生命环图标 + 未读徽标（不用圆点区分） */
.global-row .gicon {
  flex: none; display: grid; place-items: center;
  width: 30px; height: 30px; margin-right: 6px;
  border-radius: 50%; color: var(--accent);
  background: color-mix(in srgb, var(--accent-soft) 60%, var(--bg));
}
.global-badge {
  display: inline-grid; place-items: center;
  min-width: 16px; height: 16px; padding: 0 4px;
  border-radius: 8px; background: var(--accent); color: #fff;
  font-size: 10.5px; font-weight: 650; line-height: 1;
}
.sheet-hint { margin: 0; padding: 12px 14px; color: var(--text-3); font-size: 12px; }
.rmeta {
  display: flex; align-items: center; gap: 4px; margin-top: 3px;
  font-size: 11.5px; color: var(--text-3);
}
.rename {
  width: 100%; padding: 4px 7px;
  border: 1px solid var(--blue-border); border-radius: var(--r-sm);
  background: var(--bg); outline: none;
  font: inherit; font-size: 14.5px; color: var(--text);
}
.rmore {
  display: grid; place-items: center; width: 30px; height: 30px;
  border: 0; border-radius: 50%; background: none; color: var(--text-3);
}

.dfoot { display: flex; align-items: stretch; gap: 8px; border-top: 1px solid var(--border); padding: 8px 10px calc(8px + var(--safe-bottom)); }
.frow.console { color: var(--blue); }
.frow {
  display: flex; align-items: center; gap: 10px; flex: 1; min-width: 0;
  padding: 8px; border: 0; border-radius: var(--r-md); background: none;
}
.frow:active { background: var(--fill); }
.fname { flex: 1; text-align: left; font-size: 15px; font-weight: 600; color: var(--text); }
.fsq {
  display: grid; place-items: center; flex-shrink: 0;
  width: 52px; min-height: 46px;
  border: 0; border-left: 1px solid var(--border);
  border-radius: 0; background: none; color: var(--text-2);
}
.fsq:active { background: var(--fill); }

.sheet-wrap {
  position: absolute; inset: 0; z-index: 2;
  display: flex; align-items: flex-end;
  background: rgba(17, 22, 31, .34);
}
.sheet {
  width: 100%; padding: 4px 10px calc(10px + var(--safe-bottom));
  background: var(--bg); border-radius: 18px 18px 0 0;
  box-shadow: var(--shadow-sheet);
  animation: rise .22s cubic-bezier(.22, .68, .19, 1) both;
}
@keyframes rise { from { transform: translateY(18px); opacity: .5; } to { transform: none; opacity: 1; } }
.sheet-title {
  padding: 12px 12px 8px; font-size: 12.5px; color: var(--text-3);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.sheet-item {
  display: flex; align-items: center; gap: 12px;
  width: 100%; height: 50px; padding: 0 12px;
  border: 0; border-radius: var(--r-md); background: none;
  font-size: 15.5px; color: var(--text);
}
.sheet-item:active { background: var(--fill); }
.sheet-item.danger { color: var(--danger); }
.sheet-cancel {
  width: 100%; height: 48px; margin-top: 6px;
  border: 0; border-radius: var(--r-md); background: var(--fill);
  font-size: 15.5px; font-weight: 600; color: var(--text-2);
}
.token-count { color: var(--blue); font-variant-numeric: tabular-nums; }
</style>
