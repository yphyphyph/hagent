import request from '@/utils/request'

/** Text-to-SQL 表白名单与行级隔离策略 */
export interface SqlTablePolicy {
  id?: number
  tableName: string
  /** 是否按用户隔离 0=否 1=是 */
  userScoped?: number
  /** 用户隔离列名，默认 user_id */
  userColumn?: string
  /** 是否支持公私 0=否 1=是（公有记录豁免用户隔离） */
  visibilityAware?: number
  /** 是否启用 0=停用 1=启用 */
  enabled?: number
  remark?: string
}

export const sqlPolicyApi = {
  async list(): Promise<SqlTablePolicy[]> {
    return request.get<SqlTablePolicy[], SqlTablePolicy[]>('/admin/ai/sql-policies')
  },
  create(policy: SqlTablePolicy): Promise<SqlTablePolicy> {
    return request.post<SqlTablePolicy, SqlTablePolicy>('/admin/ai/sql-policies', policy)
  },
  update(id: number, policy: SqlTablePolicy): Promise<SqlTablePolicy> {
    return request.put<SqlTablePolicy, SqlTablePolicy>(`/admin/ai/sql-policies/${id}`, policy)
  },
  remove(id: number): Promise<void> {
    return request.delete(`/admin/ai/sql-policies/${id}`)
  },
}
