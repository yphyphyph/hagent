import request from '@/utils/request'
import type { AxiosProgressEvent } from 'axios'

export interface KnowledgeFile {
  id: number
  fileName?: string
  /** 文档 OSS 地址（与后端 KnowledgeFileVO.fileUrl 对齐，聊天文档 chip 由此取 url） */
  fileUrl?: string
  fileExt?: string
  fileSize?: number
  /** 可见性：0=私有 1=公有 */
  visibility?: number
  /** 上传用户ID */
  userId?: number
  vectorStatus?: number
  vectorStatusDesc?: string
  createdAt?: string
  fileType?: string
}

export interface KnowledgeStats {
  totalFiles?: number
  completed?: number
  searchCount?: number
}

export interface KnowledgeChunk {
  chunk_index?: number
  chunkIndex?: number
  id?: string | number
  content?: string
  text?: string
  metadata?: Record<string, unknown>
  score?: number
  file_id?: string | number
  fileId?: string | number
}

export const knowledgeApi = {
  /** 文件列表（后端返回分页结构 {list, total}，取 list；pageSize 可选，知识库选择器一次拉全量用） */
  async list(opts?: { pageSize?: number }): Promise<KnowledgeFile[]> {
    const data = await request.get<{ list: KnowledgeFile[]; total: number }, { list: KnowledgeFile[]; total: number }>(
      '/admin/knowledge',
      opts?.pageSize ? { params: { pageNum: 1, pageSize: opts.pageSize } } : undefined
    )
    return data.list ?? []
  },
  upload(file: File, onProgress?: (percent: number) => void, visibility?: number): Promise<KnowledgeFile> {
    const form = new FormData()
    form.append('file', file)
    if (visibility != null) form.append('visibility', String(visibility))
    return request.post('/admin/knowledge/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e: AxiosProgressEvent) => {
        if (e.total && onProgress) {
          onProgress(Math.round((e.loaded / e.total) * 100))
        }
      },
    })
  },
  /** 切换文件可见性(0=私有 1=公有)，后端会自动重新向量化同步元数据 */
  updateVisibility(id: number, visibility: number): Promise<KnowledgeFile> {
    return request.put(`/admin/knowledge/files/${id}/visibility?visibility=${visibility}`)
  },
  remove(id: number): Promise<void> {
    return request.delete(`/admin/knowledge/files/${id}`)
  },
  vectorize(id: number): Promise<void> {
    return request.post(`/admin/knowledge/files/${id}/vectorize`)
  },
  status(id: number): Promise<{ status?: string; errorMsg?: string }> {
    return request.get(`/admin/knowledge/status/${id}`)
  },
  statistics(): Promise<KnowledgeStats> {
    return request.get('/admin/knowledge/statistics')
  },
  search(query: string, limit?: number): Promise<Array<Record<string, unknown>>> {
    return request.post('/admin/knowledge/search', { query, limit })
  },
  outdatedStats(): Promise<{ outdatedCount: number; currentModel?: string; currentDimension?: number }> {
    return request.get('/admin/knowledge/outdated-stats')
  },
  revectorizeOutdated(ids?: number[]): Promise<{ total: number; queued: number }> {
    return request.post('/admin/knowledge/revectorize-outdated', ids ? { ids } : {})
  },
  /** 分块列表：优先 GET /admin/knowledge/{id}/chunks，无则前端兜底走 GET /admin/ai/vector/search?fileId=... */
  async chunks(fileId: number | string): Promise<KnowledgeChunk[]> {
    try {
      const data = await request.get<unknown, unknown>(`/admin/knowledge/${fileId}/chunks`)
      if (Array.isArray(data)) return data as KnowledgeChunk[]
      if (data && typeof data === 'object') {
        const obj = data as Record<string, unknown>
        if (Array.isArray(obj.list)) return obj.list as KnowledgeChunk[]
        if (Array.isArray(obj.data)) return obj.data as KnowledgeChunk[]
        if (Array.isArray(obj.chunks)) return obj.chunks as KnowledgeChunk[]
      }
      return []
    } catch (e: unknown) {
      // 404 兜底：尝试向量检索接口按 fileId 过滤
      const msg = e instanceof Error ? e.message : ''
      void msg
      try {
        const fallback = await request.get<unknown, unknown>(`/admin/ai/vector/search`, {
          params: { fileId },
        })
        if (Array.isArray(fallback)) return fallback as KnowledgeChunk[]
        if (fallback && typeof fallback === 'object') {
          const obj = fallback as Record<string, unknown>
          if (Array.isArray(obj.list)) return obj.list as KnowledgeChunk[]
          if (Array.isArray(obj.data)) return obj.data as KnowledgeChunk[]
          if (Array.isArray(obj.chunks)) return obj.chunks as KnowledgeChunk[]
        }
        return []
      } catch {
        throw e
      }
    }
  },
  /** 按 fileId 的向量检索兜底（显式 GET /admin/ai/vector/search?fileId=...） */
  vectorSearchByFile(fileId: number | string, query?: string, limit?: number): Promise<KnowledgeChunk[]> {
    const params: Record<string, unknown> = { fileId }
    if (query) params.query = query
    if (limit) params.limit = limit
    return request.get('/admin/ai/vector/search', { params }) as Promise<KnowledgeChunk[]>
  },
}
