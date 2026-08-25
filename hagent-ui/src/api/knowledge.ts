import request from '@/utils/request'
import type { AxiosProgressEvent } from 'axios'

export interface KnowledgeFile {
  id: number
  fileName?: string
  fileExt?: string
  fileSize?: number
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
  upload(file: File, onProgress?: (percent: number) => void): Promise<KnowledgeFile> {
    const form = new FormData()
    form.append('file', file)
    return request.post('/admin/knowledge/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e: AxiosProgressEvent) => {
        if (e.total && onProgress) {
          onProgress(Math.round((e.loaded / e.total) * 100))
        }
      },
    })
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
}
