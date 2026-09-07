import request from '@/utils/request'

/**
 * AI 提示词库
 * 对应后端 AiPromptController（/admin/ai/prompts）与 ai_prompt 表
 */
export interface AiPrompt {
  id?: number
  /** 标题 */
  title?: string
  /** 分类（writing/seo/analysis/general 等） */
  category?: string
  /** 提示词内容（支持 {占位符}） */
  content?: string
  /** 描述 */
  description?: string
  /** 变量占位符列表（逗号分隔） */
  variables?: string
  /** 是否内置（0=否 1=是，内置不可删除） */
  builtin?: number
  /** 使用次数 */
  useCount?: number
  /** 排序 */
  sortOrder?: number
  /** 状态（1=启用 0=禁用） */
  status?: number
  createdAt?: string
  updatedAt?: string
}

/** 列表查询参数 */
export interface PromptListParams {
  category?: string
  keyword?: string
}

export const promptApi = {
  /** 查询提示词列表（可按分类/标题关键词过滤） */
  list(params: PromptListParams): Promise<AiPrompt[]> {
    return request.get<AiPrompt[], AiPrompt[]>('/admin/ai/prompts', { params } as never)
  },

  /** 提示词详情 */
  detail(id: number): Promise<AiPrompt> {
    return request.get<AiPrompt, AiPrompt>(`/admin/ai/prompts/${id}`)
  },

  /** 新建提示词 */
  create(data: AiPrompt): Promise<AiPrompt> {
    return request.post<AiPrompt, AiPrompt>('/admin/ai/prompts', data)
  },

  /** 更新提示词（内置仅允许改内容/描述/变量/状态/排序） */
  update(id: number, data: AiPrompt): Promise<AiPrompt> {
    return request.put<AiPrompt, AiPrompt>(`/admin/ai/prompts/${id}`, data)
  },

  /** 删除提示词（内置不可删除） */
  remove(id: number): Promise<void> {
    return request.delete<void, void>(`/admin/ai/prompts/${id}`)
  },

  /** 使用次数自增（选用某提示词时调用） */
  use(id: number): Promise<void> {
    return request.post<void, void>(`/admin/ai/prompts/${id}/use`)
  },
}
