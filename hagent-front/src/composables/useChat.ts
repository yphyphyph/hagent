import { ref, type Ref } from 'vue'
import {
  streamChat,
  conversationApi,
  type ChatMessageItem,
  type ChatSource,
  type ChatImageAttachment,
  type ChatDocumentAttachment,
  type ChatAttachment,
  type Conversation,
  type StreamEventHandlers,
} from '@/api/chat'
import { systemApi } from '@/api/system'


/** 用户消息图片（气泡缩略图渲染；url 为 OSS 地址或本地 base64 预览，name 为原始文件名） */
export interface ChatMessageImage {
  url: string
  name: string
}

/** 用户消息文档卡片（气泡文字上方渲染；url 缺省不可跳转，size 缺省仅显示类型名，fileId 缺省为旧标记历史消息） */
export interface ChatMessageDoc {
  name: string
  url?: string
  size?: number
  /** 知识库文件 ID（新标记 [文档：name|fileId](url) 解析所得，供后端按 fileId 过滤检索；旧标记缺省） */
  fileId?: number
}

/** 前端统一消息模型（视图层渲染用） */
export interface UiChatMessage {
  id: number | string
  role: 'user' | 'ai'
  content: string
  time: string
  /** 用户消息图片（气泡文字上方缩略图行，点击放大预览） */
  images?: ChatMessageImage[]
  /** 用户消息文档卡片（气泡文字上方卡片行，对标豆包样式） */
  docs?: ChatMessageDoc[]
  /** 本条回复涉及的工具名（工具徽章展示） */
  tools?: string[]
  /** 思考过程内容（type:thinking 累积） */
  thinkingContent?: string
  /** 思考块是否展开（默认展开） */
  thinkingExpanded?: boolean
  /** 应答起始时间戳（发送时刻，用于计算回答耗时） */
  startTime?: number
  /** 回答耗时（毫秒，流结束后写入，用于底部耗时展示） */
  elapsedMs?: number
  /** 溯源引用列表（type:source 累积，对应后端 RAG 命中） */
  sources?: ChatSource[]
}

// 供外部引用
export type { ChatSource }

/** 上下文用量快照（后端 meta 事件解析结果，流结束后定格保留） */
export interface ContextUsage {
  /** 本次组装后的提示总 token 数（分子） */
  promptTokens: number
  /** 上下文窗口（分母，可在设置页配置） */
  contextWindow: number
  /** 已用百分比（0-100，floor 取整） */
  percent: number
  /** 数值口径：estimated=流中估算（发送/RAG修正） / actual=回答完成的真实消耗（usage 回执） */
  stage: 'estimated' | 'actual'
  /** 明细：历史 / 摘要 / RAG / 系统（未知键保留透传；stage=actual 时为估算构成，与总量存在小差额） */
  breakdown: Record<string, number>
  /** 上轮实际 promptTokens（仅明细行参考，缺省 0） */
  actualPromptTokens: number
  /** 快照更新时间戳（毫秒） */
  updatedAt: number
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

/** 附件载荷转气泡图片数组（过滤 type:image；dataUrl 值为 OSS URL 或 base64 预览，均可直接作为 img src） */
function attachmentsToImages(attachments?: ChatAttachment[]): ChatMessageImage[] | undefined {
  const list = (attachments ?? []).filter((a): a is ChatImageAttachment => a.type === 'image')
  if (list.length === 0) return undefined
  return list.map((a) => ({ url: a.dataUrl, name: a.name }))
}

/** 附件载荷转气泡文档卡片数组（过滤 type:document；url 空串归一为 undefined 便于模板判定跳转） */
function attachmentsToDocs(attachments?: ChatAttachment[]): ChatMessageDoc[] | undefined {
  const list = (attachments ?? []).filter((a): a is ChatDocumentAttachment => a.type === 'document')
  if (list.length === 0) return undefined
  return list.map((a) => ({ name: a.name, url: a.url || undefined, size: a.size, fileId: a.fileId }))
}

/**
 * 从历史消息 content 中解析并剥离附件标记（后端落库时追加在 content 末尾）：
 * - `![图片](url)` → images 数组
 * - `[文档：name|fileId](url)`（新格式，fileId 为知识库文件 ID）与 `[文档：name](url)`（旧格式，fileId 缺省）
 *   双兼容 → docs 数组（历史无 size，卡片仅显示类型名；兼容半角冒号）
 * 剥离标记避免正文 markdown 与缩略图/卡片双重显示
 */
export function extractContentImages(content: string): { content: string; images: ChatMessageImage[]; docs: ChatMessageDoc[] } {
  const images: ChatMessageImage[] = []
  const docs: ChatMessageDoc[] = []
  const stripped = content
    .replace(/!\[图片\]\(([^)\s]+)\)/g, (_match, url: string) => {
      images.push({ url, name: '' })
      return ''
    })
    // 文档标记新旧双格式：
    // 新格式 [文档：name|fileId](url) → 解析出 name/url/fileId；旧格式 [文档：name](url) → fileId 缺省。
    // 名称组 [^\]|]* 在 | 或 ] 前停止；\|?(\d+)? 仅匹配新格式的 |数字 后缀，旧格式（无 |）两个可选组均空转，
    // 名称以数字结尾（如「2024报告」）因名称组贪婪优先整体归入 name，不会被误拆为 fileId
    .replace(/\[文档[：:]\s*([^\]|]*)\|?(\d+)?\]\(([^)\s]+)\)/g, (_match, name: string, fileIdStr: string | undefined, url: string) => {
      docs.push({
        name: name.trim() || '文档',
        url,
        fileId: fileIdStr != null ? Number(fileIdStr) : undefined,
      })
      return ''
    })
  return images.length > 0 || docs.length > 0
    ? { content: stripped.replace(/^\s+/, '').replace(/\s+$/, ''), images, docs }
    : { content, images, docs }
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

