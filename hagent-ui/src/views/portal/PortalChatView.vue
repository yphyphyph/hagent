<template>
  <div class="portal-chat">
    <!-- 会话侧栏：贴左通高（仅登录用户；访客单栏沉浸式） -->
    <aside v-if="loggedIn" class="portal-chat__side">
      <ConversationSidebar
        :conversations="conversations"
        :active-id="activeId"
        :loading="convLoading"
        @select="onSelectConversation"
        @new="onNewConversation"
        @delete="onDeleteConversation"
        @rename="onRenameConversation"
      />
    </aside>

    <!-- 主聊天区：铺满剩余空间，内容列居中限宽 -->
    <section class="portal-chat__main">
      <!-- 顶部条 -->
      <header class="chat-topbar">
        <template v-if="loggedIn">
          <span class="chat-topbar__title">{{ activeTitle }}</span>
          <div class="chat-topbar__actions">
            <HsThemeToggle />
            <router-link to="/admin/tools" class="topbar-link">管理控制台</router-link>
          </div>
        </template>
        <template v-else>
          <router-link to="/" class="topbar-brand">
            <span class="topbar-brand__mark">H</span>
            <span class="topbar-brand__name">HAgent</span>
          </router-link>
          <div class="chat-topbar__actions">
            <HsThemeToggle />
            <router-link to="/login" class="topbar-link topbar-link--strong">登录</router-link>
          </div>
        </template>
      </header>

      <ChatMessageList
        ref="listRef"
        :messages="displayMessages"
        :thinking="displayThinking"
        :guest="!loggedIn"
        :confirming-id="confirmingId"
        @send="sendText"
        @confirm-action="onConfirmAction"
        @cancel-action="onCancelAction"
      />

      <!-- 欢迎屏自带 hero 输入框，仅在已有消息时显示底部输入栏，避免双输入框 -->
      <div v-if="displayMessages.length > 0" class="portal-chat__inputbar">
        <ChatInputBar
          v-model="draft"
          :streaming="displayStreaming"
          @send="submitDraft"
          @stop="onStopStop"
        />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import ChatMessageList from '@/components/chat/ChatMessageList.vue'
import ChatInputBar from '@/components/chat/ChatInputBar.vue'
import ConversationSidebar from '@/components/chat/ConversationSidebar.vue'
import HsThemeToggle from '@/components/ui/HsThemeToggle.vue'
import { useChat } from '@/composables/useChat'
import { useDemoChat } from '@/composables/useDemoChat'
import {
  conversationApi,
  pendingActionApi,
  PORTAL_CHAT_ENDPOINT,
  type Conversation,
} from '@/api/chat'
import { isLoggedIn } from '@/utils/auth'

/* ---------- 登录态决定布局：访客单栏沉浸式，登录后带历史侧栏 ---------- */
const loggedIn = ref(isLoggedIn())

const draft = ref('')
const activeId = ref<number | undefined>(undefined)
const conversations = ref<Conversation[]>([])
const convLoading = ref(false)
const listRef = ref<InstanceType<typeof ChatMessageList> | null>(null)

/**
 * 正在确认中的待办操作 ID（驱动确认按钮 loading 态）。
 * 空闲哨兵值取 0：真实操作 ID 为雪花大正数、演示卡片 id 为 -1，0 与两者永不碰撞；
 * 若不传该 prop，子组件默认值 -1 会与演示卡片 id:-1 恒等，导致访客"确认执行"按钮永久 loading 吞掉点击。
 */
const confirmingId = ref(0)

/** 顶栏标题：当前会话名 */
const activeTitle = computed(
  () => conversations.value.find((c) => c.id === activeId.value)?.title ?? '新会话'
)

const chat = useChat({
  endpoint: PORTAL_CHAT_ENDPOINT,
  getConversationId: () => activeId.value,
  // 访客模式 meta 返回 "guest"，不作为会话ID
  onConversationId: (id) => {
    if (id !== 'guest') activeId.value = Number(id)
  },
  onDone: () => {
    if (loggedIn.value) void loadConversations()
  },
})
const { messages, thinking, streaming, stop } = chat

/** 访客演示播放器：纯本地剧本，无任何网络请求 */
const demo = useDemoChat()

