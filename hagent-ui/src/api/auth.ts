import request from '@/utils/request'
import { setToken, setUser, clearAuth, type UserInfo } from '@/utils/auth'

/** Sa-Token 登录响应 data 字段：{ userId, role, token, username } */
export interface LoginResult {
  userId: string | number
  username: string
  role?: string
  token: string
  avatar?: string
}

export interface ProfileUpdate {
  nickname?: string
  avatar?: string
}

export const authApi = {
  /** 登录，成功后写入 token 和用户信息 */
  async login(username: string, password: string): Promise<LoginResult> {
    const data = await request.post<LoginResult, LoginResult>('/auth/login', { username, password })
    setToken(data.token)
    setUser({
      userId: data.userId,
      username: data.username,
      role: data.role,
      avatar: data.avatar,
    })
    return data
  },

  /** 获取当前用户信息 */
  me(): Promise<UserInfo> {
    return request.get('/auth/me')
  },

  /** 更新个人资料 */
  updateProfile(payload: ProfileUpdate): Promise<UserInfo> {
    return request.put('/auth/profile', payload)
  },

  /** 修改密码 */
  changePassword(oldPassword: string, newPassword: string): Promise<void> {
    return request.put('/auth/password', { oldPassword, newPassword })
  },

  /** 登出 */
  logout(): Promise<void> {
    return request.post('/auth/logout')
  },

  clearLocal() {
    clearAuth()
  },
}