/** 上下文窗口分母缓存（经 systemApi.getSettings 取 ai.chat.context-window，失败回退 128000） */
let cachedContextWindow: number | null = null
const FALLBACK_CONTEXT_WINDOW = 128000

/** 解析上下文窗口：数字/数字字符串均兼容，非法回退 null 由调用方处理 */
function parseContextWindow(v: unknown): number | null {
  const n = typeof v === 'string' ? Number(v) : (v as number)
  return typeof n === 'number' && Number.isFinite(n) && n > 0 ? Math.round(n) : null
}

/** 分母解析：读设置键 ai.chat.context-window，失败静默回退 128000，不 toast 不抛错 */
async function resolveContextWindow(): Promise<number> {
  if (cachedContextWindow !== null) return cachedContextWindow
  try {
    const settings = await systemApi.getSettings()
    const parsed = parseContextWindow(settings?.['ai.chat.context-window'])
    cachedContextWindow = parsed ?? FALLBACK_CONTEXT_WINDOW
  } catch {
    cachedContextWindow = FALLBACK_CONTEXT_WINDOW
  }
  return cachedContextWindow
}

/** CJK 字符判断：与后端 ContextTokenEstimator.isCjk 同口径（统一表意文字/扩展 A/兼容区，按 UTF-16 码元区间） */
function isCjkCodeUnit(code: number): boolean {
  return (code >= 0x4e00 && code <= 0x9fff) || (code >= 0x3400 && code <= 0x4dbf) || (code >= 0xf900 && code <= 0xfaff)
}

/**
 * 单段文本 token 启发式估算：与后端 ContextTokenEstimator.estimate 同口径
 * （CJK 按 1.5 字符/token、其余按 4 字符/token、分段加 15% 脚手架裕量，非空文本至少为 1）
 */
function estimateTextTokens(text: string): number {
  if (!text) return 0
  let cjkChars = 0
  let otherChars = 0
  for (let i = 0; i < text.length; i++) {
    if (isCjkCodeUnit(text.charCodeAt(i))) cjkChars++
    else otherChars++
  }
  const raw = cjkChars / 1.5 + otherChars / 4.0
  return Math.max(1, Math.round(raw * 1.15))
}

/**
 * 共享聊天流控制：
 * - 结构化 SSE 事件解析（meta/token/tool/error）
 * // 未知 type 静默忽略
 * - 发送/停止（AbortController）
 * - 消息列表状态管理（供门户与管理端两个视图复用）
 */
