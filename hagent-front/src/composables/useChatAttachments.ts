import { computed, ref } from 'vue'
import type { ChatAttachment } from '@/api/chat'
import { uploadChatImage } from '@/api/chat'
import type { KnowledgeFile } from '@/api/knowledge'
import { knowledgeApi } from '@/api/knowledge'
import { notify } from '@/utils/request'
import { isImageFile, isSupportedDocFile, planImageAdds } from '@/utils/chatAttachments'

/** 图片 chip（本地 base64 dataURL 秒显预览；发送时经 uploadChatImage 传 OSS 换 URL） */
export interface ImageChip {
  id: number
  name: string
  dataUrl: string
  /** 原始 File（发送时上传 OSS 用，chip 展示不依赖它） */
  file: File
}

/** 进行中的知识库文档上传 chip（进度展示，结束后移除） */
export interface DocUpload {
  id: number
  name: string
  percent: number
}

/** 已入库的文档成功态 chip（发送时随消息走；点 × 仅移除本地 chip，知识库条目保留） */
export interface DocChip {
  id: number
  name: string
  /** 知识库上传响应的 fileUrl（OSS 地址；无则为空串，气泡卡片不可跳转） */
  url: string
  /** 文件字节数（气泡卡片副标题人类可读大小） */
  size: number
  /** 知识库文件 ID（发送时随附件载荷传后端，用于按 fileId 过滤检索；历史解析旧 chip 无则缺省） */
  fileId?: number
}

export interface UseChatAttachmentsOptions {
  /** 是否访客（点击附件入口时判定） */
  isGuest: () => boolean
  /** 访客点击附件入口的引导回调（如 emit login-request），不打开文件选择器 */
  onGuestAttach?: () => void
}

/**
 * 聊天附件共享逻辑（ChatInputBar 与欢迎屏 hero 输入共用）：
 * - 图片：选中即 base64 chip 秒显预览，点发送时并发上传 OSS（≤4 张/单张≤5MB，契约见 ChatImageAttachment，dataUrl 值为 OSS URL）
 * - 文档：选中即上传知识库（复用 knowledgeApi.upload，后端上传后触发向量化），成功后转为成功态 chip 保留在输入区随消息发送（点 × 仅移除本地 chip，知识库条目保留），不自动发消息
 */
