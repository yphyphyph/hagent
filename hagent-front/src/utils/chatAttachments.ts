/**
 * 聊天输入栏附件纯逻辑（图片 chip 校验与文档类型路由）
 * 不依赖 Vue/DOM，便于独立断言自查；常量与后端契约锁死
 */

/** 图片附件上限（与后端契约锁死：最多 4 张） */
export const MAX_CHAT_IMAGES = 4

/** 单张图片大小上限 5MB（超限前置拒绝，避免 base64 读入后内存放大） */
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024

/** 文档附件支持的扩展名（与知识库管理页上传 accept 保持一致） */
export const DOC_EXTENSIONS: readonly string[] = ['.md', '.txt', '.pdf', '.doc', '.docx']

/** 最小文件结构（File 的结构子集，便于测试桩构造） */
export interface AttachmentFileLike {
  name: string
  size: number
  type: string
}

/** 是否图片文件：按 MIME 前缀判断（file input accept=image/* 已过滤一轮） */
export function isImageFile(file: AttachmentFileLike): boolean {
  return typeof file.type === 'string' && file.type.startsWith('image/')
}

/** 是否支持的知识库文档：按扩展名判断（.md/.txt 等在 Windows 常无 MIME） */
export function isSupportedDocFile(file: AttachmentFileLike): boolean {
  const name = (file.name || '').toLowerCase()
  return DOC_EXTENSIONS.some((ext) => name.endsWith(ext))
}

/** 被拒绝的文件与原因（原因可直接进 toast） */
export interface RejectedFile<T = AttachmentFileLike> {
  file: T
  reason: string
}

/** 图片增补计划：accepted 待读 base64，rejected 逐条 toast */
export interface ImageAddPlan<T extends AttachmentFileLike> {
  accepted: T[]
  rejected: Array<RejectedFile<T>>
  /** 既有 + 新增通过后的图片总数 */
  totalAfter: number
}

/**
 * 图片增补校验：逐张检查大小与总量上限
 * - 单张超 5MB：拒绝该张
 * - 总量超 MAX_CHAT_IMAGES：超出部分拒绝（先到先得）
 */
export function planImageAdds<T extends AttachmentFileLike>(files: readonly T[], existingCount: number): ImageAddPlan<T> {
  const accepted: T[] = []
  const rejected: Array<RejectedFile<T>> = []
  let count = Math.max(0, existingCount)
  for (const file of files) {
    if (file.size > MAX_IMAGE_BYTES) {
      rejected.push({ file, reason: `「${file.name}」超过 5MB 限制，单张图片最大 5MB` })
      continue
    }
    if (count >= MAX_CHAT_IMAGES) {
      rejected.push({ file, reason: `最多选择 ${MAX_CHAT_IMAGES} 张图片` })
      continue
    }
    accepted.push(file)
    count++
  }
  return { accepted, rejected, totalAfter: count }
}

/** 文档类型标签（气泡卡片副标题前半，如「Markdown · 16KB」的 Markdown） */
export function docTypeLabel(name: string): string {
  const lower = (name || '').toLowerCase()
  if (lower.endsWith('.md')) return 'Markdown'
  if (lower.endsWith('.pdf')) return 'PDF'
  if (lower.endsWith('.doc') || lower.endsWith('.docx')) return 'Word'
  if (lower.endsWith('.txt')) return 'TXT'
  return '文档'
}

/** 图标旁扩展名标注（大写；超 5 字符截断；无扩展名显示 FILE） */
export function docExtLabel(name: string): string {
  const lower = (name || '').toLowerCase()
  const dot = lower.lastIndexOf('.')
  // 无扩展名（或以点结尾）显示 FILE
  if (dot <= 0 || dot === lower.length - 1) return 'FILE'
  return lower.slice(dot + 1).toUpperCase().slice(0, 5)
}

/** 字节数转人类可读大小（512B / 16KB / 1.5MB，KB 取整与豆包样式一致） */
export function formatDocSize(size: number): string {
  if (size < 1024) return `${size}B`
  if (size < 1024 * 1024) return `${Math.round(size / 1024)}KB`
  return `${(size / (1024 * 1024)).toFixed(1)}MB`
}
