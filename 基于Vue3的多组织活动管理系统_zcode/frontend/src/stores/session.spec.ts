import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '../shared/http/error'
import { useSessionStore } from './session'
import { useOrganizationsStore } from './organizations'

// 会话代次保护（R02）：迟到的成功响应与 401 不得改写新会话状态。
const fetchMock = vi.fn<typeof fetch>()

let currentUser = 'userA'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function defaultHandler(input: RequestInfo | URL): Response {
  const path = String(input)
  if (path.includes('/api/auth/csrf')) {
    return jsonResponse({ token: 'test-token', headerName: 'X-CSRF-TOKEN' })
  }
  if (path.includes('/api/auth/me')) {
    return jsonResponse({ id: '1', username: currentUser })
  }
  return new Response(null, { status: 204 })
}

function useHandler(): void {
  fetchMock.mockImplementation((input) => Promise.resolve(defaultHandler(input)))
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

function unauthorizedError() {
  return new ApiError({ kind: 'server', status: 401, code: 'AUTH_REQUIRED', detail: '未登录' })
}

beforeEach(() => {
  currentUser = 'userA'
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('会话代次保护', () => {
  it('登录 A 后返回的旧 me 成功响应不改写登录 B 的状态', async () => {
    const session = useSessionStore()
    useHandler()
    await session.login('userA', 'Passw0rd123')
    expect(session.account?.username).toBe('userA')

    // 挂起一次旧代次的 me 请求
    const stale = deferred<Response>()
    fetchMock.mockImplementationOnce((input) => {
      const path = String(input)
      if (path.includes('/api/auth/csrf')) {
        return Promise.resolve(jsonResponse({ token: 't', headerName: 'X-CSRF-TOKEN' }))
      }
      return stale.promise
    })
    const pendingBootstrap = session.bootstrap()

    // 切换到 B（代次递增）
    currentUser = 'userB'
    await session.login('userB', 'Passw0rd123')
    expect(session.account?.username).toBe('userB')

    // 旧 me（A 的）此刻返回：代次已变，不得覆盖
    stale.resolve(jsonResponse({ id: '1', username: 'userA' }))
    await pendingBootstrap
    expect(session.account?.username).toBe('userB')
  })

  it('旧代次的 401 不清除当前登录，也不触发登录引导', async () => {
    const session = useSessionStore()
    useHandler()
    await session.login('userA', 'Passw0rd123')

    const organizations = useOrganizationsStore()
    organizations.items = [
      { id: '9', name: '旧组织', role: 'OWNER', joinedAt: '2026-09-12T00:00:00Z' },
    ]

    // 请求发起于当前代次之前（模拟旧会话的 401）
    const staleGen = session.capture() - 1
    const handled = session.observeUnauthorized(unauthorizedError(), staleGen)

    expect(handled).toBe(false)
    expect(session.status).toBe('authenticated')
    expect(session.account?.username).toBe('userA')
    // 旧 401 不清空组织目录
    expect(organizations.items).toHaveLength(1)
  })

  it('当前代次的 401 单飞切换为未登录并清空受保护缓存', async () => {
    const session = useSessionStore()
    useHandler()
    await session.login('userA', 'Passw0rd123')

    const organizations = useOrganizationsStore()
    organizations.items = [
      { id: '9', name: '组织', role: 'OWNER', joinedAt: '2026-09-12T00:00:00Z' },
    ]

    let redirectTarget = ''
    session.bindRedirect((target) => {
      redirectTarget = target
    })

    const gen = session.capture()
    const first = session.observeUnauthorized(unauthorizedError(), gen)
    const second = session.observeUnauthorized(unauthorizedError(), gen)

    expect(first).toBe(true)
    // 并发第二个 401 不重复引导
    expect(second).toBe(false)
    expect(session.status).toBe('guest')
    expect(session.account).toBeNull()
    expect(organizations.items).toHaveLength(0)
    expect(redirectTarget).toBe('/login')
  })

  it('组织目录迟到响应不回填新会话', async () => {
    const session = useSessionStore()
    useHandler()
    await session.login('userA', 'Passw0rd123')
    const organizations = useOrganizationsStore()

    // 挂起目录请求（A 代次内发起）
    const stale = deferred<Response>()
    fetchMock.mockImplementationOnce((input) => {
      const path = String(input)
      if (path.includes('/api/auth/csrf')) {
        return Promise.resolve(jsonResponse({ token: 't', headerName: 'X-CSRF-TOKEN' }))
      }
      if (path.includes('/api/auth/me')) {
        return Promise.resolve(jsonResponse({ id: '1', username: 'userB' }))
      }
      return stale.promise
    })
    const loading = organizations.fetchDirectory(true)

    // 会话切到 B（代次递增）后旧目录响应才返回
    currentUser = 'userB'
    await session.login('userB', 'Passw0rd123')
    stale.resolve(
      jsonResponse({ items: [{ id: '1', name: '旧响应组织', role: 'OWNER', joinedAt: '2026-09-12T00:00:00Z' }] }),
    )
    await expect(loading).resolves.toBeUndefined()
    expect(organizations.items).toHaveLength(0)
  })
})
