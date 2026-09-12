import { ref } from 'vue'
import { defineStore } from 'pinia'
import { request, setCsrfHeaderProvider } from '../shared/http/client'
import { ApiError } from '../shared/http/error'
import { changePassword as apiChangePassword, login as apiLogin, logout as apiLogout, type MeResponse } from '../features/account/api'
import { useOrganizationsStore } from './organizations'

export type SessionStatus = 'unknown' | 'authenticated' | 'guest'

export interface AccountInfo {
  id: string
  username: string
}

interface CsrfCredential {
  token: string
  headerName: string
}

// CSRF 凭据保存在内存，由本会话协调层单飞获取；登录、退出后作废重取（开发规范 2.4）。
let csrfCredential: CsrfCredential | null = null
let csrfPromise: Promise<CsrfCredential> | null = null

async function ensureCsrfCredential(): Promise<CsrfCredential> {
  if (csrfCredential) {
    return csrfCredential
  }
  if (!csrfPromise) {
    csrfPromise = request<CsrfCredential>('/api/auth/csrf')
      .then((response) => {
        csrfCredential = response.data
        return response.data
      })
      .finally(() => {
        csrfPromise = null
      })
  }
  return csrfPromise
}

export function invalidateCsrfCredential(): void {
  csrfCredential = null
}

setCsrfHeaderProvider(async () => {
  const credential = await ensureCsrfCredential()
  return { [credential.headerName]: credential.token }
})

export const useSessionStore = defineStore('session', () => {
  const status = ref<SessionStatus>('unknown')
  const account = ref<AccountInfo | null>(null)

  let redirectToLogin: ((target: string) => void) | null = null

  /** 由应用边界（main.ts）绑定，避免 store 反向依赖路由。 */
  function bindRedirect(handler: (target: string) => void): void {
    redirectToLogin = handler
  }

  async function bootstrap(): Promise<void> {
    try {
      const response = await request<MeResponse>('/api/auth/me')
      status.value = 'authenticated'
      account.value = { id: response.data.id, username: response.data.username }
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        status.value = 'guest'
        account.value = null
        return
      }
      throw error
    }
  }

  async function login(username: string, password: string): Promise<void> {
    await apiLogin(username, password)
    await bootstrap()
    invalidateCsrfCredential()
  }

  async function logout(): Promise<void> {
    await apiLogout()
    status.value = 'guest'
    account.value = null
    invalidateCsrfCredential()
    // 退出同时清空本人组织目录（前端架构 5.1：目录属于会话范围）。
    useOrganizationsStore().clear()
  }

  async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await apiChangePassword({ currentPassword, newPassword })
  }

  /**
   * 受保护请求出现 401 AUTH_REQUIRED 时的单飞处理：
   * 仅当当前仍是已登录状态才切换为未登录并引导一次；迟到 401 不清除新登录。
   */
  function observeUnauthorized(error: unknown): boolean {
    if (!(error instanceof ApiError) || error.isTransportError) {
      return false
    }
    if (error.status !== 401 || error.code !== 'AUTH_REQUIRED') {
      return false
    }
    if (status.value !== 'authenticated') {
      return false
    }
    status.value = 'guest'
    account.value = null
    redirectToLogin?.('/login')
    return true
  }

  return {
    status,
    account,
    bindRedirect,
    bootstrap,
    login,
    logout,
    changePassword,
    observeUnauthorized,
  }
})
