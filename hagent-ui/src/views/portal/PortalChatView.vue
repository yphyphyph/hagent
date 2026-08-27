<template>
  <div class="portal-chat">
    <!-- 会话侧栏：始终渲染，访客展示演示会话，登录用户展示真实会话 -->
    <aside
      class="portal-chat__side"
      :class="{ 'portal-chat__side--collapsed': sideCollapsed }"
      :style="sideCollapsed ? 'width:0;min-width:0;overflow:hidden;border-right:none;' : ''"
    >
      <ConversationSidebar
        :conversations="sideConvs as unknown as Conversation[]"
        :active-id="activeId"
        :loading="convLoading"
        :guest="guest"
        @select="onSelectConversation"
        @new="handleNew"
        @delete="onDeleteConversation"
        @rename="onRenameConversation"
        @login="openLogin"
      />
    </aside>

    <!-- 主聊天区：铺满剩余空间，内容列居中限宽 -->
    <section class="portal-chat__main">
      <!-- 顶部条 -->
      <header class="chat-topbar">
        <div class="chat-topbar__left">
          <!-- 顶部收起按钮：仅在侧栏展开时显示，收起后由悬浮按钮负责展开，避免重复 -->
          <button v-if="!sideCollapsed" class="collapse-btn" type="button" @click="toggleSide">
            收起
          </button>
          <template v-if="loggedIn">
            <span class="chat-topbar__title">{{ activeTitle }}</span>
          </template>
          <template v-else>
            <router-link to="/" class="topbar-brand">
              <span class="topbar-brand__mark">H</span>
              <span class="topbar-brand__name">HAgent</span>
            </router-link>
          </template>
        </div>
        <div class="chat-topbar__actions">
          <HsThemeToggle />
          <router-link v-if="loggedIn" to="/admin/tools" class="topbar-link">管理控制台</router-link>
          <router-link v-else to="/login" class="topbar-link topbar-link--strong">登录</router-link>
        </div>
      </header>

      <!-- 思考块为唯一进度载体：移除独立占位，直接由 thinking-block 从首个信号起渲染 -->
      <ChatMessageList
        ref="listRef"
        :messages="displayMessages"
        :thinking="displayThinking"
        :thinking-title="thinkingTitle"
        :stage-text="stageText"
        :streaming="displayStreaming"
        :guest="guest"
        @send="sendText"
      />

      <!-- 欢迎屏自带 hero 输入框，仅登录用户且已有消息时显示底部输入栏；访客不展示底部输入框 -->
      <div v-if="!guest && displayMessages.length > 0" class="portal-chat__inputbar">
        <ChatInputBar
          v-model="draft"
          :streaming="displayStreaming"
          @send="submitDraft"
          @stop="onStopStop"
          @focus="onInputFocus"
        />
      </div>
    </section>

    <!-- 侧栏收起时的悬浮展开按钮 -->
    <button
      v-if="sideCollapsed"
      class="side-expand-float"
      type="button"
      title="展开侧栏"
      @click="toggleSide"
    >
      展开
    </button>

    <LoginModal v-model:show="showLogin" @success="onLoginSuccess" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import ChatMessageList from '@/components/chat/ChatMessageList.vue'
import ChatInputBar from '@/components/chat/ChatInputBar.vue'
import ConversationSidebar from '@/components/chat/ConversationSidebar.vue'
import LoginModal from '@/components/chat/LoginModal.vue'
import HsThemeToggle from '@/components/ui/HsThemeToggle.vue'
import { useChat } from '@/composables/useChat'
import { conversationApi, PORTAL_CHAT_ENDPOINT, type Conversation } from '@/api/chat'
import { isLoggedIn } from '@/utils/auth'
import { demoConversations } from '@/data/demoConversations'

/* ---------- 登录态决定布局 ---------- */
const loggedIn = ref(isLoggedIn())
const guest = computed(() => !loggedIn.value)

const draft = ref('')
const activeId = ref<number | undefined>(undefined)
const conversations = ref<Conversation[]>([])
const convLoading = ref(false)
const listRef = ref<InstanceType<typeof ChatMessageList> | null>(null)