export function useChat(options: UseChatOptions) {
  const messages: Ref<UiChatMessage[]> = ref([])
  const thinking = ref(false)
  const streaming = ref(false)
  /** 全局思考内容（当前生成中的增量累积，供外部/调试使用） */
  const thinkingContent = ref('')
  /** 思考增量版本号：每收到一个 thinking chunk 自增，用于触发 flush:'sync' 监听，强制逐片渲染避免批量合并 */
  const thinkingTick = ref(0)
  /** 是否已收到实际 thinking 内容：true 时隐藏“AI 思考中”占位，展示思考折叠面板 */
  const thinkingHasContent = ref(false)
  /** 内容增量版本号：每收到 token 自增，驱动 ChatMessageList 的 flush:'post' 自动滚动 */
  const contentTick = ref(0)
  /** 阶段文本仅作为思考块头部的副标题初始状态，不再单独渲染进度条（行业标准单载体） */
  const stageText = ref('')
  /** 阶段可见性已废弃：保留字段以兼容旧调用，始终为 false，UI 不再单独展示阶段条 */
  const stageVisible = ref(false)
  /** 上下文用量快照（meta 事件解析，默认 null；流结束后定格保留，切换会话/clear() 时清空） */
  const contextUsage: Ref<ContextUsage | null> = ref(null)
  /** 编辑重发目标（后端 DB 消息 id + 原文；进入编辑态时设置，发送成功或取消后清空） */
  const editingMessage = ref<{ id: number; originalText: string } | null>(null)

  // 8 阶段动态标题（与后端 THINK_STAGE_* 对齐，前端据此切换折叠块标题）
  const THINKING_TITLES = [
    '思考中',
    '正在理解问题',
    '正在分析问题',
    '制定解题策略',
    '检索知识库',
    '生成查询 SQL',
    '构建输出结构',
    '最终润色',
  ] as const
  const THINK_DONE_TITLE = '思考结束'
  // 阶段到标题的精确映射（已去除省略号，历史带省略号的输入由 normalizeStageToTitle 模糊匹配兼容）
  const STAGE_TO_TITLE: Record<string, string> = {
    '思考中': THINKING_TITLES[0],
    '正在理解问题': THINKING_TITLES[1],
    '正在分析问题': THINKING_TITLES[2],
    '制定解题策略': THINKING_TITLES[3],
    '检索知识库': THINKING_TITLES[4],
    '生成查询 SQL': THINKING_TITLES[5],
    '构建输出结构': THINKING_TITLES[6],
    '正在组织答案': THINKING_TITLES[6],
    '最终润色': THINKING_TITLES[7],
    '思考结束': THINK_DONE_TITLE,
  }
  // 工具名到阶段标题的映射（工具徽章与思考标题联动）
  const TOOL_TO_TITLE: Record<string, string> = {
    searchKnowledge: THINKING_TITLES[4],
    queryBusinessData: THINKING_TITLES[5],
    analyze_image: THINKING_TITLES[6],
  }
  /** 思考标题（动态切换的核心状态，暴露给 ChatMessageList 作为单折叠块标题） */
  const thinkingTitle = ref<string>(THINKING_TITLES[0])
  /** 当前阶段索引（单调递增，避免回退闪烁） */
  let thinkingStageIndex = 0
  /** 根据标题文本获取索引（用于单调推进） */
  function titleIndex(title: string): number {
    const idx = (THINKING_TITLES as readonly string[]).indexOf(title)
    if (idx !== -1) return idx
    if (title === THINK_DONE_TITLE) return THINKING_TITLES.length
    return -1
  }
  /** 仅向前推进标题（避免阶段回退） */
  function advanceThinkingTitle(next: string) {
    const currIdx = titleIndex(thinkingTitle.value)
    const nextIdx = titleIndex(next)
    // 若无法识别索引则直接更新（兼容未预设的 stage）
    if (nextIdx === -1 || currIdx === -1) {
      thinkingTitle.value = next
      return
    }
    if (nextIdx >= currIdx) {
      thinkingTitle.value = next
      thinkingStageIndex = nextIdx
    }
  }
  /** 将任意 stage 文本归一为 8 阶段标题之一（映射失败返回原文） */
  function normalizeStageToTitle(stage: string): string | null {
    if (!stage) return null
    const trimmed = stage.trim()
    if (STAGE_TO_TITLE[trimmed]) return STAGE_TO_TITLE[trimmed]
    // 模糊匹配：包含关键词即映射
    if (trimmed.includes('理解')) return THINKING_TITLES[1]
    if (trimmed.includes('分析')) return THINKING_TITLES[2]
    if (trimmed.includes('策略')) return THINKING_TITLES[3]
    if (trimmed.includes('检索')) return THINKING_TITLES[4]
    if (trimmed.includes('SQL') || trimmed.includes('查询')) return THINKING_TITLES[5]
    if (trimmed.includes('结构') || trimmed.includes('组织')) return THINKING_TITLES[6]
    if (trimmed.includes('润色')) return THINKING_TITLES[7]
    if (trimmed.includes('结束')) return THINK_DONE_TITLE
    return trimmed
  }
  /** 根据思考内容增量推断阶段（与后端 inferThinkingStage 逻辑对齐，前端兜底） */
  function inferTitleFromContent(chunk: string): string | null {
    if (!chunk) return null
    const lower = chunk.toLowerCase()
    if (lower.includes('sql') || chunk.includes('SELECT') || chunk.includes('查询')) return THINKING_TITLES[5]
    if (chunk.includes('检索') || chunk.includes('知识库') || lower.includes('searchknowledge')) return THINKING_TITLES[4]
    if (chunk.includes('策略') || chunk.includes('方案') || chunk.includes('计划')) return THINKING_TITLES[3]
    if (chunk.includes('结构') || chunk.includes('组织') || chunk.includes('构建输出')) return THINKING_TITLES[6]
    if (chunk.includes('润色') || chunk.includes('优化') || chunk.includes('完善')) return THINKING_TITLES[7]
    if (chunk.includes('分析')) return THINKING_TITLES[2]
    if (chunk.includes('理解')) return THINKING_TITLES[1]
    return null
  }

  /** 需要屏蔽的阶段关键词（保留执行查询屏蔽，生成查询已纳入 8 阶段不再屏蔽） */
  const BLOCKED_STAGE_KEYWORDS: string[] = ['执行查询']
  /** 判断阶段文本是否被屏蔽 */
  function isBlockedStage(text: string): boolean {
    return BLOCKED_STAGE_KEYWORDS.some((k) => text.includes(k))
  }

  let seq = 0
  let controller: AbortController | null = null
  const nextId = () => `m${++seq}`

  /** 当前正在生成的 AI 消息引用 */
  let activeAiMsg: UiChatMessage | null = null

  // 阶段条已移除，无需定时器；保留空实现以保持调用兼容
  function clearStageTimer() {}
  function hideStageImmediate() {
    stageVisible.value = false
  }
  function scheduleStageHide() {
    stageVisible.value = false
  }

  function finishStream() {
    thinking.value = false
    streaming.value = false
    // 流式结束将标题置为“思考结束”，800ms 后由 ChatMessageList 自动收起（此处立即更新标题）
    thinkingTitle.value = THINK_DONE_TITLE
    stageText.value = THINK_DONE_TITLE
    // 计算回答耗时并回写当前消息，同时收起思考块（满足“默认展开、完成后收起”）
    if (activeAiMsg) {
      const idx = messages.value.indexOf(activeAiMsg)
      if (idx !== -1) {
        let updated: UiChatMessage = { ...activeAiMsg, thinkingExpanded: false }
        if (updated.startTime && updated.elapsedMs === undefined) {
          updated = { ...updated, elapsedMs: Date.now() - updated.startTime }
        }
        messages.value[idx] = updated
        activeAiMsg = updated
      }
    }
    activeAiMsg = null
    controller = null
    // 流结束后收尾（追问已下线）
  }

  /** 加载历史会话消息（管理端，含 thinkingContent 历史思考回显与溯源） */
  async function loadMessages(conversationId: number): Promise<void> {
    messages.value = []
    // 切换会话时清空上下文用量快照（新会话首轮 meta 到达前不展示旧数据）
    contextUsage.value = null
    try {
      const list: ChatMessageItem[] = await conversationApi.messages(conversationId)
      messages.value = list.map((m) => {
        // 历史回显：从 content 解析并剥离 `![图片](url)` 与 `[文档：name](url)`，图片/文档分别走 images/docs 渲染（避免双重显示）
        const { content, images, docs } = extractContentImages(m.content)
        return {
          id: m.id ?? nextId(),
          role: normalizeRole(m.role),
          content,
          time: formatMsgTime(m.createdAt),
          // 历史消息按 toolName 显示单工具徽章
          tools: m.toolName ? [m.toolName] : undefined,
          // 历史思考过程内容回显（后端 thinking_content 落库后通过 ChatMessageVO 返回）
          thinkingContent: m.thinkingContent ?? undefined,
          // 历史消息若有思考内容，默认展开
          thinkingExpanded: m.thinkingContent ? true : undefined,
          // 溯源引用历史回显（后端 sources 字段，可能是 JSON 字符串）
          sources: parseSources((m as unknown as Record<string, unknown>).sources),
          // 历史图片回显（content 中解析出的 OSS URL）
          images: images.length > 0 ? images : undefined,
          // 历史文档卡片回显（content 中解析出的 [文档：name](url) 标记）
          docs: docs.length > 0 ? docs : undefined,
        }
      })
      // 历史页优先取后端口径（与 send 后 meta 同一代码路径，口径天然一致）；
      // 后端失败（网络/401/404）才回退本地启发式估算；空历史保持 null 不变
      if (messages.value.length > 0) {
        const remote = await conversationApi.contextUsage(conversationId).catch(() => null)
        const parsed = parseContextUsage(remote)
        if (parsed) {
          contextUsage.value = parsed
        } else {
          let historyTokens = 0
          for (const msg of messages.value) {
            historyTokens += estimateTextTokens(msg.content ?? '')
          }
          const contextWindow = await resolveContextWindow()
          contextUsage.value = {
            promptTokens: historyTokens,
            contextWindow,
            // floor 取整与后端口径一致：分子单调增时显示永不回退
            percent: Math.floor((historyTokens / contextWindow) * 100),
            // 本地兜底为启发式估算，口径标 estimated
            stage: 'estimated',
            // 历史页无当轮 RAG/摘要口径，如实归零（明细行显示 0）
            breakdown: { history: historyTokens, summary: 0, rag: 0, system: 0 },
            // 历史页无上轮实际回执，如实置 0（明细行仅参考）
            actualPromptTokens: 0,
            updatedAt: Date.now(),
          }
        }
      }
      // 切换会话后保持消息列表干净
    } catch {
      // 错误已由 request 拦截器统一 toast
    }
  }

  /** 生成前公共置位（send/sendEdit/regenerate 共用）：立即进入思考态，避免 7s SSE 空白期内 UI 无反馈 */
  function beginGenerate(): void {
    thinking.value = true
    streaming.value = true
    thinkingContent.value = ''
    thinkingHasContent.value = false
    thinkingTitle.value = THINKING_TITLES[0] // 思考中
    stageText.value = THINKING_TITLES[0]
    thinkingStageIndex = 0
    stageVisible.value = false
    clearStageTimer()
  }

  /** 推送空 AI 占位消息并置为当前流式目标（思考块以 thinkingContent="" 与标题“思考中”空内容起步，后续 SSE thinking 增量追加） */
  function pushAiPlaceholder(): void {
    const aiMsg: UiChatMessage = { id: nextId(), role: 'ai', content: '', time: '', thinkingContent: '', thinkingExpanded: true, startTime: Date.now(), sources: [] }
    messages.value.push(aiMsg)
    activeAiMsg = aiMsg
  }

  /** 统一 SSE 事件处理器（send/sendEdit/regenerate 共用；回调仅闭包引用共享状态，无每次调用私有状态） */
  const handlers: StreamEventHandlers = {
    // meta 事件：首参数会话ID透传旧回调；全量未知字段兼容解析上下文用量（旧字段不受影响）
    onMeta: (id, meta) => {
      options.onConversationId?.(id)
      const parsed = parseContextUsage(meta)
      if (parsed) contextUsage.value = parsed
    },
    // 上下文快照刷新事件：与 meta 同 shape，解析后覆盖快照（后值覆盖前值，定格逻辑与 meta 一致）
    onContextUpdate: (meta) => {
      const parsed = parseContextUsage(meta)
      // 调试审计：仅开发环境输出，生产经 vite esbuild pure 移除，避免刷屏
      if (import.meta.env.DEV) console.debug('[context-update]', parsed)
      if (parsed) contextUsage.value = parsed
    },
    // 思考过程增量累积（type:thinking）：默认展开，标题随内容动态切换
    onThinking: (chunk) => {
      if (!activeAiMsg) return
      const idx = messages.value.indexOf(activeAiMsg)
      if (idx === -1) return
      // 通过替换消息对象引用强制 Vue 重新渲染，避免响应式属性批量合并导致只显示首条
      activeAiMsg = {
        ...activeAiMsg,
        thinkingContent: (activeAiMsg.thinkingContent ?? '') + chunk,
      }
      if (!activeAiMsg.thinkingExpanded) activeAiMsg.thinkingExpanded = true
      messages.value[idx] = activeAiMsg
      // 同步到全局 ref（便于 ChatMessageList 以外监听），并递增版本号驱动 sync 监听
      thinkingContent.value = activeAiMsg.thinkingContent ?? ''
      thinkingTick.value++
      // 收到实际 thinking 内容后，隐藏“AI 思考中”占位，由思考折叠面板展示
      thinkingHasContent.value = true
      // 动态标题：根据思考内容关键词推断阶段并推进标题（与后端 stage 互补，前端兜底映射）
      const inferred = inferTitleFromContent(chunk)
      if (inferred) {
        advanceThinkingTitle(inferred)
        stageText.value = inferred
      }
    },
    // 阶段提示：后端 stage 事件与 thinking 内嵌 stage 均经此统一归一为 8 标题之一并推进标题
    onStage: (text) => {
      if (isBlockedStage(text)) return
      const normalized = normalizeStageToTitle(text)
      if (!normalized) return
      stageText.value = normalized
      stageVisible.value = false
      // 标题随 stage 动态切换（单调推进）
      advanceThinkingTitle(normalized)
    },
    onToken: (chunk) => {
      if (!activeAiMsg) return
      const idx = messages.value.indexOf(activeAiMsg)
      if (idx === -1) return
      // 通过替换消息对象引用强制正文逐 token 刷新
      activeAiMsg = {
        ...activeAiMsg,
        content: activeAiMsg.content + chunk,
        time: formatMsgTime(new Date().toISOString()),
      }
      messages.value[idx] = activeAiMsg
      contentTick.value++
    },
    onTool: (name) => {
      if (!activeAiMsg) return
      const idx = messages.value.indexOf(activeAiMsg)
      if (idx === -1) return
      activeAiMsg = {
        ...activeAiMsg,
        tools: [...(activeAiMsg.tools ?? []), name],
      }
      messages.value[idx] = activeAiMsg
      // 工具触发的阶段标题联动（如 searchKnowledge → 检索知识库）
      const toolTitle = TOOL_TO_TITLE[name]
      if (toolTitle) {
        advanceThinkingTitle(toolTitle)
        stageText.value = toolTitle
      }
    },
    // 溯源引用事件（type:source）：按 file_id+chunk_id 去重后附加到当前 AI 消息
    onSource: (chunks) => {
      if (!activeAiMsg) return
      const idx = messages.value.indexOf(activeAiMsg)
      if (idx === -1) return
      const existing = activeAiMsg.sources ?? []
      const merged = [...existing]
      for (const c of chunks) {
        const fid = (c.file_id ?? c.fileName ?? c.file_name) as unknown
        const cid = c.chunk_id as unknown
        const dup = merged.some(
          (e) =>
            String(e.file_id ?? e.fileName ?? e.file_name ?? '') === String(fid ?? '') &&
            String(e.chunk_id ?? '') === String(cid ?? '') &&
            // 若无 chunk_id，则按 file_id+snippet 判重
            (cid ? true : (e.snippet ?? e.content ?? '') === (c.snippet ?? c.content ?? ''))
        )
        if (!dup) merged.push(c)
      }
      activeAiMsg = { ...activeAiMsg, sources: merged }
      messages.value[idx] = activeAiMsg
    },
    onError: (msg) => {
      if (activeAiMsg && !activeAiMsg.content) {
        activeAiMsg.content = `（AI 响应失败：${msg}）`
      }
      options.onError?.(msg)
      scheduleStageHide()
      finishStream()
    },
    onDone: () => {
      if (activeAiMsg && !activeAiMsg.content) {
        activeAiMsg.content = '（无回复内容）'
      }
      scheduleStageHide()
      finishStream()
      options.onDone?.()
    },
  }

  /**
   * 发送消息并处理流式响应（处于编辑态时自动路由到编辑重发路径）
   * @param attachments 附件（图片/文档混合数组，随请求体 attachments 字段发送，契约见 ChatAttachment）
   * @returns 是否成功发起（空文本/生成中会拒绝）
   */
  function send(text: string, attachments?: ChatAttachment[]): boolean {
    if (editingMessage.value) return sendEdit(text, attachments)
    const trimmed = text.trim()
    if (!trimmed || thinking.value || streaming.value) return false
    const msgLen = [...trimmed].length
    if (msgLen > 10000) {
      options.onError?.(`消息过长（${msgLen}/10000字），请分段发送或上传为知识库文件`)
      return false
    }

    beginGenerate()
    // 发送成功即把附件塞进用户消息（气泡立刻显示缩略图/文档卡片；dataUrl 已是 OSS URL 或本地预览）
    messages.value.push({
      id: nextId(),
      role: 'user',
      content: trimmed,
      time: formatMsgTime(new Date().toISOString()),
      images: attachmentsToImages(attachments),
      docs: attachmentsToDocs(attachments),
    })
    pushAiPlaceholder()

    controller = streamChat(options.endpoint, trimmed, {
      scene: options.scene ?? 1,
      conversationId: options.getConversationId(),
      // 图片附件透传请求体（空数组归一为 undefined，请求体不含该键）
      attachments: attachments && attachments.length > 0 ? attachments : undefined,
      handlers,
    })
    return true
  }

  /**
   * 编辑重发：以新文本替换指定消息及其后回复（契约 editMessageId，message 为编辑后新文本）
   * UI 先移除目标消息及其后所有消息，再推新 USER + 空 AI 进入流式
   */
  function sendEdit(text: string, attachments?: ChatAttachment[]): boolean {
    const editing = editingMessage.value
    if (!editing) return false
    const trimmed = text.trim()
    if (!trimmed) return false
    if (thinking.value || streaming.value) {
      options.onError?.('正在生成回复，请等待完成后再发送')
      return false
    }
    const msgLen = [...trimmed].length
    if (msgLen > 10000) {
      options.onError?.(`消息过长（${msgLen}/10000字），请分段发送或上传为知识库文件`)
      return false
    }
    const convId = options.getConversationId()
    if (convId === undefined || convId < 0) {
      options.onError?.('请先登录并选择会话后再编辑消息')
      return false
    }
    const idx = messages.value.findIndex((m) => m.id === editing.id)
    if (idx === -1) {
      editingMessage.value = null
      options.onError?.('未找到待编辑的消息，请刷新会话后重试')
      return false
    }

    beginGenerate()
    // 先移除该消息及其后所有消息，再推新 USER + 空 AI 进入流式
    messages.value.splice(idx)
    messages.value.push({
      id: nextId(),
      role: 'user',
      content: trimmed,
      time: formatMsgTime(new Date().toISOString()),
      images: attachmentsToImages(attachments),
      docs: attachmentsToDocs(attachments),
    })
    pushAiPlaceholder()

    controller = streamChat(options.endpoint, trimmed, {
      scene: options.scene ?? 1,
      conversationId: convId,
      editMessageId: editing.id,
      attachments: attachments && attachments.length > 0 ? attachments : undefined,
      handlers,
    })
    editingMessage.value = null
    return true
  }

  /**
   * 重新生成最后一条 AI 回复（契约 regenerate=true，message 传空串由后端忽略）
   * UI 移除最后一条 AI 消息后推新空 AI 消息进入流式
   */
  function regenerate(): boolean {
    if (thinking.value || streaming.value) {
      options.onError?.('正在生成回复，请等待完成后再重新生成')
      return false
    }
    const convId = options.getConversationId()
    if (convId === undefined || convId < 0) {
      options.onError?.('请先登录并选择会话后再重新生成')
      return false
    }
    let lastAiIdx = -1
    for (let i = messages.value.length - 1; i >= 0; i--) {
      if (messages.value[i].role === 'ai') {
        lastAiIdx = i
        break
      }
    }
    if (lastAiIdx === -1) {
      options.onError?.('暂无可重新生成的回复')
      return false
    }

    beginGenerate()
    messages.value.splice(lastAiIdx)
    pushAiPlaceholder()

    controller = streamChat(options.endpoint, '', {
      scene: options.scene ?? 1,
      conversationId: convId,
      regenerate: true,
      handlers,
    })
    return true
  }

  /**
   * 进入编辑态：记录待编辑的 USER 消息（需为已落库、带后端 DB id 的历史消息）
   * 由视图层同步到输入栏（startEdit），发送时经 send 路由到编辑重发路径
   */
  function editMessage(messageId: number, originalText: string): boolean {
    if (thinking.value || streaming.value) {
      options.onError?.('正在生成回复，请等待完成后再编辑')
      return false
    }
    const convId = options.getConversationId()
    if (convId === undefined || convId < 0) {
      options.onError?.('请先登录并选择会话后再编辑消息')
      return false
    }
    const target = messages.value.find((m) => m.id === messageId)
    if (!target || target.role !== 'user') {
      options.onError?.('未找到待编辑的消息')
      return false
    }
    editingMessage.value = { id: messageId, originalText }
    return true
  }

  /** 退出编辑态（取消编辑或切换会话时调用） */
  function cancelEdit(): void {
    editingMessage.value = null
  }

  /** 停止生成（客户端断连，后端会取消 Flux 订阅终止 AI 生成） */
  function stop(): void {
    if (!controller) {
      // 即使无请求也隐藏阶段提示
      hideStageImmediate()
      return
    }
    controller.abort()
    if (activeAiMsg && activeAiMsg.content && !activeAiMsg.content.endsWith('（已停止生成）')) {
      activeAiMsg.content += '（已停止生成）'
    }
    hideStageImmediate()
    finishStream()
  }

  /** 清空本地消息（新建临时会话/切换访客模式） */
  function clear(): void {
    stop()
    hideStageImmediate()
    stageText.value = ''
    messages.value = []
    // 切换会话/新建时清空上下文用量快照与编辑态
    contextUsage.value = null
    editingMessage.value = null
  }

  /** 解析 meta 事件中的上下文用量（未知字段兼容：缺失则返回 null，旧字段不受影响） */
  function parseContextUsage(meta: unknown): ContextUsage | null {
    if (!meta || typeof meta !== 'object') return null
    const m = meta as Record<string, unknown>
    const toNum = (v: unknown): number | undefined => {
      const n = typeof v === 'string' ? Number(v) : (v as number)
      return typeof n === 'number' && Number.isFinite(n) && n >= 0 ? n : undefined
    }
    const promptTokens = toNum(m.promptTokens)
    const contextWindow = toNum(m.contextWindow)
    // 分子分母缺一不可，否则不更新快照（保持旧 meta 仅带 conversationId 时静默忽略）
    if (promptTokens === undefined || contextWindow === undefined || contextWindow <= 0) return null
    let percent = toNum(m.percent)
    // floor 取整与后端口径一致：分子单调增时显示永不回退
    if (percent === undefined) percent = Math.floor((promptTokens / contextWindow) * 100)
    // breakdown 四段归一（history/summary/rag/system），未知键透传数字，其余置 0
    const breakdown: Record<string, number> = {}
    const rawBd = (m.breakdown ?? {}) as Record<string, unknown>
    const rawObj = typeof rawBd === 'object' && rawBd !== null ? rawBd : {}
    for (const k of ['history', 'summary', 'rag', 'system']) {
      breakdown[k] = toNum(rawObj[k]) ?? 0
    }
    for (const [k, v] of Object.entries(rawObj)) {
      if (!(k in breakdown)) {
        const n = toNum(v)
        if (n !== undefined) breakdown[k] = n
      }
    }
    // 上轮实际仅进明细行参考，缺省 0（meta 事件无此字段时保持兼容）
    const actualPromptTokens = toNum(m.actualPromptTokens) ?? 0
    // 数值口径：actual=usage 回执真实值，其余（含缺省/旧事件）一律视为估算
    const stage: ContextUsage['stage'] = m.stage === 'actual' ? 'actual' : 'estimated'
    return { promptTokens, contextWindow, percent, stage, breakdown, actualPromptTokens, updatedAt: Date.now() }
  }

  /** 解析溯源字段（兼容 JSON 字符串 / 数组 / 空值） */
function parseSources(raw: unknown): ChatSource[] | undefined {
  if (!raw) return undefined
  if (Array.isArray(raw)) return raw as ChatSource[]
  if (typeof raw === 'string') {
    const s = raw.trim()
    if (!s) return undefined
    try {
      const parsed = JSON.parse(s)
      if (Array.isArray(parsed)) return parsed as ChatSource[]
      // 若后端存单对象，包一层
      if (parsed && typeof parsed === 'object') return [parsed as ChatSource]
    } catch {
      return undefined
    }
  }
  if (typeof raw === 'object') return [raw as ChatSource]
  return undefined
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

  return { messages, thinking, streaming, thinkingContent, thinkingHasContent, thinkingTick, contentTick, stageText, stageVisible, thinkingTitle, THINKING_TITLES, THINK_DONE_TITLE, contextUsage, editingMessage, loadMessages, send, stop, clear, groupConversations, regenerate, editMessage, cancelEdit }
}
