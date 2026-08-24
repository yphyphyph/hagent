import { ref, type Ref } from 'vue'
import {
  streamChat,
  conversationApi,
  type ChatMessageItem,
  type Conversation,
} from '@/api/chat'

/** 前端统一消息模型（视图层渲染用） */
export interface UiChatMessage {
  id: number | string
  role: 'user' | 'ai'
  content: string
  time: string
  /** 本条回复涉及的工具名（工具徽章展示） */
  tools?: string[]
  /** 待确认操作卡片（Text-to-SQL 先确认后执行） */
  pendingAction?: {
    id: number
    sql?: string
    question?: string
    status: 'pending' | 'confirmed' | 'cancelled'
    resultText?: string
  }
}

export interface UseChatOptions {
  /** SSE 端点（管理端/门户端） */
  endpoint: string
  /** 当前会话ID获取器（访客模式返回 undefined） */
  getConversationId: () => number | undefined
  /** meta 事件回调（持久化会话ID或识别 guest） */
  onConversationId?: (id: string) => void
  /** 流结束回调（如刷新会话列表） */
  onDone?: () => void
  /** 错误回调（视图层用于 toast） */
  onError?: (msg: string) => void
  scene?: number
}

/** 时间格式化：HH:mm */
export function formatMsgTime(t?: string): string {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return t ?? ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 后端角色归一化：1/'1'/'user' → user，其余 → ai */
export function normalizeRole(role: unknown): 'user' | 'ai' {
  if (role === 1 || role === '1' || role === 'user' || role === 'USER') return 'user'
  return 'ai'
}

/**
 * 共享聊天流控制：
 * - 结构化 SSE 事件解析（meta/token/tool/pending_action/error）
 * - 发送/停止（AbortController）
 * - 消息列表状态管理（供门户与管理端两个视图复用）
 */
export function useChat(options: UseChatOptions) {
  const messages: Ref<UiChatMessage[]> = ref([])
  const thinking = ref(false)
  const streaming = ref(false)

  let seq = 0
  let controller: AbortController | null = null
  const nextId = () => `m${++seq}`

  /** 当前正在生成的 AI 消息引用 */
  let activeAiMsg: UiChatMessage | null = null

  function finishStream() {
    thinking.value = false
    streaming.value = false
    activeAiMsg = null
    controller = null
  }

  /** 加载历史会话消息（管理端） */
  async function loadMessages(conversationId: number): Promise<void> {
    messages.value = []
    try {
      const list: ChatMessageItem[] = await conversationApi.messages(conversationId)
      messages.value = list.map((m) => ({
        id: m.id ?? nextId(),
        role: normalizeRole(m.role),
        content: m.content,
        time: formatMsgTime(m.createdAt),
        // 历史消息按 toolName 显示单工具徽章
        tools: m.toolName ? [m.toolName] : undefined,
      }))
    } catch {
      // 错误已由 request 拦截器统一 toast
    }
  }

  /**
   * 发送消息并处理流式响应
   * @returns 是否成功发起（空文本/生成中会拒绝）
   */
  function send(text: string): boolean {
    const trimmed = text.trim()
    if (!trimmed || thinking.value || streaming.value) return false

    messages.value.push({ id: nextId(), role: 'user', content: trimmed, time: formatMsgTime(new Date().toISOString()) })
    const aiMsg: UiChatMessage = { id: nextId(), role: 'ai', content: '', time: '' }
    messages.value.push(aiMsg)
    activeAiMsg = aiMsg
    thinking.value = true
    streaming.value = true

    controller = streamChat(options.endpoint, trimmed, {
      scene: options.scene ?? 1,
      conversationId: options.getConversationId(),
      handlers: {
        onMeta: (id) => options.onConversationId?.(id),
        onToken: (chunk) => {
          if (!activeAiMsg) return
          activeAiMsg.content += chunk
          activeAiMsg.time = formatMsgTime(new Date().toISOString())
        },
        onTool: (name) => {
          if (!activeAiMsg) return
          activeAiMsg.tools = [...(activeAiMsg.tools ?? []), name]
        },
        onPendingAction: (info) => {
          if (!activeAiMsg) return
          activeAiMsg.pendingAction = { ...info, status: 'pending' }
        },
        onError: (msg) => {
          if (activeAiMsg && !activeAiMsg.content) {
            activeAiMsg.content = `（AI 响应失败：${msg}）`
          }
          options.onError?.(msg)
          finishStream()
        },
        onDone: () => {
          if (activeAiMsg && !activeAiMsg.content) {
            activeAiMsg.content = '（无回复内容）'
          }
          finishStream()
          options.onDone?.()
        },
      },
    })
    return true
  }

  /** 停止生成（客户端断连，后端会取消 Flux 订阅终止 AI 生成） */
  function stop(): void {
    if (!controller) return
    controller.abort()
    if (activeAiMsg && activeAiMsg.content && !activeAiMsg.content.endsWith('（已停止生成）')) {
      activeAiMsg.content += '（已停止生成）'
    }
    finishStream()
  }

  /** 清空本地消息（新建临时会话/切换访客模式） */
  function clear(): void {
    stop()
    messages.value = []
  }

  /** 会话时间分组：今天 / 7天内 / 30天内 / 更早 */
  function groupConversations(list: Conversation[]): Array<{ label: string; items: Conversation[] }> {
    const now = new Date()
    const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
    const dayMs = 86400000
    const groups: Array<{ label: string; maxDays: number; items: Conversation[] }> = [
      { label: '今天', maxDays: 1, items: [] },
      { label: '7 天内', maxDays: 7, items: [] },
      { label: '30 天内', maxDays: 30, items: [] },
      { label: '更早', maxDays: Infinity, items: [] },
    ]
    for (const c of list) {
      const t = c.updatedAt || c.createdAt
      const ts = t ? new Date(t).getTime() : 0
      const days = Number.isNaN(ts) || ts === 0 ? Infinity : Math.max(0, Math.ceil((startOfToday - ts) / dayMs))
      groups.find((g) => days < g.maxDays)?.items.push(c)
    }
    return groups.filter((g) => g.items.length > 0)
  }

  return { messages, thinking, streaming, loadMessages, send, stop, clear, groupConversations }
}