/* ---------- 侧栏折叠持久化 ---------- */
const SIDE_COLLAPSED_KEY = 'hagent_side_collapsed'
const sideCollapsed = ref(localStorage.getItem(SIDE_COLLAPSED_KEY) === '1')
function toggleSide() {
  sideCollapsed.value = !sideCollapsed.value
}
watch(sideCollapsed, (v) => {
  localStorage.setItem(SIDE_COLLAPSED_KEY, v ? '1' : '0')
})

/* ---------- 登录弹窗（保留组件以备他用，访客门控已改为重定向） ---------- */
const showLogin = ref(false)
const router = useRouter()
const message = useMessage()
function openLogin() {
  message.warning('请先登录后体验完整对话')
  router.push({ path: '/login', query: { redirect: '/' } })
}
function onLoginSuccess() {
  // authApi.login 已派发 hagent:auth-change，refreshAuthState 会拉取会话
}
function onInputFocus() {
  if (guest.value) openLogin()
}

/* ---------- 侧栏数据源：登录用真实会话，访客用演示会话 ---------- */
const sideConvs = computed(() => {
  if (loggedIn.value) return conversations.value
  return demoConversations as unknown as Conversation[]
})

/** 顶栏标题：当前会话名（来自可见侧栏） */
const activeTitle = computed(() => {
  const list = sideConvs.value as unknown as Array<{ id: number; title?: string }>
  return list.find((c) => c.id === activeId.value)?.title ?? '新会话'
})

const chat = useChat({
  endpoint: PORTAL_CHAT_ENDPOINT,
  getConversationId: () => (guest.value ? undefined : activeId.value),
  // 访客模式 meta 返回 "guest"，不作为会话ID
  onConversationId: (id) => {
    if (id !== 'guest') activeId.value = Number(id)
  },
  onDone: () => {
    if (loggedIn.value) void loadConversations()
  },
})
// 思考块为唯一进度载体：thinking-block 从首个 thinking 信号即渲染，标题“思考中…”空内容起步，随后递增
const { messages, thinking, streaming, stageText, thinkingTitle, stop } = chat

/* ---------- 视图绑定：访客展示演示消息，登录展示真实流 ---------- */
const displayMessages = computed(() => {
  if (guest.value) {
    if (activeId.value !== undefined && activeId.value < 0) {
      const demo = demoConversations.find((d) => d.id === activeId.value)
      return demo ? demo.messages : []
    }
    return []
  }
  return messages.value
})
// 思考块直通：不再区分占位与折叠块，首个 thinking 信号即由 ChatMessageList 的 thinking-block 统一承载
const displayThinking = computed(() => {
  if (guest.value) return false
  return thinking.value
})
const displayStreaming = computed(() => (guest.value ? false : streaming.value))

/** 停止按钮 */
function onStopStop() {
  stop()
}

/* ---------- 发送（访客门控：未登录一律弹登录框） ---------- */
function submitDraft() {
  if (guest.value) {
    openLogin()
    return
  }
  const text = draft.value.trim()
  if (!text) return
  if (!chat.send(text)) return
  draft.value = ''
}

function sendText(text: string) {
  if (guest.value) {
    openLogin()
    return
  }
  if (!chat.send(text)) return
  draft.value = ''
}

/* ---------- 会话管理 ---------- */
async function loadConversations() {
  convLoading.value = true
  try {
    const list = await conversationApi.list()
    conversations.value = list
    // 刷新首页时保持 activeId 为 undefined，默认进入“新会话”页面
  } catch {
    // 已由拦截器 toast
  } finally {
    convLoading.value = false
  }
}

function onSelectConversation(c: Conversation) {
  // 访客：点击演示会话直接展示静态消息
  if (guest.value) {
    stop()
    activeId.value = c.id
    return
  }
  if (c.id === activeId.value) return
  stop()
  activeId.value = c.id
  void chat.loadMessages(c.id)
}

function handleNew() {
  if (guest.value) {
    openLogin()
    return
  }
  onNewConversation()
}

function onNewConversation() {
  stop()
  activeId.value = undefined
  chat.clear()
}

async function onDeleteConversation(id: number) {
  try {
    await conversationApi.remove(id)
    conversations.value = conversations.value.filter((c) => c.id !== id)
    if (activeId.value === id) {
      activeId.value = undefined
      chat.clear()
    }
  } catch {
    // 已由拦截器 toast
  }
}