/* ---------- 视图绑定按登录态切换：登录用真实会话流，访客用本地演示剧本 ---------- */
const displayMessages = computed(() => (loggedIn.value ? messages.value : demo.messages.value))
const displayThinking = computed(() => (loggedIn.value ? thinking.value : demo.thinking.value))
const displayStreaming = computed(() => (loggedIn.value ? streaming.value : demo.streaming.value))

/** 停止按钮：登录态停真实请求，访客停本地演示播放 */
function onStopStop() {
  if (loggedIn.value) stop()
  else demo.stop()
}

/* ---------- 发送（欢迎屏与底部输入框共用） ---------- */
function submitDraft() {
  const text = draft.value.trim()
  if (!text) return
  // 访客分支：走本地演示剧本，绝不触达真实聊天接口
  if (!loggedIn.value) {
    demo.play(text)
    draft.value = ''
    return
  }
  if (!chat.send(text)) return
  draft.value = ''
}

function sendText(text: string) {
  // 访客分支：走本地演示剧本，绝不触达真实聊天接口
  if (!loggedIn.value) {
    demo.play(text)
    draft.value = ''
    return
  }
  if (!chat.send(text)) return
  draft.value = ''
}

/* ---------- 会话管理（登录用户） ---------- */
async function loadConversations() {
  convLoading.value = true
  try {
    const list = await conversationApi.list()
    conversations.value = list
    if (activeId.value === undefined && list.length > 0) {
      activeId.value = list[0].id
      await chat.loadMessages(list[0].id)
    }
  } catch {
    // 已由拦截器 toast
  } finally {
    convLoading.value = false
  }
}

function onSelectConversation(c: Conversation) {
  if (c.id === activeId.value) return
  stop()
  activeId.value = c.id
  void chat.loadMessages(c.id)
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

/* ---------- 待确认操作（登录走真实接口；访客仅本地翻转演示卡片，零网络请求） ---------- */
async function onConfirmAction(
  msg: (typeof messages.value)[number],
  action: NonNullable<(typeof messages.value)[number]['pendingAction']>
) {
  // 访客分支：演示数据（id=-1）不落库，直接本地翻转状态
  if (!loggedIn.value) {
    demo.confirmDemo(action)
    return
  }
  try {
    // 先标记当前确认中的操作 ID，驱动按钮 loading 态防止重复提交
    confirmingId.value = action.id
    const vo = await pendingActionApi.confirm(action.id)
    action.status = 'confirmed'
    if (vo.payloadJson) {
      const payload = JSON.parse(vo.payloadJson) as { rowCount?: number; truncated?: boolean }
      action.resultText = `执行完成：返回 ${payload.rowCount ?? 0} 行${payload.truncated ? '（已截断前100行）' : ''}`
    }
    void msg
  } catch {
    action.status = 'pending'
  } finally {
    // 无论成败都复位空闲哨兵 0，避免按钮卡在 loading
    confirmingId.value = 0
  }
}

async function onCancelAction(
  _msg: (typeof messages.value)[number],
  action: NonNullable<(typeof messages.value)[number]['pendingAction']>
) {
  // 访客分支：演示数据（id=-1）不落库，直接本地翻转状态
  if (!loggedIn.value) {
    demo.cancelDemo(action)
    return
  }
  try {
    await pendingActionApi.cancel(action.id)
    action.status = 'cancelled'
  } catch {
    // 已由拦截器 toast
  }
}

/* ---------- 登录态响应式同步 ---------- */
/**
 * 登录态变化时校准视图（幂等：状态未变直接返回，快速反复切换安全）
 * - 转为已登录：停掉残留播放/请求，清空旧数据后拉取会话列表
 * - 转为访客：停掉播放/请求并清空会话，回到单栏沉浸式演示视图
 */
function refreshAuthState(): void {
  const now = isLoggedIn()
  if (now === loggedIn.value) return
  loggedIn.value = now
  if (now) {
    stop()
    demo.stop()
    chat.clear()
    void loadConversations()
  } else {
    stop()
    demo.stop()
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
}

/* ---------- 侧栏 ---------- */
.portal-chat__side {
  flex-shrink: 0;
  width: 280px;
  background: var(--bg);
  border-right: 1px solid var(--border);
  overflow: hidden;
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

@media (max-width: 900px) {
  .portal-chat__side {
    display: none;
  }
}
</style>
