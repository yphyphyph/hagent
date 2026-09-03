import { ref, type Ref } from 'vue'
import {
  streamChat,
  conversationApi,
  type ChatMessageItem,
  type ChatSource,
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
    try {
      const list: ChatMessageItem[] = await conversationApi.messages(conversationId)
      messages.value = list.map((m) => ({
        id: m.id ?? nextId(),
        role: normalizeRole(m.role),
        content: m.content,
        time: formatMsgTime(m.createdAt),
        // 历史消息按 toolName 显示单工具徽章
        tools: m.toolName ? [m.toolName] : undefined,
        // 历史思考过程内容回显（后端 thinking_content 落库后通过 ChatMessageVO 返回）
        thinkingContent: m.thinkingContent ?? undefined,
        // 历史消息若有思考内容，默认展开
        thinkingExpanded: m.thinkingContent ? true : undefined,
        // 溯源引用历史回显（后端 sources 字段，可能是 JSON 字符串）
        sources: parseSources((m as unknown as Record<string, unknown>).sources),
      }))
      // 切换会话后保持消息列表干净
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

    // 立即进入思考态，避免 7s SSE 空白期内 UI 无反馈（先置位再推消息，确保首帧即渲染思考块）
    thinking.value = true
    streaming.value = true
    thinkingContent.value = ''
    thinkingHasContent.value = false
    thinkingTitle.value = THINKING_TITLES[0] // 思考中
    stageText.value = THINKING_TITLES[0]
    thinkingStageIndex = 0
    stageVisible.value = false
    clearStageTimer()

    messages.value.push({ id: nextId(), role: 'user', content: trimmed, time: formatMsgTime(new Date().toISOString()) })
    // 立即创建空 AI 消息，思考块以 thinkingContent="" 与标题“思考中”空内容起步，后续 SSE thinking 增量追加
    const aiMsg: UiChatMessage = { id: nextId(), role: 'ai', content: '', time: '', thinkingContent: '', thinkingExpanded: true, startTime: Date.now(), sources: [] }
    messages.value.push(aiMsg)
    activeAiMsg = aiMsg

    controller = streamChat(options.endpoint, trimmed, {
      scene: options.scene ?? 1,
      conversationId: options.getConversationId(),
      handlers: {
        onMeta: (id) => options.onConversationId?.(id),
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
      },
    })
    return true
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

  return { messages, thinking, streaming, thinkingContent, thinkingHasContent, thinkingTick, contentTick, stageText, stageVisible, thinkingTitle, THINKING_TITLES, THINK_DONE_TITLE, loadMessages, send, stop, clear, groupConversations }
}
