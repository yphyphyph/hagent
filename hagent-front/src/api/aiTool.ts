import request from '@/utils/request'
import { getToken } from '@/utils/auth'

export interface AiToolItem {
  /** 后端列表接口返回的字段是 key */
  key: string
  name?: string
  description?: string
  category?: string
  /** 后端返回入参定义字段是 inputs */
  inputs?: Array<{ name: string; label?: string; type?: string; required?: boolean; placeholder?: string }>
}

export interface ExecChunk {
  type: 'token' | 'result' | 'citations' | 'error'
  content?: string
  data?: unknown
}

export const aiToolApi = {
  /** 可用 AI 工具列表 */
  list(): Promise<AiToolItem[]> {
    return request.get('/admin/ai/tools')
  },
  /**
   * SSE 执行工具
   * 解析后端三类事件：
   *  - {"type":"token","content":"..."}   流式文本
   *  - {"type":"result","data":{...}}     结构化结果（SQL/统计数据）
   *  - {"type":"citations","data":[...]}  RAG 引用出处
   *  - [FINAL]/[DONE]                     流结束
   */
  streamExecute(
    toolKey: string,
    params: Record<string, unknown>,
    opts: {
      fileIds?: string[]
      onChunk: (chunk: ExecChunk) => void
      onDone?: () => void
      onError?: (msg: string) => void
    }
  ): AbortController {
    const controller = new AbortController()
    const token = getToken()

    fetch('/api/admin/ai/tools/execute', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `${token}` } : {}),
      },
      body: JSON.stringify({
        toolKey,
        params,
        fileIds: opts.fileIds,
      }),
      signal: controller.signal,
    })
      .then(async (resp) => {
        if (!resp.ok) {
          opts.onError?.(`请求失败（${resp.status}）`)
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
              opts.onDone?.()
              continue
            }
            try {
              const obj = JSON.parse(payload) as {
                type?: string
                content?: string
                data?: unknown
                message?: string
              }
              if (obj.type === 'error') {
                opts.onChunk({ type: 'error', content: obj.message || '工具执行失败' })
              } else if (obj.type === 'token' && obj.content) {
                opts.onChunk({ type: 'token', content: obj.content })
              } else if (obj.type === 'result') {
                opts.onChunk({ type: 'result', data: obj.data })
              } else if (obj.type === 'citations') {
                opts.onChunk({ type: 'citations', data: obj.data })
              }
            } catch {
              // 非 JSON 即纯文本块
              opts.onChunk({ type: 'token', content: payload })
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
      .catch((err) => {
        if (err?.name !== 'AbortError') {
          opts.onError?.('网络连接失败')
        }
      })

    return controller
  },
}
