import { ref, type Ref, triggerRef, nextTick } from 'vue'
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
  /** 思考过程内容（type:thinking 累积） */
  thinkingContent?: string
  /** 思考块是否展开（默认展开） */
  thinkingExpanded?: boolean
  /** 应答起始时间戳（发送时刻，用于计算回答耗时） */
  startTime?: number
  /** 回答耗时（毫秒，流结束后写入，用于底部耗时展示） */
  elapsedMs?: number
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
  /** 阶段进度提示文本（仅展示 3 个允许阶段：理解问题/分析问题/组织答案） */
  const stageText = ref('')
  /** 阶段提示是否可见 */
  const stageVisible = ref(false)

  /** 需要屏蔽的阶段关键词（TextToSql 内部阶段，前端不展示） */
  const BLOCKED_STAGE_KEYWORDS = ['生成查询', '执行查询']
  /** 判断阶段文本是否被屏蔽 */
  function isBlockedStage(text: string): boolean {
    return BLOCKED_STAGE_KEYWORDS.some((k) => text.includes(k))
  }

  let seq = 0
  let controller: AbortController | null = null
  const nextId = () => `m${++seq}`

  /** 当前正在生成的 AI 消息引用 */
  let activeAiMsg: UiChatMessage | null = null

  /** 阶段提示自动隐藏定时器 */
  let stageHideTimer: ReturnType<typeof setTimeout> | null = null

  /** 清除阶段隐藏定时器 */
  function clearStageTimer() {
    if (stageHideTimer) {
      clearTimeout(stageHideTimer)
      stageHideTimer = null
    }
  }

  /** 立即隐藏阶段提示 */
  function hideStageImmediate() {
    clearStageTimer()
    stageVisible.value = false
  }

  /** 延迟 2s 自动隐藏阶段提示（完成/出错后） */
  function scheduleStageHide() {
    clearStageTimer()
    stageHideTimer = setTimeout(() => {
      stageVisible.value = false
      stageHideTimer = null
    }, 2000)
  }

  function finishStream() {
    thinking.value = false
    streaming.value = false
    // 计算回答耗时并回写当前消息（含停止/异常场景，已结束的消息也会被标记）
    if (activeAiMsg && activeAiMsg.startTime && activeAiMsg.elapsedMs === undefined) {
      const idx = messages.value.indexOf(activeAiMsg)
      if (idx !== -1) {
        activeAiMsg = { ...activeAiMsg, elapsedMs: Date.now() - activeAiMsg.startTime }
        messages.value[idx] = activeAiMsg
      }
    }
    activeAiMsg = null
    controller = null
  }

  /** 加载历史会话消息（管理端，含 thinkingContent 历史思考回显） */
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
    // 思考块默认展开，确保首轮与增量思考均可见
    const aiMsg: UiChatMessage = { id: nextId(), role: 'ai', content: '', time: '', thinkingContent: '', thinkingExpanded: true, startTime: Date.now() }
    messages.value.push(aiMsg)
    activeAiMsg = aiMsg
    thinking.value = true
    streaming.value = true
    // 新一轮思考内容重置
    thinkingContent.value = ''
    thinkingHasContent.value = false
    // 新一轮阶段提示重置
    clearStageTimer()
    stageText.value = ''
    stageVisible.value = false

    controller = streamChat(options.endpoint, trimmed, {
      scene: options.scene ?? 1,
      conversationId: options.getConversationId(),
      handlers: {
        onMeta: (id) => options.onConversationId?.(id),
        // 思考过程增量累积（type:thinking）：默认展开，增量更新自动贴底
        // 关键修复：Vue 默认批量合并同步修改，需用 triggerRef + nextTick 强制逐片刷新，避免一次性全量渲染
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
        },
        // 阶段进度提示（type:stage）：屏蔽“生成查询/执行查询”，仅展示 3 个允许阶段
        onStage: (text) => {
          // 若命中被屏蔽关键词则隐藏进度条，不更新文本
          if (isBlockedStage(text)) {
            hideStageImmediate()
            return
          }
          clearStageTimer()
          stageText.value = text
          stageVisible.value = true
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
        },
        onError: (msg) => {
          if (activeAiMsg && !activeAiMsg.content) {
            activeAiMsg.content = `（AI 响应失败：${msg}）`
          }
          options.onError?.(msg)
          // 出错后延迟隐藏阶段提示
          scheduleStageHide()
          finishStream()
        },
        onDone: () => {
          if (activeAiMsg && !activeAiMsg.content) {
            activeAiMsg.content = '（无回复内容）'
          }
          // 完成后延迟 2s 自动隐藏阶段提示
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

  return { messages, thinking, streaming, thinkingContent, thinkingHasContent, thinkingTick, contentTick, stageText, stageVisible, loadMessages, send, stop, clear, groupConversations }
}
