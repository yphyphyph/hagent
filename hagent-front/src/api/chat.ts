import axios from 'axios'
import request from '@/utils/request'
import { getToken } from '@/utils/auth'

export interface Conversation {
  id: number
  title?: string
  createdAt?: string
  updatedAt?: string
}

/** 溯源引用单条（与后端 RAG 命中字段对齐） */
export interface ChatSource {
  file_id?: string | number
  chunk_id?: string
  score?: number
  file_name?: string
  // 兼容驼峰命名（后端可能返回 fileName）
  fileName?: string
  snippet?: string
  // 兼容后端偶发字段
  content?: string
}

export interface ChatMessageItem {
  id?: number
  role: 'user' | 'assistant'
  content: string
  createdAt?: string
  toolName?: string
  /** 思考过程内容（历史消息回显） */
  thinkingContent?: string
  /** 溯源引用 JSON 字符串或数组（历史消息回显，后端 sources 字段） */
  sources?: string | ChatSource[]
}

/** 上下文用量明细（与后端 meta 事件 breakdown 对齐，未知键保留透传） */
export interface ChatContextBreakdown {
  history?: number
  summary?: number
  rag?: number
  system?: number
  [key: string]: number | undefined
}

/** meta 事件全量信息（旧回调仅取 conversationId，新字段全部可选保持兼容） */
export interface ChatMetaInfo {
  conversationId: string
  promptTokens?: number
  contextWindow?: number
  percent?: number
  breakdown?: ChatContextBreakdown
  [key: string]: unknown
}

/** 后端上下文用量快照（GET /admin/ai/conversations/{id}/context-usage，与 meta 同口径） */
export interface ChatContextUsage {
  promptTokens: number
  contextWindow: number
  percent?: number
  breakdown?: ChatContextBreakdown
  [key: string]: unknown
}

/**
 * 聊天图片附件（与后端请求体契约锁死，勿改字段名）：
 * attachments: [{type:"image", dataUrl:"data:image/png;base64,...", name}]
 */
export interface ChatImageAttachment {
  type: 'image'
  /** base64 dataURL（FileReader.readAsDataURL 产物） */
  dataUrl: string
  /** 原始文件名 */
  name: string
}

/**
 * 聊天文档附件（与后端请求体契约锁死，勿改字段名）：
 * attachments: [{type:"document", name:"报告.md", url:"https://oss/...", size:16384}]
 * url 为知识库上传响应的 fileUrl（OSS 地址，无则为空串）；size 为文件字节数（历史消息无 size）
 */
export interface ChatDocumentAttachment {
  type: 'document'
  /** 原始文件名 */
  name: string
  /** 文档 OSS 地址（知识库上传响应 fileUrl；空串表示不可跳转，仅展示卡片） */
  url: string
  /** 文件字节数（气泡卡片副标题人类可读大小；历史解析无 size 时缺省） */
  size?: number
  /** 知识库文件 ID（后端按 fileId 过滤检索；知识库选择器/上传路径可得，历史解析旧标记无 fileId 时缺省） */
  fileId?: number
}

/** 附件联合类型：图片与文档混合数组随请求体 attachments 字段发送 */
export type ChatAttachment = ChatImageAttachment | ChatDocumentAttachment

/**
 * 上传聊天图片到 OSS（POST /admin/upload/image，FormData 字段名 file）
 * 后端 UploadController 校验 image/* MIME 与 5MB 上限，返回 Result{data:{url}}（request 拦截器已解包 data）
 * 失败（网络/非 200/未返回 url）抛错，由调用方中止发送并保留 chips 重试
 */