async function onRenameConversation(id: number, title: string) {
  try {
    await conversationApi.rename(id, title)
    const target = conversations.value.find((c) => c.id === id)
    if (target) target.title = title
  } catch {
    // 已由拦截器 toast
  }
}

/* ---------- 登录态响应式同步 ---------- */
/**
 * 登录态变化时校准视图（幂等：状态未变直接返回，快速反复切换安全）
 * - 转为已登录：停掉残留播放/请求，清空旧数据后拉取会话列表
 * - 转为访客：停掉播放/请求并清空会话，回到演示视图
 */
function refreshAuthState(): void {
  const now = isLoggedIn()
  if (now === loggedIn.value) return
  loggedIn.value = now
  if (now) {
    stop()
    chat.clear()
    void loadConversations()
  } else {
    stop()
    chat.clear()
    conversations.value = []
    activeId.value = undefined
  }
}

onMounted(() => {
  // 挂载时先校准一次登录态（覆盖同页签登录/登出后重进等边界），再按需拉取会话
  refreshAuthState()
  // 同页签登录态变化通知（localStorage 的 storage 事件仅跨页签触发，此处自定义事件补齐同页签流程）
  window.addEventListener('hagent:auth-change', refreshAuthState)
  if (loggedIn.value) void loadConversations()
})

onUnmounted(() => {
  window.removeEventListener('hagent:auth-change', refreshAuthState)
})
</script>

<style scoped>
/* ---------- 全屏铺满：侧栏贴左通高，主区占满剩余 ---------- */
.portal-chat {
  display: flex;
  height: 100vh;
  min-height: 0;
  overflow: hidden;
  position: relative;
}

/* ---------- 侧栏 ---------- */
.portal-chat__side {
  flex-shrink: 0;
  width: 280px;
  background: var(--bg);
  border-right: 1px solid var(--border);
  overflow: hidden;
  transition: width var(--dur, 220ms) var(--ease);
}
.portal-chat__side--collapsed {
  width: 0 !important;
  min-width: 0 !important;
  border-right: none;
}

/* ---------- 主区 ---------- */
.portal-chat__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-elevated);
}

/* ---------- 顶部条 ---------- */
.chat-topbar {
  flex-shrink: 0;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--space-5);
  gap: var(--space-4);
}
.chat-topbar__left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-width: 0;
}
.collapse-btn {
  flex-shrink: 0;
  height: 32px;
  padding: 0 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-strong);
  background: var(--bg);
  color: var(--text-2);
  font-size: var(--text-xs);
  font-weight: 600;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}
.collapse-btn:hover {
  background: var(--bg-hover);
  color: var(--text-1);
  border-color: var(--accent-border);
}
.chat-topbar__title {
  font-weight: 600;
  font-size: var(--text-base);
  color: var(--text-1);
  max-width: 50%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-topbar__actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.topbar-link {
  font-size: var(--text-sm);
  color: var(--text-2);
  text-decoration: none;
  padding: 6px 12px;
  border-radius: var(--radius-md);
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.topbar-link:hover {
  color: var(--text-1);
  background: var(--bg-hover);
}
.topbar-link--strong {
  border: 1px solid var(--border-strong);
}
.topbar-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  text-decoration: none;
}
.topbar-brand__mark {
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  background: var(--accent);
  color: #fff;
  font-family: var(--font-mono);
  font-weight: 800;
  font-size: 14px;
  border-radius: 7px;
}
[data-theme='dark'] .topbar-brand__mark {
  color: #0f172a;
}
.topbar-brand__name {
  font-family: var(--font-display), 'Noto Serif SC', serif;
  font-weight: 700;
  font-size: 16px;
  color: var(--text-1);
}

/* ---------- 输入栏 ---------- */
.portal-chat__inputbar {
  flex-shrink: 0;
  padding: var(--space-2) var(--space-5) var(--space-4);
}

/* ---------- 悬浮展开按钮 ---------- */
.side-expand-float {
  position: absolute;
  left: 12px;
  top: 68px;
  z-index: 10;
  height: 32px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid var(--border-strong);
  background: var(--bg-elevated);
  color: var(--text-1);
  font-size: var(--text-xs);
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  transition: background var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}
.side-expand-float:hover {
  background: var(--bg-hover);
  transform: translateY(-1px);
}

@media (max-width: 900px) {
  .portal-chat__side {
    display: none;
  }
  .side-expand-float {
    display: none;
  }
}
</style>
