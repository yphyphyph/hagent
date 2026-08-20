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
}

/**
 * SSE 流式对话
 * @param message 用户消息
 * @param scene 1-通用 2-订单 3-知识库
 * @param conversationId 会话ID（空则新建）
 * @param onChunk 收到文本块回调
 * @param onDone 流结束回调
 * @param onError 错误回调
 * @returns AbortController（用于中断）
 */
export function streamChat(
  message: string,
  opts: {
    scene?: number
    conversationId?: number
    onChunk: (text: string) => void
    onDone?: (conversationId?: number) => void
    onError?: (msg: string) => void
  }
): AbortController {
  const controller = new AbortController()
  const token = getToken()

  fetch('/api/admin/ai/portal/chat', {
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
        if (resp.status === 401) {
          opts.onError?.('登录已过期，请重新登录')
        } else {
          opts.onError?.(`请求失败（${resp.status}）`)
        }
        return
      }
      const reader = resp.body?.getReader()
      if (!reader) return

      const decoder = new TextDecoder()
      let buffer = ''

      // 解析 SSE 文本流
      const processChunk = (chunk: string) => {
        buffer += chunk
        // SSE 事件以 \n\n 分隔
        const parts = buffer.split('\n\n')
        buffer = parts.pop() ?? ''
        for (const part of parts) {
          const line = part.trim()
          if (!line.startsWith('data:')) continue
          const payload = line.slice(5).trim()
          if (!payload) continue
          if (payload === '[DONE]' || payload === '[FINAL]') {
            opts.onDone?.()
            continue
          }
          // 结构化 JSON：{"type":"error","message":"..."} / {"type":"token","content":"..."} / {"type":"meta","conversationId":...}
          try {
            const obj = JSON.parse(payload)
            if (obj.type === 'error') {
              opts.onError?.(obj.message || 'AI 服务异常')
            } else if (obj.content) {
              opts.onChunk(obj.content)
            }
            // meta 事件：仅携带会话ID信息，不触发 onDone（真正的结束标记是 [FINAL]/[DONE]）
          } catch {
            // 非 JSON 即纯文本块
            opts.onChunk(payload)
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
    .catch((err) => {
      if (err?.name !== 'AbortError') {
        opts.onError?.('网络连接失败')
      }
    })

  return controller
}

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
}
