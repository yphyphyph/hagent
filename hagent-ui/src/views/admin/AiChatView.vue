<template>
  <div class="chat-page">
    <!-- 左侧会话列表 -->
    <aside class="chat-side">
      <div class="chat-side__head">
        <span class="chat-side__title">会话列表</span>
        <n-button size="small" quaternary circle @click="onNewConversation">
          <template #icon>
            <n-icon :component="PlusOutlined" />
          </template>
        </n-button>
      </div>
      <n-list :show-divider="false" class="conv-list">
        <div v-if="!convLoading && conversations.length === 0" class="conv-empty">
          暂无会话，点击右上角 + 新建
        </div>
        <n-list-item
          v-for="c in conversations"
          :key="c.id"
          class="conv-item"
          :class="{ 'conv-item--active': c.id === activeId }"
          @click="onSelectConversation(c)"
        >
          <div class="conv-item__body">
            <div class="conv-item__title ellipsis">{{ c.title || '未命名会话' }}</div>
            <div class="conv-item__meta">
              <span>{{ c.msgCount != null ? `${c.msgCount} 条消息` : '' }}</span>
              <span class="mono">{{ formatTime(c.updatedAt || c.createdAt) }}</span>
            </div>
          </div>
          <n-button
            size="small"
            quaternary
            type="error"
            class="conv-item__del"
            @click.stop="onDeleteConversation(c)"
          >
            <template #icon>
              <n-icon :component="DeleteOutlined" />
            </template>
          </n-button>
        </n-list-item>
      </n-list>
    </aside>

    <!-- 右侧聊天区 -->
    <section class="chat-main">
      <div ref="msgListRef" class="chat-msgs">
        <div v-if="msgLoading" class="chat-empty">
          <p>会话加载中…</p>
        </div>
        <div v-else-if="messages.length === 0" class="chat-empty">
          <n-icon :component="MessageOutlined" class="chat-empty__icon" />
          <p>开始一段新的 AI 对话</p>
        </div>

        <div
          v-for="m in messages"
          :key="m.id"
          class="msg-row"
          :class="m.role === 'user' ? 'msg-row--user' : 'msg-row--ai'"
        >
          <!-- 仅在有内容时渲染：空 content 的流式占位消息不显示气泡与时间（思考期间由下方 msg-bubble--thinking 呈现） -->
          <template v-if="m.content">
            <div v-if="m.role === 'ai'" class="msg-avatar" aria-hidden="true">AI</div>
            <div class="msg-bubble">
              <div class="msg-content md-body" v-html="renderMarkdown(m.content)"></div>
              <div v-if="m.content" class="msg-time mono">{{ m.time }}</div>
            </div>
          </template>
        </div>

        <!-- 思考中占位 -->
        <div v-if="thinking" class="msg-row msg-row--ai">
          <div class="msg-bubble msg-bubble--thinking">
            <span class="thinking-dot"></span>
            <span class="thinking-dot"></span>
            <span class="thinking-dot"></span>
            <span class="thinking-label">AI 思考中</span>
          </div>
        </div>
      </div>

      <div class="chat-input">
        <n-input
          v-model:value="draft"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 5 }"
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          @keydown.enter.exact.prevent="onSend"
        />
        <n-button type="primary" :disabled="!draft.trim()" @click="onSend">
          <template #icon>
            <n-icon :component="SendOutlined" />
          </template>
          发送
        </n-button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import {
  NButton,
  NIcon,
  NInput,
  NList,
  NListItem,
  useMessage,
} from 'naive-ui'
import { PlusOutlined, SendOutlined, MessageOutlined, DeleteOutlined } from '@vicons/antd'
import { conversationApi, type Conversation as ApiConversation } from '@/api/chat'
import { getToken } from '@/utils/auth'
import { renderMarkdown } from '@/utils/markdown'

const message = useMessage()

/* ---------- 会话 ---------- */
interface Conversation extends ApiConversation {
  msgCount?: number
}

const conversations = ref<Conversation[]>([])
const convLoading = ref(false)
const activeId = ref<number | undefined>(undefined)

/* ---------- 消息 ---------- */
type MsgRole = 'user' | 'ai'
interface ChatMessage {
  id: number
  role: MsgRole
  content: string
  time: string
}

/** 后端消息角色为数字：1=用户 2=AI（兼容字符串 user/assistant） */
function normalizeRole(role: unknown): MsgRole {
  if (role === 1 || role === '1' || role === 'user' || role === 'USER') return 'user'
  return 'ai'
}

const messages = ref<ChatMessage[]>([])
const msgLoading = ref(false)
const draft = ref('')
const thinking = ref(false)
const msgListRef = ref<HTMLElement | null>(null)

let msgId = 0
let sseController: AbortController | null = null

