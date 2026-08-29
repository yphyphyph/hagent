import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import { getToken, setToken, removeToken, clearAuth } from './auth'

/**
 * 轻量消息提示：避免在非组件环境依赖 UI 框架，
 * 通过 window 事件交给 App 层（Naive UI message）渲染。
 */
export function notify(type: 'success' | 'error' | 'warning' | 'info', content: string) {
  window.dispatchEvent(
    new CustomEvent('hs:notify', { detail: { type, content } })
  )
}

const request = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

request.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `${token}`
  }
  return config
})

// ---------- Token 刷新机制（refresh-once-and-retry） ----------
let isRefreshing = false
let pendingQueue: Array<(token: string | null) => void> = []

function redirectToLogin() {
  clearAuth()
  notify('error', '登录已过期，请重新登录')
  if (!window.location.pathname.startsWith('/login')) {
    window.location.href = `/login?redirect=${encodeURIComponent(window.location.pathname)}`
  }
}

async function doRefresh(): Promise<string | null> {
  const current = getToken()
  if (!current) return null
  try {
    const resp = await axios.post('/api/auth/refresh', null, {
      headers: { Authorization: `${current}` },
    })
    const body = resp.data
    const newToken = body?.data?.token
    if (body?.code === 200 && newToken) {
      setToken(newToken)
      return newToken
    }
    return null
  } catch {
    return null
  }
}

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code === 200) {
      return res.data
    }
    notify('error', res.msg || '请求失败')
    return Promise.reject(new Error(res.msg || '请求失败'))
  },
  async (error) => {
    const { response, config } = error
    if (!response) {
      notify('error', '网络连接失败')
      return Promise.reject(error)
    }
    const { status, data } = response

    if (status === 401) {
      const original = config as AxiosRequestConfig & { _retried?: boolean }
      if (original.url?.includes('/auth/refresh') || original._retried) {
        redirectToLogin()
        return Promise.reject(error)
      }
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          pendingQueue.push((token) => {
            if (!token) {
              reject(error)
              return
            }
            original._retried = true
            original.headers = {
              ...(original.headers || {}),
              Authorization: `${token}`,
            }
            resolve(request(original))
          })
        })
      }
      isRefreshing = true
      const newToken = await doRefresh()
      isRefreshing = false
      pendingQueue.forEach((cb) => cb(newToken))
      pendingQueue = []
      if (!newToken) {
        redirectToLogin()
        return Promise.reject(error)
      }
      original._retried = true
      original.headers = {
        ...(original.headers || {}),
        Authorization: `${newToken}`,
      }
      return request(original)
    } else if (status === 403) {
      notify('error', '没有权限访问')
    } else if (status === 429) {
      notify('warning', data?.msg || '请求过于频繁，请稍后再试')
    } else if (status === 500) {
      notify('error', '服务器内部错误')
    } else {
      notify('error', data?.msg || '请求失败')
    }
    return Promise.reject(error)
  }
)

export default request
