import request from '@/utils/request'
import { getToken } from '@/utils/auth'

export interface Conversation {
  id: number
  title?: string
  createdAt?: string
  updatedAt?: string
}

export interface ChatMessageItem {
  id?: number
  role: 'user' | 'assistant'
  content: string
  createdAt?: string
  toolName?: string
}

/** 待确认操作信息（SSE pending_action 事件载荷） */
export interface PendingActionInfo {
  id: number
  sql?: string
  question?: string
}

/** 待确认操作执行结果（confirm 接口返回的 payload 解析） */
export interface PendingActionResult {
  sql?: string
  question?: string
  rowCount?: number
  data?: Array<Record<string, unknown>>
  truncated?: boolean
}

/** SSE 流式事件处理器集合 */
export interface StreamEventHandlers {
  /** 元数据事件：携带会话ID（访客模式为 "guest"） */
  onMeta?: (conversationId: string) => void
  /** 文本 token 追加 */
  onToken: (text: string) => void
  /** 工具调用事件（阶段1每执行一个工具触发一次） */
  onTool?: (name: string) => void
  /** 待确认操作卡片事件 */
  onPendingAction?: (info: PendingActionInfo) => void
  onError?: (msg: string) => void
  onDone?: () => void
}

/**
 * SSE 流式对话（统一入口）
 *
 * 后端事件协议：
 * - {"type":"meta","conversationId":"123"|"guest"}
 * - {"type":"token","content":"..."}
 * - {"type":"tool","name":"searchKnowledge"}
 * - {"type":"pending_action","id":1,"sql":"...","question":"..."}
 * - {"type":"error","message":"..."}
 * - [FINAL] / [DONE] 结束标记
 *
 * @param endpoint SSE 端点：'/api/admin/ai/chat'（管理端）| '/api/admin/ai/portal/chat'（门户端）
 */
export function streamChat(
  endpoint: string,
  message: string,
  opts: {
    scene?: number
    conversationId?: number
    handlers: StreamEventHandlers
  }
): AbortController {
  const controller = new AbortController()
  const token = getToken()
  const h = opts.handlers

  fetch(endpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `${token}` } : {}),
    },
    body: JSON.stringify({
      message,
      scene: opts.scene ?? 1,
      conversationId: opts.conversationId,
    }),
    signal: controller.signal,
  })
    .then(async (resp) => {
      if (!resp.ok) {
        h.onError?.(resp.status === 401 ? '登录已过期，请重新登录' : `请求失败（${resp.status}）`)
        return
      }
      const reader = resp.body?.getReader()
      if (!reader) return

      const decoder = new TextDecoder()
      let buffer = ''

      // 解析 SSE 文本流（事件以 \n\n 分隔）
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
            h.onDone?.()
            continue
          }
          try {
            const obj = JSON.parse(payload) as Record<string, unknown>
            switch (obj.type) {
              case 'error':
                h.onError?.((obj.message as string) || 'AI 服务异常')
                break
              case 'meta':
                h.onMeta?.(String(obj.conversationId ?? ''))
                break
              case 'tool':
                h.onTool?.(String(obj.name ?? ''))
                break
              case 'pending_action':
                h.onPendingAction?.({
                  id: Number(obj.id),
                  sql: obj.sql as string | undefined,
                  question: obj.question as string | undefined,
                })
                break
              case 'token':
                if (obj.content) h.onToken(String(obj.content))
                break
            }
          } catch {
            // 非 JSON 即纯文本块（兼容旧协议）
            h.onToken(payload)
          }
        }
      }

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        processChunk(decoder.decode(value, { stream: true }))
      }
      // 处理残留 buffer
      if (buffer.trim()) processChunk('')
    })
    .catch((err: unknown) => {
      if (!(err instanceof Error && err.name === 'AbortError')) {
        h.onError?.('网络连接失败')
      }
    })

  return controller
}

/** 管理端全功能对话端点 */
export const ADMIN_CHAT_ENDPOINT = '/api/admin/ai/chat'
/** 门户端对话端点（访客可匿名） */
export const PORTAL_CHAT_ENDPOINT = '/api/admin/ai/portal/chat'

export const conversationApi = {
  /** 会话列表（后端返回分页结构 {list, total}，取 list） */
  async list(): Promise<Conversation[]> {
    const data = await request.get<{ list: Conversation[]; total: number }, { list: Conversation[]; total: number }>(
      '/admin/ai/conversations'
    )
    return data.list ?? []
  },
  messages(id: number): Promise<ChatMessageItem[]> {
    return request.get(`/admin/ai/conversations/${id}/messages`)
  },
  remove(id: number): Promise<void> {
    return request.delete(`/admin/ai/conversations/${id}`)
  },
  rename(id: number, title: string): Promise<void> {
    return request.put(`/admin/ai/conversations/${id}/title`, { title })
  },
}

/** 待确认操作接口（确认卡片的确认/取消动作） */
export const pendingActionApi = {
  async confirm(id: number): Promise<{ statusDesc?: string; payloadJson?: string }> {
    const vo = await request.post<{ statusDesc?: string; payloadJson?: string }, { statusDesc?: string; payloadJson?: string }>(
      `/admin/ai/pending-actions/${id}/confirm`
    )
    return vo ?? {}
  },
  cancel(id: number): Promise<void> {
    return request.post(`/admin/ai/pending-actions/${id}/cancel`)
  },
}
