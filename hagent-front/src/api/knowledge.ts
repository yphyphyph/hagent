import request from '@/utils/request'
import type { AxiosProgressEvent } from 'axios'

export interface KnowledgeFile {
  id: number
  fileName?: string
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

export const knowledgeApi = {
  /** 文件列表（后端返回分页结构 {list, total}，取 list） */
  async list(): Promise<KnowledgeFile[]> {
    const data = await request.get<{ list: KnowledgeFile[]; total: number }, { list: KnowledgeFile[]; total: number }>(
      '/admin/knowledge'
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
}