function now(): string {
  return new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function formatTime(t?: string): string {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return t
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function scrollToBottom() {
  nextTick(() => {
    const el = msgListRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function abortStream() {
  if (sseController) {
    sseController.abort()
    sseController = null
  }
}

/* ---------- 数据加载 ---------- */
async function loadConversations() {
  convLoading.value = true
  try {
    const list = await conversationApi.list()
    conversations.value = list.map((c) => ({ ...c, msgCount: 0 }))
    // 默认选中第一个会话并加载历史消息
    if (activeId.value === undefined && list.length > 0) {
      activeId.value = list[0].id
      await loadMessages(list[0].id)
    }
  } catch {
    // 错误已由 request 拦截器统一 toast
  } finally {
    convLoading.value = false
  }
}

async function loadMessages(id: number) {
  msgLoading.value = true
  messages.value = [] // 先清空旧消息，避免与加载态叠加
  try {
    const list = await conversationApi.messages(id)
    messages.value = list.map((m) => ({
      id: m.id ?? ++msgId,
      role: normalizeRole(m.role),
      content: m.content,
      time: formatTime(m.createdAt),
    }))
    const conv = conversations.value.find((c) => c.id === id)
    if (conv) conv.msgCount = messages.value.length
    scrollToBottom()
  } catch {
    // 已 toast
  } finally {
    msgLoading.value = false
  }
}

async function onSelectConversation(c: Conversation) {
  if (c.id === activeId.value) return
  abortStream()
  activeId.value = c.id
  await loadMessages(c.id)
}

async function onDeleteConversation(c: Conversation) {
  try {
    await conversationApi.remove(c.id)
    message.success('会话已删除')
    conversations.value = conversations.value.filter((x) => x.id !== c.id)
    if (activeId.value === c.id) {
      activeId.value = undefined
      messages.value = []
    }
  } catch {
    // 已 toast
  }
}

/* ---------- 管理端 SSE 流式对话（/admin/ai/chat） ---------- */
function streamAdminChat(opts: {
  message: string
  conversationId?: number
  onChunk: (text: string) => void
  onDone: () => void
  onError: (msg: string) => void
}): AbortController {
  const controller = new AbortController()
  const token = getToken()

  fetch('/api/admin/ai/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `${token}` } : {}),
    },
    body: JSON.stringify({
      message: opts.message,
      scene: 1,
      conversationId: opts.conversationId,
    }),
    signal: controller.signal,
  })
    .then(async (resp) => {
      if (!resp.ok) {
        opts.onError(resp.status === 401 ? '登录已过期，请重新登录' : `请求失败（${resp.status}）`)
        return
      }
      const reader = resp.body?.getReader()
      if (!reader) return
      const decoder = new TextDecoder()
      let buffer = ''

      const processChunk = (chunk: string) => {
        buffer += chunk
        const parts = buffer.split('\n\n')
        buffer = parts.pop() ?? ''
        for (const part of parts) {
          const line = part.trim()
          if (!line.startsWith('data:')) continue
          const payload = line.slice(5).trim()
          if (!payload) continue
          if (payload === '[DONE]' || payload === '[FINAL]') {
            opts.onDone()
            continue
          }
          try {
            const obj = JSON.parse(payload) as {
              type?: string
              content?: string
              message?: string
            }
            if (obj.type === 'error') {
              opts.onError(obj.message || 'AI 服务异常')
            } else if (obj.content) {
              opts.onChunk(obj.content)
            }
          } catch {
            opts.onChunk(payload)
          }
        }
      }

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        processChunk(decoder.decode(value, { stream: true }))
      }
      if (buffer.trim()) processChunk('')
    })
    .catch((err: unknown) => {
      if (!(err instanceof Error && err.name === 'AbortError')) {
        opts.onError('网络连接失败')
      }
    })

  return controller
}

/* ---------- 发送 ---------- */
function onSend() {
  const text = draft.value.trim()
  if (!text || thinking.value) return
  draft.value = ''
  messages.value.push({ id: ++msgId, role: 'user', content: text, time: now() })
  thinking.value = true
  const aiMsgId = ++msgId
  messages.value.push({ id: aiMsgId, role: 'ai', content: '', time: now() })
  scrollToBottom()

  let acc = ''
  sseController = streamAdminChat({
    message: text,
    conversationId: activeId.value,
    onChunk: (chunk) => {
      acc += chunk
      const m = messages.value.find((x) => x.id === aiMsgId)
      if (m) m.content = acc
      scrollToBottom()
    },
    onDone: () => {
      thinking.value = false
      sseController = null
      const m = messages.value.find((x) => x.id === aiMsgId)
      if (m && !m.content) m.content = '（无回复内容）'
      loadConversations()
    },
    onError: (msg) => {
      thinking.value = false
      sseController = null
      message.error(msg)
      const m = messages.value.find((x) => x.id === aiMsgId)
      if (m && !m.content) m.content = '（AI 响应失败）'
    },
  })
}

function onNewConversation() {
  abortStream()
  activeId.value = undefined
  messages.value = []
  message.success('已创建新会话')
}

onMounted(loadConversations)

onUnmounted(() => {
  abortStream()
})
</script>

<style scoped>
.chat-page {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: var(--space-4);
  height: calc(100vh - 56px - 48px);
  min-height: 480px;
}

/* ---------- 会话列表 ---------- */
.chat-side {
  display: flex;
  flex-direction: column;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}
.chat-side__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--border);
}
/* "+" 新建按钮：32px 圆形点击区，与标题垂直居中对齐 */
.chat-side__head :deep(.n-button) {
  width: 32px;
  height: 32px;
  padding: 0;
  flex-shrink: 0;
}
.chat-side__title {
  font-weight: 600;
  font-size: var(--text-sm);
}
.conv-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-2);
}
.conv-item {
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  border: 1px solid transparent;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
  margin-bottom: 2px;
}
.conv-item :deep(.n-list-item__main) {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.conv-item:hover {
  background: var(--bg-hover);
}
.conv-item--active {
  background: var(--accent-soft);
  border-color: var(--accent-border);
}
.conv-item__body {
  flex: 1;
  min-width: 0;
}
/* 删除按钮：30px 圆形点击区，hover 才显现，不挤压标题 */
.conv-item__del {
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  padding: 0;
  opacity: 0;
  transition: opacity var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.conv-item:hover .conv-item__del,
.conv-item__del:hover {
  opacity: 1;
}
.conv-empty {
  padding: var(--space-4);
  text-align: center;
  color: var(--text-3);
  font-size: var(--text-xs);
}
.conv-item__title {
  font-size: var(--text-sm);
  font-weight: 600;
  max-width: 100%;
}
.conv-item__meta {
  display: flex;
  justify-content: space-between;
  gap: var(--space-2);
  font-size: var(--text-xs);
  color: var(--text-3);
  margin-top: 2px;
}

/* ---------- 聊天区 ---------- */
.chat-main {
  display: flex;
  flex-direction: column;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}
.chat-msgs {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.chat-empty {
  margin: auto;
  text-align: center;
  color: var(--text-3);
  font-size: var(--text-sm);
}
.chat-empty__icon {
  font-size: 34px;
  color: var(--border-strong);
  margin-bottom: var(--space-2);
}

.msg-row {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
}
.msg-row--user {
  justify-content: flex-end;
}
.msg-row--ai {
  justify-content: flex-start;
}
.msg-avatar {
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-md);
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
  color: var(--accent);
  font-size: var(--text-xs);
  font-weight: 700;
  font-family: var(--font-mono);
  margin-top: 2px;
}
.msg-bubble {
  max-width: 72%;
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  line-height: 1.7;
  word-break: break-word;
}
.msg-row--user .msg-bubble {
  background: var(--accent);
  color: #0f172a;
  border-bottom-right-radius: var(--radius-sm);
}
[data-theme='light'] .msg-row--user .msg-bubble {
  color: #fff;
}
.msg-row--ai .msg-bubble {
  background: var(--bg-hover);
  border: 1px solid var(--border);
  border-bottom-left-radius: var(--radius-sm);
}
.msg-content {
  color: inherit;
}
.msg-time {
  margin-top: var(--space-1);
  font-size: var(--text-xs);
  color: var(--text-3);
  opacity: 0.45;
}
/* 时间与气泡边缘对齐：用户消息靠右、AI 消息靠左，视觉融入气泡 */
.msg-row--user .msg-time {
  text-align: right;
}
.msg-row--ai .msg-time {
  text-align: left;
}

/* ---------- 思考中 ---------- */
.msg-bubble--thinking {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: var(--space-3) var(--space-4);
}
.thinking-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  animation: pulse-dot 1.2s var(--ease) infinite;
}
.thinking-dot:nth-child(2) {
  animation-delay: 0.15s;
}
.thinking-dot:nth-child(3) {
  animation-delay: 0.3s;
}
.thinking-label {
  margin-left: var(--space-2);
  font-size: var(--text-xs);
  color: var(--text-3);
}

/* ---------- 输入区 ---------- */
.chat-input {
  display: flex;
  align-items: flex-end;
  gap: var(--space-3);
  padding: var(--space-4);
  border-top: 1px solid var(--border);
}
.chat-input .n-input {
  flex: 1;
}
/* 发送按钮：40px 高与输入框协调，与 textarea 底部对齐 */
.chat-input :deep(.n-button) {
  height: 40px;
  padding: 0 var(--space-4);
  flex-shrink: 0;
}

@media (max-width: 900px) {
  .chat-page {
    grid-template-columns: 1fr;
    height: auto;
  }
  .chat-side {
    max-height: 220px;
  }
  .chat-main {
    height: 560px;
  }
}
</style>
