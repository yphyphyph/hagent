const TOKEN_KEY = 'hagent_token'
const USER_KEY = 'hagent_user'

/** 登录用户信息（Sa-Token 后端返回结构） */
export interface UserInfo {
  userId: string | number
  username: string
  role?: string
  avatar?: string
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
  // 同步通知同页签登录态变化
  window.dispatchEvent(new Event('hagent:auth-change'))
}

export function removeToken(): void {
  localStorage.removeItem(TOKEN_KEY)
  // 同步通知同页签登录态变化
  window.dispatchEvent(new Event('hagent:auth-change'))
}

export function getUser(): UserInfo | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as UserInfo
  } catch {
    return null
  }
}

export function setUser(user: UserInfo): void {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearAuth(): void {
  removeToken()
  localStorage.removeItem(USER_KEY)
  // 同步通知同页签登录态变化（内部 removeToken 已触发一次，重复派发对幂等监听方无副作用）
  window.dispatchEvent(new Event('hagent:auth-change'))
}

export function isLoggedIn(): boolean {
  return !!getToken()
}

/** 读取当前登录用户名 */
export function getUsername(): string {
  return getUser()?.username ?? ''
}

/** 读取当前登录用户角色 */
export function getRole(): string {
  return getUser()?.role ?? ''
}