export function useChatAttachments(options: UseChatAttachmentsOptions) {
  /** 待发送图片 chips（发送成功后由父组件经 clearAttachments 清空） */
  const images = ref<ImageChip[]>([])
  let imageSeq = 0

  /** 进行中的知识库文档上传（每条一个进度 chip，结束后移除） */
  const docUploads = ref<DocUpload[]>([])
  let docSeq = 0
  /** 已入库的文档成功态 chips（发送时随消息走，成功后由父组件经 clearAttachments 清空） */
  const docs = ref<DocChip[]>([])
  /** 按钮 loading 态：任一文档上传中即转圈 */
  const docUploading = computed(() => docUploads.value.length > 0)

  const fileInputRef = ref<HTMLInputElement | null>(null)

  /** 附件入口点击：访客走登录引导（不清空已选），已登录打开文件选择器 */
  function onAttachClick() {
    if (options.isGuest()) {
      options.onGuestAttach?.()
      return
    }
    fileInputRef.value?.click()
  }

  /** 选中文件分流：图片走 base64 chip，文档立即上传知识库，其余类型 toast 拒绝 */
  function onFilesPicked(e: Event) {
    const input = e.target as HTMLInputElement
    const files = Array.from(input.files ?? [])
    // 重置 value 使同名文件可重复选择
    input.value = ''
    const imageFiles: File[] = []
    for (const file of files) {
      if (isImageFile(file)) {
        imageFiles.push(file)
      } else if (isSupportedDocFile(file)) {
        void uploadDoc(file)
      } else {
        notify('warning', `「${file.name}」不是支持的附件类型，仅支持图片与 md/txt/pdf/doc/docx 文档`)
      }
    }
    if (imageFiles.length === 0) return
    // 超限前置拒绝（4 张 / 单张 5MB），通过者再读 base64，避免大文件读入内存
    const plan = planImageAdds(imageFiles, images.value.length)
    for (const r of plan.rejected) notify('warning', r.reason)
    for (const f of plan.accepted) void addImageChip(f)
  }

  /** FileReader 读 base64 dataURL（5MB 上限已在选中时前置拒绝） */
  function readAsDataUrl(file: File): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(reader.error ?? new Error('读取失败'))
      reader.readAsDataURL(file)
    })
  }

  async function addImageChip(file: File) {
    try {
      const dataUrl = await readAsDataUrl(file)
      images.value.push({ id: ++imageSeq, name: file.name, dataUrl, file })
    } catch {
      notify('error', `「${file.name}」读取失败，请重新选择`)
    }
  }

  function removeImage(index: number) {
    images.value.splice(index, 1)
  }

  /** 移除文档成功态 chip（仅移除本地 chip，知识库条目保留，title 已注明） */
  function removeDoc(index: number) {
    docs.value.splice(index, 1)
  }

  /**
   * 从知识库选择器接入：选中文件直接映射为成功态 doc chips（name/url/size 取自列表接口）
   * 已有 fileUrl 不再重复上传，后续发送/卡片/落库/历史全链路与上传流共用
   */
  function addKnowledgeDocs(files: KnowledgeFile[]) {
    for (const f of files) {
      docs.value.push({
        id: ++docSeq,
        name: f.fileName || '未命名文件',
        url: f.fileUrl ?? '',
        size: f.fileSize ?? 0,
        fileId: f.id,
      })
    }
  }

  /**
   * 文档立即上传知识库（复用知识库管理页同一接口，后端上传后触发向量化）
   * 上传中按钮 loading + chip 进度；成功后转为成功态 chip 保留在输入区（随消息发送）；
   * 失败 toast 由 request 拦截器统一提示；不自动发送消息
   */
  async function uploadDoc(file: File) {
    const chipId = ++docSeq
    docUploads.value.push({ id: chipId, name: file.name, percent: 0 })
    try {
      const uploaded = await knowledgeApi.upload(file, (percent) => {
        // 经响应式数组访问更新，确保视图跟随进度刷新
        const chip = docUploads.value.find((d) => d.id === chipId)
        if (chip) chip.percent = percent
      })
      // 成功态 chip：url 取上传响应的 fileUrl（无则空串），size 记录字节数供气泡卡片副标题，fileId 取上传响应 id
      docs.value.push({ id: chipId, name: file.name, url: uploaded?.fileUrl ?? '', size: file.size, fileId: uploaded?.id })
      notify('success', `「${file.name}」已入库并向量化，可直接提问`)
    } catch {
      // 失败提示已由 request 拦截器统一 toast（403 → 没有权限访问，其余透传服务端 msg），此处仅收尾进度 chip
    } finally {
      const idx = docUploads.value.findIndex((d) => d.id === chipId)
      if (idx !== -1) docUploads.value.splice(idx, 1)
    }
  }

  /** 清空图片与文档 chips（发送成功后由父组件调用；进行中的文档上传不受影响） */
  function clearAttachments() {
    images.value = []
    docs.value = []
  }

  /* ---------- 发送前图片上传 OSS（chips 保持 base64 秒显，点发送时才上传换 URL） ---------- */
  /** 上传并发上限（防打爆 OSS，与后端 60s/30 次限流留余量） */
  const UPLOAD_CONCURRENCY = 3

  /** 图片上传进行中（发送按钮 loading 态） */
  const imageUploading = ref(false)
  /** 上传进度文案（如「图片上传中 2/4」，供按钮 title 展示） */
  const imageUploadStatus = ref('')

  /**
   * 组装发送附件载荷（契约与后端锁死，勿改字段名）：
   * - 文档 chips 已在选中时上传知识库，直接映射 {type:'document', name, url, size}
   * - 图片 chips 并发上传 OSS 后映射 {type:'image', dataUrl:OSS URL, name}
   * - 任一图片上传失败：toast 已由 request 拦截器统一提示，抛错中止发送，chips 保留可重试
   * - 图片与文档均为空时返回 undefined，不进入 loading 态
   */
  async function uploadAndBuildAttachments(): Promise<ChatAttachment[] | undefined> {
    const imgChips = images.value
    const docChips = docs.value
    if (imgChips.length === 0 && docChips.length === 0) return undefined
    const payload: ChatAttachment[] = docChips.map((d) => ({
      type: 'document' as const,
      name: d.name,
      url: d.url,
      size: d.size,
      // fileId 有则传，无则省略（undefined 值经 JSON.stringify 自动剔除该键，旧后端不受影响）
      fileId: d.fileId,
    }))
    if (imgChips.length === 0) return payload
    const total = imgChips.length
    const urls = new Array<string>(total)
    let next = 0
    let failed = false
    let done = 0
    imageUploading.value = true
    imageUploadStatus.value = `图片上传中 0/${total}`
    const worker = async () => {
      while (next < total && !failed) {
        const idx = next++
        try {
          const { url } = await uploadChatImage(imgChips[idx].file)
          urls[idx] = url
        } catch {
          // 失败即中止剩余上传（错误 toast 由 request 拦截器统一处理）
          failed = true
        } finally {
          done++
          imageUploadStatus.value = `图片上传中 ${done}/${total}`
        }
      }
    }
    await Promise.all(Array.from({ length: Math.min(UPLOAD_CONCURRENCY, total) }, () => worker()))
    imageUploading.value = false
    imageUploadStatus.value = ''
    if (failed) throw new Error('图片上传失败')
    for (let i = 0; i < total; i++) {
      payload.push({ type: 'image' as const, dataUrl: urls[i], name: imgChips[i].name })
    }
    return payload
  }

  return { images, docs, docUploads, docUploading, imageUploading, imageUploadStatus, fileInputRef, onAttachClick, onFilesPicked, removeImage, removeDoc, addKnowledgeDocs, clearAttachments, uploadAndBuildAttachments }
}
