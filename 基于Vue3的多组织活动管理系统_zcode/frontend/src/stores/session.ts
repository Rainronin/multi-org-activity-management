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

/**
 * 会话层（R02）：维护代次（generation）保护——每个请求发起时登记代次，
 * 迟到的成功响应或 401 只有在代次未变时才允许改写状态，旧会话响应不清除新登录。
 */
export const useSessionStore = defineStore('session', () => {
  const status = ref<SessionStatus>('unknown')
  const account = ref<AccountInfo | null>(null)
  const generation = ref(0)

  let redirectToLogin: ((target: string) => void) | null = null

  /** 由应用边界（main.ts）绑定，避免 store 反向依赖路由。 */
  function bindRedirect(handler: (target: string) => void): void {
    redirectToLogin = handler
  }

  /** 请求发起前调用：记录当时的会话代次。 */
  function capture(): number {
    return generation.value
  }

  /** 会话切换或失效时递增；同时清空受保护缓存与 CSRF 凭据，阻止旧请求回填。 */
  function invalidateProtectedState(): void {
    status.value = 'guest'
    account.value = null
    generation.value++
    useOrganizationsStore().clear()
    invalidateCsrfCredential()
  }

  async function bootstrap(): Promise<void> {
    const gen = capture()
    try {
      const response = await request<MeResponse>('/api/auth/me')
      if (gen !== generation.value) {
        return
      }
      status.value = 'authenticated'
      account.value = { id: response.data.id, username: response.data.username }
    } catch (error) {
      if (gen !== generation.value) {
        return
      }
      if (error instanceof ApiError && error.status === 401) {
        invalidateProtectedState()
        return
      }
      throw error
    }
  }

  /** 切回标签页时重新验证会话；已失效则走单飞登录引导（开发规范 2.4）。 */
  async function revalidate(): Promise<void> {
    if (status.value !== 'authenticated') {
      return
    }
    const gen = capture()
    try {
      await bootstrap()
    } catch {
      return
    }
    if (status.value !== 'authenticated' && gen === generation.value) {
      redirectToLogin?.('/login')
    }
  }

  async function login(username: string, password: string): Promise<void> {
    await apiLogin(username, password)
    await bootstrap()
    invalidateCsrfCredential()
    // 会话主体切换：递增代次，作废切换前所有在途请求的回写资格
    generation.value++
  }

  async function logout(): Promise<void> {
    await apiLogout()
    invalidateProtectedState()
  }

  async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await apiChangePassword({ currentPassword, newPassword })
  }

  /**
   * 受保护请求出现 401 AUTH_REQUIRED 时的单飞处理：
   * 请求发起时的代次必须与当前一致（迟到的旧 401 一律忽略），
   * 且仅当当前仍是已登录状态才切换为未登录并引导一次。
   */
  function observeUnauthorized(error: unknown, requestGeneration: number): boolean {
    if (!(error instanceof ApiError) || error.isTransportError) {
      return false
    }
    if (error.status !== 401 || error.code !== 'AUTH_REQUIRED') {
      return false
    }
    if (requestGeneration !== generation.value || status.value !== 'authenticated') {
      return false
    }
    invalidateProtectedState()
    redirectToLogin?.('/login')
    return true
  }

  return {
    status,
    account,
    generation,
    bindRedirect,
    capture,
    bootstrap,
    revalidate,
    login,
    logout,
    changePassword,
    observeUnauthorized,
  }
})
