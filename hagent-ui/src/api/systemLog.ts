import request from '@/utils/request'

/**
 * 系统操作日志
 * 对应后端 system_log 表
 */
export interface SystemLog {
  id: number
  traceId?: string
  userId?: number
  username?: string
  module?: string
  businessId?: string
  operationType?: string
  actionDesc?: string
  method?: string
  requestMethod?: string
  requestUrl?: string
  requestParams?: string | Record<string, unknown>
  responseResult?: string | Record<string, unknown>
  success?: number
  errorMessage?: string
  ip?: string
  userAgent?: string
  costTimeMs?: number
  createdAt?: string
}

/** 分页查询参数 */
export interface SystemLogPageParams {
  page: number
  pageSize: number
  module?: string
  operationType?: string
  username?: string
  success?: number
  startTime?: string
  endTime?: string
}

/** 分页响应，兼容后端 PageResult 形如 { list/total } 或 { records/total } */
export interface SystemLogPageResult {
  list: SystemLog[]
  total: number
}

export const systemLogApi = {
  /**
   * 分页查询操作日志
   * GET /admin/system-log/page（经 request baseURL /api → /api/admin/system-log/page，nginx/vite 去掉 /api 后命中后端 /admin/system-log/page）
   */
  page(params: SystemLogPageParams): Promise<SystemLogPageResult> {
    return request.get<SystemLogPageResult, SystemLogPageResult>('/admin/system-log/page', {
      params,
    } as never)
  },

  /**
   * 日志详情
   * GET /admin/system-log/{id}
   */
  detail(id: number | string): Promise<SystemLog> {
    return request.get<SystemLog, SystemLog>(`/admin/system-log/${id}`)
  },

  /**
   * 清理日志
   * DELETE /admin/system-log/clean?days=30
   */
  clean(days: number): Promise<void> {
    return request.delete(`/admin/system-log/clean`, {
      params: { days },
    } as never)
  },
}