export async function uploadChatImage(file: File): Promise<{ url: string; name: string }> {
  const form = new FormData()
  form.append('file', file)
  const data = await request.post<{ url?: string }, { url?: string }>('/admin/upload/image', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  const url = data?.url ?? ''
  if (!url) throw new Error('上传失败：服务端未返回图片地址')
  return { url, name: file.name }
}

/** SSE 流式事件处理器集合 */
export interface StreamEventHandlers {
  /** 元数据事件：携带会话ID（访客模式为 "guest"），第二参数透传全量 meta 字段 */
  onMeta?: (conversationId: string, meta?: ChatMetaInfo) => void
  /** 文本 token 追加 */
  onToken: (text: string) => void
  /** 思考过程 token 追加（type:thinking） */
  onThinking?: (text: string) => void
  /** 阶段进度提示（type:stage），如“正在生成查询...” */
  onStage?: (text: string) => void
  /** 工具调用事件（阶段1每执行一个工具触发一次） */
  onTool?: (name: string) => void
  /** 溯源引用块（type:source），携带命中片段列表 */
  onSource?: (sources: ChatSource[]) => void
  /** 上下文快照刷新事件（type:context-update，与 meta 同 shape，RAG 到达与 usage 校准后推送） */
  onContextUpdate?: (meta: unknown) => void
  onError?: (msg: string) => void
  onDone?: () => void
}

/**
 * SSE 流式对话（统一入口）
 *
 * 后端事件协议：
 * - {"type":"meta","conversationId":"123"|"guest","promptTokens":12345,"contextWindow":128000,"percent":9,"breakdown":{"history":8000,"summary":1500,"rag":2800,"system":0}}
 * - {"type":"token","content":"..."}
 * - {"type":"context-update","promptTokens":12345,"contextWindow":128000,"percent":9,"breakdown":{"history":8000,"summary":1500,"rag":2800,"system":0}}
 * - {"type":"thinking","content":"..."}
 * - {"type":"stage","content":"正在生成查询..."}
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
    /** 附件（图片/文档混合数组，随请求体发送，仅作用于本次请求，不影响 SSE 解析） */
    attachments?: ChatAttachment[]
    /** 重新生成：重发最后一条 AI 回复（message 传空串，后端忽略 message 内容；与 editMessageId 互斥） */
    regenerate?: boolean
    /** 编辑重发：目标后端消息 id（message 为编辑后的新文本；与 regenerate 互斥） */
    editMessageId?: number
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
      // 无附件时为 undefined，JSON.stringify 自动省略该键（旧后端不受影响）
      attachments: opts.attachments,
      // regenerate / editMessageId 契约与后端锁死：regenerate=true 时 message 为空串；editMessageId 时 message 为新文本
      regenerate: opts.regenerate,
      editMessageId: opts.editMessageId,
    }),
    signal: controller.signal,
  })
    .then(async (resp) => {
      if (!resp.ok) {
        let serverMsg = ''
        try {
          const text = await resp.text()
          const body = JSON.parse(text) as Record<string, unknown>
          const msg = body?.msg
          if (typeof msg === 'string' && msg.trim()) serverMsg = msg.trim()
        } catch {
          // 非 JSON 响应体则回退通用提示
        }
        h.onError?.(resp.status === 401 ? '登录已过期，请重新登录' : (serverMsg || `请求失败（${resp.status}）`))
        return
      }
      const reader = resp.body?.getReader()
      if (!reader) return

      const decoder = new TextDecoder()
      let buffer = ''

      // 解析 SSE 文本流（事件以 \n\n 分隔）
      // 关键修复：使用 TextDecoder(stream:true) 逐块增量解码，避免缓冲；每读到一个 chunk 立即 processChunk，不等待后续数据
      const processChunk = (chunk: string) => {
        buffer += chunk
        const parts = buffer.split('\n\n')
        buffer = parts.pop() ?? ''
        for (const part of parts) {
          // 兼容多行 data: 拼接（SSE 规范）
          const lines = part.split('\n')
          let payload = ''
          for (const l of lines) {
            const trimmed = l.trim()
            if (trimmed.startsWith('data:')) {
              // 多行 data 需用 \n 拼接后再整体解析
              payload += (payload ? '\n' : '') + trimmed.slice(5).trim()
            }
          }
          // 若无 data: 前缀，尝试直接取 payload（容错）
          if (!payload) {
            const line = part.trim()
            if (!line.startsWith('data:')) continue
            payload = line.slice(5).trim()
          }
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
                // 全量透传 meta 字段（旧调用仅取 conversationId，新字段经第二参数透传）
                h.onMeta?.(String(obj.conversationId ?? ''), obj as unknown as ChatMetaInfo)
                break
              case 'context-update':
                // 上下文快照刷新（与 meta 同 shape，仅 type 不同），透传全量字段由上层解析覆盖
                h.onContextUpdate?.(obj as unknown)
                break
              case 'tool':
                h.onTool?.(String(obj.name ?? ''))
                break
              case 'thinking': {
                // 思考过程流（reasoning/thinking 模型增量）—— 立即回调，不做任何缓冲或防抖
                if (obj.content) h.onThinking?.(String(obj.content))
                // 后端可在 thinking 事件中附带 stage 字段（8 阶段之一），透传给阶段处理器以驱动标题动态切换
                const thinkingStage = (obj.stage as string) ?? (obj.title as string) ?? ''
                if (thinkingStage) h.onStage?.(String(thinkingStage))
                break
              }
              case 'stage': {
                // 阶段进度提示（与 onThinking 同级，轻量文本展示）
                // 前端仅展示 3 个允许阶段，屏蔽“生成查询/执行查询”由上层 useChat 统一过滤，此处透传
                const stageText =
                  (obj.content as string) ??
                  (obj.text as string) ??
                  (obj.message as string) ??
                  ''
                if (stageText) h.onStage?.(String(stageText))
                break
              }
              case 'token':
                // token 增量立即透传，避免批量
                if (obj.content) h.onToken(String(obj.content))
                break
              case 'source': {
                // 溯源引用事件：后端推送 {"type":"source","chunks":[...]}，兼容多种字段名
                const raw =
                  (obj.chunks as unknown) ??
                  (obj.sources as unknown) ??
                  (obj.data as unknown) ??
                  (obj.list as unknown) ??
                  []
                const list = Array.isArray(raw) ? (raw as ChatSource[]) : []
                if (list.length) h.onSource?.(list)
                else if (obj.file_name || obj.fileName) {
                  // 兼容单条来源直接作为对象
                  h.onSource?.([obj as unknown as ChatSource])
                }
                break
              }
              default:
                // 未知 type 静默忽略
                break
            }
          } catch {
            // 非 JSON 即纯文本块（兼容旧协议）—— 也立即透传
            h.onToken(payload)
          }
        }
      }

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        // 关键：stream:true 保证跨 chunk 的 UTF-8 字符不被截断，且每片立即处理，不做额外缓冲
        processChunk(decoder.decode(value, { stream: true }))
      }
      // 处理残留 buffer（流结束时若无尾随 \n\n，也立即 flush 最后事件）
      if (buffer.trim()) {
        // 补一个分隔符强制触发最后一段解析
        processChunk('\n\n')
      }
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
  /**
   * 会话上下文用量（与 send 后 meta 同口径，后端按需计算）
   * 原生 axios 直调以静默失败（不断网 toast），失败返回 null 由调用方回退本地估算
   */
  async contextUsage(id: number): Promise<ChatContextUsage | null> {
    try {
      const token = getToken()
      const resp = await axios.get(`/api/admin/ai/conversations/${id}/context-usage`, {
        headers: { ...(token ? { Authorization: `${token}` } : {}) },
        timeout: 10000,
      })
      const body = resp.data as { code?: number; data?: unknown } & Record<string, unknown>
      if (!body || typeof body !== 'object') return null
      // 兼容 Result 包裹（{code:200,data:{...}}）与裸返回
      const wrapped = body.data
      if (wrapped && typeof wrapped === 'object') return wrapped as ChatContextUsage
      if (typeof body.promptTokens !== 'undefined') return body as unknown as ChatContextUsage
      return null
    } catch {
      return null
    }
  },
}
