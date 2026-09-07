/**
 * 会话导出 Markdown（纯前端拼接，无后端参与）
 *
 * 结构约定：
 * - 一级标题 = 会话标题
 * - 元信息行 = 导出时间 / 消息数
 * - 按时间序以「## 用户」「## 助手」二级标题分节（不用 emoji）
 * - 助手消息含思考过程时，以「> 思考过程」引用块附后（截断至 500 字防超长）
 * - 全部溯源来源去重后以「## 来源」+「- [文件名]」列表附尾
 */
import type { ChatMessageItem, ChatSource } from '@/api/chat'

/** 思考过程引用块最大保留字数（超出截断，防导出文件超长） */
const THINKING_MAX_CHARS = 500

/** 文件名非法字符（Windows 保留字符 + 控制字符，g 全量替换） */
const FILENAME_ILLEGAL = /[\\/:*?"<>|\u0000-\u001f]/g

/** 文件名清洗：非法字符替换为下划线，去首尾空白与结尾点号，超长截断，空值回退默认名 */
export function sanitizeExportFileName(title: string): string {
  const cleaned = (title || '')
    .replace(FILENAME_ILLEGAL, '_')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/[. ]+$/, '')
    .slice(0, 80)
    .replace(/[. ]+$/, '')
  return cleaned || '未命名会话'
}

/** 角色归一：user/1/'1' → 用户，其余（assistant/ai/未知）→ 助手（与 useChat.normalizeRole 同口径） */
function roleLabel(role: unknown): '用户' | '助手' {
  if (role === 'user' || role === 1 || role === '1' || role === 'USER') return '用户'
  return '助手'
}

/** 解析溯源字段（兼容 JSON 字符串 / 数组 / 单对象 / 空值，与 useChat.parseSources 同口径） */
function parseSources(raw: unknown): ChatSource[] {
  if (!raw) return []
  if (Array.isArray(raw)) return raw as ChatSource[]
  if (typeof raw === 'string') {
    const s = raw.trim()
    if (!s) return []
    try {
      const parsed = JSON.parse(s) as unknown
      if (Array.isArray(parsed)) return parsed as ChatSource[]
      if (parsed && typeof parsed === 'object') return [parsed as ChatSource]
    } catch {
      return []
    }
  }
  if (typeof raw === 'object') return [raw as ChatSource]
  return []
}

/** 取来源文件名（file_name 优先，兼容驼峰 fileName，再回退 file_id） */
function sourceFileName(s: ChatSource): string {
  return s.file_name || s.fileName || (s.file_id != null ? String(s.file_id) : '')
}

/** 思考过程转引用块：逐行加「> 」前缀，超长截断并标注 */
function thinkingToQuote(thinking: string): string {
  let text = thinking.trim()
  if (!text) return ''
  if (text.length > THINKING_MAX_CHARS) {
    text = text.slice(0, THINKING_MAX_CHARS) + '…（思考过程已截断）'
  }
  return text
    .split('\n')
    .map((line) => `> ${line}`.trimEnd())
    .join('\n')
}

/** 本地时间格式化：YYYY-MM-DD HH:mm:ss */
function formatDateTime(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/**
 * 拼接会话导出 Markdown
 * @param title 会话标题（一级标题）
 * @param messages 会话全量消息（conversationApi.messages 返回，按时间序）
 */
export function buildConversationMarkdown(title: string, messages: ChatMessageItem[]): string {
  const lines: string[] = []
  lines.push(`# ${title || '未命名会话'}`)
  lines.push('')
  lines.push(`导出时间：${formatDateTime(new Date())} ｜ 消息数：${messages.length}`)
  lines.push('')
  lines.push('---')
  lines.push('')

  const sourceNames: string[] = []
  for (const m of messages) {
    lines.push(`## ${roleLabel(m.role)}`)
    lines.push('')
    lines.push(m.content ?? '')
    // 助手消息附思考过程引用块（截断至 500 字）
    if (roleLabel(m.role) === '助手') {
      const quote = m.thinkingContent ? thinkingToQuote(m.thinkingContent) : ''
      if (quote) {
        lines.push('')
        lines.push('> 思考过程')
        lines.push('>')
        lines.push(quote)
      }
      for (const s of parseSources(m.sources)) {
        const name = sourceFileName(s)
        if (name && !sourceNames.includes(name)) sourceNames.push(name)
      }
    }
    lines.push('')
  }

  // 来源列表附尾（跨消息去重）
  if (sourceNames.length > 0) {
    lines.push('## 来源')
    lines.push('')
    for (const name of sourceNames) {
      lines.push(`- [${name}]`)
    }
    lines.push('')
  }

  return lines.join('\n').replace(/\n{3,}/g, '\n\n').trimEnd() + '\n'
}

/** 触发浏览器下载（Blob + 临时 a 标签，用后即回收） */
export function downloadTextFile(filename: string, content: string): void {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
