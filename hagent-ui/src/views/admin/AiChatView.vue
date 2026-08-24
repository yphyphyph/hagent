<template>
  <div class="admin-chat">
    <!-- 左侧会话列表 -->
    <aside class="admin-chat__side">
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

    <!-- 右侧聊天区 -->
    <section class="admin-chat__main">
      <ChatMessageList
        ref="listRef"
        :messages="messages"
        :thinking="thinking"
        :confirming-id="confirmingId"
        @send="sendText"
        @confirm-action="onConfirmAction"
        @cancel-action="onCancelAction"
      />
      <div class="admin-chat__inputbar">
        <ChatInputBar v-model="draft" :streaming="streaming" @send="submitDraft" @stop="stop" />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import ChatMessageList from '@/components/chat/ChatMessageList.vue'
import ChatInputBar from '@/components/chat/ChatInputBar.vue'
import ConversationSidebar from '@/components/chat/ConversationSidebar.vue'
import { useChat } from '@/composables/useChat'
import {
  conversationApi,
  pendingActionApi,
  ADMIN_CHAT_ENDPOINT,
  type Conversation,
} from '@/api/chat'

type UiMsg = ReturnType<typeof useChat>['messages']['value'][number]
type UiPending = NonNullable<UiMsg['pendingAction']>

const draft = ref('')
const activeId = ref<number | undefined>(undefined)
const conversations = ref<Conversation[]>([])
const convLoading = ref(false)
const confirmingId = ref<number>(-1)

const chat = useChat({
  endpoint: ADMIN_CHAT_ENDPOINT,
  getConversationId: () => activeId.value,
  onConversationId: (id) => {
    // 首条消息后端自动建会话：回填ID并刷新列表
    if (id !== 'guest') activeId.value = Number(id)
  },
  onDone: () => void loadConversations(),
})
const { messages, thinking, streaming, stop } = chat

/* ---------- 发送 ---------- */
function submitDraft() {
  const text = draft.value.trim()
  if (!text) return
  if (!chat.send(text)) return
  draft.value = ''
}

function sendText(text: string) {
  if (!chat.send(text)) return
  draft.value = ''
}

/* ---------- 会话管理 ---------- */
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

/* ---------- 待确认操作（Text-to-SQL 先确认后执行） ---------- */
async function onConfirmAction(msg: UiMsg, action: UiPending) {
  confirmingId.value = action.id
  try {
    const vo = await pendingActionApi.confirm(action.id)
    action.status = 'confirmed'
    if (vo.payloadJson) {
      try {
        const payload = JSON.parse(vo.payloadJson) as { rowCount?: number; truncated?: boolean }
        action.resultText = `执行完成：返回 ${payload.rowCount ?? 0} 行${payload.truncated ? '（已截断前100行）' : ''}`
      } catch {
        action.resultText = '执行完成'
      }
    }
    void msg
  } catch {
    // 执行失败时后端状态回滚为待确认，前端保持 pending 可重试
  } finally {
    confirmingId.value = -1
  }
}

async function onCancelAction(_msg: UiMsg, action: UiPending) {
  try {
    await pendingActionApi.cancel(action.id)
    action.status = 'cancelled'
  } catch {
    // 已由拦截器 toast
  }
}

onMounted(loadConversations)
</script>

<style scoped>
.admin-chat {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: var(--space-4);
  height: calc(100vh - 56px - 48px);
  min-height: 480px;
}
.admin-chat__side {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}
.admin-chat__main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}
.admin-chat__inputbar {
  flex-shrink: 0;
  padding: var(--space-3) var(--space-5) var(--space-4);
}

@media (max-width: 900px) {
  .admin-chat {
    grid-template-columns: 1fr;
    height: auto;
  }
  .admin-chat__side {
    max-height: 220px;
  }
  .admin-chat__main {
    height: 560px;
  }
}
</style>
