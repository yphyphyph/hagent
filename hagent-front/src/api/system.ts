import request from '@/utils/request'

export const systemApi = {
  getSettings(): Promise<Record<string, unknown>> {
    return request.get('/admin/system/settings')
  },
  updateSettings(payload: Record<string, unknown>): Promise<void> {
    return request.put('/admin/system/settings', payload)
  },
  /** 测试文本模型连接（真实调用一次模型） */
  testModel(payload: { baseUrl: string; apiKey: string; model: string }): Promise<{ status: string; message: string; reply?: string }> {
    return request.post('/admin/ai/test', payload)
  },
}

export const dashboardApi = {
  stats(): Promise<Record<string, unknown>> {
    return request.get('/admin/dashboard/stats')
  },
  /** 最近操作日志（仪表盘"最近动态"数据源） */
  operationLogs(): Promise<Array<{ module?: string; action: string; createdAt: string }>> {
    return request.get('/admin/system/logs')
  },
}
