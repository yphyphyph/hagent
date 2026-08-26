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

/** SSE 流式事件处理器集合 */
export interface StreamEventHandlers {
  /** 元数据事件：携带会话ID（访客模式为 "guest"） */
  onMeta?: (conversationId: string) => void
  /** 文本 token 追加 */
  onToken: (text: string) => void
  /** 工具调用事件（阶段1每执行一个工具触发一次） */
  onTool?: (name: string) => void
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
 * - {"type":"error","message":"..."}
 * - [FINAL] / [DONE] 结束标记
 * // 未知 type 静默忽略（no-op）
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
