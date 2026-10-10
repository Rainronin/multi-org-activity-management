import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from './session'
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
  it('当前 CSRF 拒绝仅作废凭据，手动重试获取新值且保留会话', async () => {
    const session = useSessionStore()
    useHandler()
    await session.login('userA', 'Passw0rd123')
    const gen = session.capture()
    let token = 'old-token'
    let attempts = 0
    const csrfCalls = fetchMock.mock.calls.filter(([path]) => String(path).includes('/api/auth/csrf')).length
    fetchMock.mockImplementation(async (input, options) => {
      if (String(input).includes('/api/auth/csrf')) return jsonResponse({ token, headerName: 'X-CSRF-TOKEN' })
      if (String(input).includes('/api/auth/password')) {
        if (++attempts === 1) token = 'new-token'
        return new Headers(options?.headers).get('X-CSRF-TOKEN') === token
          ? new Response(null, { status: 204 }) : jsonResponse({ status: 403, code: 'CSRF_INVALID' }, 403)
      }
      return defaultHandler(input)
    })
    const error = await session.changePassword('Passw0rd123', 'Passw0rd456').catch(error => error)
    expect(session.observeUnauthorized(error, gen)).toBe(false)
    expect(attempts).toBe(1)
    expect(session.generation).toBe(gen)
    await session.changePassword('Passw0rd123', 'Passw0rd456')
    expect(session.status).toBe('authenticated')
    expect(fetchMock.mock.calls.filter(([path]) => String(path).includes('/api/auth/csrf'))).toHaveLength(csrfCalls + 2)
  })

  it('旧 CSRF 拒绝不能作废新会话凭据', async () => {
    const session = useSessionStore()
    useHandler()
    await session.login('userB', 'Passw0rd123')
    await session.changePassword('Passw0rd123', 'Passw0rd456')
    const csrfCalls = fetchMock.mock.calls.filter(([path]) => String(path).includes('/api/auth/csrf')).length
    const error = new ApiError({ kind: 'server', status: 403, code: 'CSRF_INVALID', detail: '凭据已失效' })
    expect(session.observeUnauthorized(error, session.capture() - 1)).toBe(false)
    await session.changePassword('Passw0rd123', 'Passw0rd456')
    expect(session.status).toBe('authenticated')
    expect(fetchMock.mock.calls.filter(([path]) => String(path).includes('/api/auth/csrf'))).toHaveLength(csrfCalls)
  })

  it('退出响应未知后以 me 确认退出，清空受保护状态', async () => {
    const session = useSessionStore()
    useHandler()
    await session.login('userA', 'Passw0rd123')
    const generation = session.capture()
    const organizations = useOrganizationsStore()
    organizations.items = [{ id: '9', name: '组织', role: 'OWNER', joinedAt: '' }]
    fetchMock.mockImplementation(async input => {
      const path = String(input)
      if (path.includes('/api/auth/logout')) return jsonResponse({ status: 503, code: 'UNAVAILABLE' }, 503)
      if (path.includes('/api/auth/me')) return jsonResponse({ status: 401, code: 'AUTH_REQUIRED' }, 401)
      return defaultHandler(input)
    })

    await expect(session.logout()).resolves.toBeUndefined()

    expect(session.status).toBe('guest')
    expect(session.account).toBeNull()
    expect(session.generation).toBe(generation + 1)
    expect(organizations.items).toEqual([])
  })

  it.each(['authenticated', 'transport'])('退出未确认且核对为 %s 时保留原状态，重试重新获取 CSRF', async result => {
    const session = useSessionStore()
    useHandler()
    await session.login('userA', 'Passw0rd123')
    const generation = session.capture()
    let token = 'before-logout'
    let attempts = 0
    const csrfCalls = fetchMock.mock.calls.filter(([path]) => String(path).includes('/api/auth/csrf')).length
    invalidateCsrfCredential()
    fetchMock.mockImplementation(async (input, options) => {
      const path = String(input)
      if (path.includes('/api/auth/csrf')) return jsonResponse({ token, headerName: 'X-CSRF-TOKEN' })
      if (path.includes('/api/auth/logout')) {
        expect(new Headers(options?.headers).get('X-CSRF-TOKEN')).toBe(token)
        if (++attempts === 1) {
          token = 'after-logout'
          return jsonResponse({ status: 503, code: 'UNAVAILABLE' }, 503)
        }
        return new Response(null, { status: 204 })
      }
      if (path.includes('/api/auth/me') && result === 'transport') throw new TypeError('Failed to fetch')
      return defaultHandler(input)
    })

    await expect(session.logout()).rejects.toMatchObject({ status: 503 })
    expect(session.status).toBe('authenticated')
    expect(session.account?.username).toBe('userA')
    expect(session.generation).toBe(generation)
    await session.logout()
    expect(session.status).toBe('guest')
    expect(fetchMock.mock.calls.filter(([path]) => String(path).includes('/api/auth/csrf'))).toHaveLength(csrfCalls + 2)
  })

  it.each([204, 503])('旧退出响应 %s 不清除新登录，也不再核对新会话', async status => {
    const session = useSessionStore()
    useHandler()
    await session.login('userA', 'Passw0rd123')
    const stale = deferred<Response>()
    let signal!: () => void
    const started = new Promise<void>(resolve => { signal = resolve })
    fetchMock.mockImplementation(async input => {
      if (String(input).includes('/api/auth/logout')) { signal(); return stale.promise }
      return defaultHandler(input)
    })
    const pending = session.logout()
    await started
    currentUser = 'userB'
    await session.login('userB', 'Passw0rd123')
    const generation = session.capture()
    const meCalls = fetchMock.mock.calls.filter(([path]) => String(path).includes('/api/auth/me')).length
    stale.resolve(status === 204 ? new Response(null, { status }) : jsonResponse({ status, code: 'UNAVAILABLE' }, status))
    await expect(pending).resolves.toBeUndefined()
    expect(session.status).toBe('authenticated')
    expect(session.account?.username).toBe('userB')
    expect(session.generation).toBe(generation)
    expect(fetchMock.mock.calls.filter(([path]) => String(path).includes('/api/auth/me'))).toHaveLength(meCalls)
  })

  it('登录响应未知后重试获取新 CSRF', async () => {
    const session = useSessionStore()
    invalidateCsrfCredential()
    let token = 'before-login'
    let firstLogin = true
    fetchMock.mockImplementation(async (input, options) => {
      const path = String(input)
      if (path.includes('/api/auth/csrf')) return jsonResponse({ token, headerName: 'X-CSRF-TOKEN' })
      if (path.includes('/api/auth/login')) {
        if (new Headers(options?.headers).get('X-CSRF-TOKEN') !== token) {
          return jsonResponse({ status: 403, code: 'CSRF_INVALID' }, 403)
        }
        if (firstLogin) {
          firstLogin = false
          token = 'after-login'
          return jsonResponse({ status: 503, code: 'UNAVAILABLE' }, 503)
        }
        return new Response(null, { status: 204 })
      }
      return defaultHandler(input)
    })

    await expect(session.login('userA', 'Passw0rd123')).rejects.toMatchObject({ status: 503 })
    await session.login('userA', 'Passw0rd123')

    expect(session.status).toBe('authenticated')
    expect(fetchMock.mock.calls.filter(([path]) => String(path).includes('/api/auth/csrf'))).toHaveLength(2)
  })

  it('匿名启动的 401 只确认未登录，不重复引导登录', async () => {
    const session = useSessionStore()
    const redirects: string[] = []
    session.bindRedirect(target => redirects.push(target))
    fetchMock.mockResolvedValue(jsonResponse({ status: 401, code: 'AUTH_REQUIRED', detail: '未登录' }, 401))

    await session.bootstrap()

    expect(session.status).toBe('guest')
    expect(redirects).toEqual([])
  })

  it('切回标签页的并发 401 清空缓存并只引导一次登录', async () => {
    const session = useSessionStore()
    useHandler()
    await session.login('userA', 'Passw0rd123')
    const organizations = useOrganizationsStore()
    organizations.items = [{ id: '9', name: '组织', role: 'OWNER', joinedAt: '' }]
    const redirects: string[] = []
    session.bindRedirect(target => redirects.push(target))
    const generation = session.capture()
    fetchMock.mockResolvedValue(jsonResponse({ status: 401, code: 'AUTH_REQUIRED', detail: '未登录' }, 401))

    await Promise.all([session.revalidate(), session.revalidate()])

    expect(session.status).toBe('guest')
    expect(session.account).toBeNull()
    expect(session.generation).toBe(generation + 1)
    expect(organizations.items).toEqual([])
    expect(redirects).toEqual(['/login'])
  })

  it('切回标签页的旧 401 不影响随后登录的新会话', async () => {
    const session = useSessionStore()
    useHandler()
    await session.login('userA', 'Passw0rd123')
    const redirects: string[] = []
    session.bindRedirect(target => redirects.push(target))
    const stale = deferred<Response>()
    fetchMock.mockImplementationOnce(() => stale.promise)
    const pending = session.revalidate()
    currentUser = 'userB'
    await session.login('userB', 'Passw0rd123')
    stale.resolve(jsonResponse({ status: 401, code: 'AUTH_REQUIRED', detail: '未登录' }, 401))
    await pending

    expect(session.status).toBe('authenticated')
    expect(session.account?.username).toBe('userB')
    expect(redirects).toEqual([])
  })

  it('切回标签页时网络失败保留当前会话与目录', async () => {
    const session = useSessionStore()
    useHandler()
    await session.login('userA', 'Passw0rd123')
    const organizations = useOrganizationsStore()
    organizations.items = [{ id: '9', name: '组织', role: 'OWNER', joinedAt: '' }]
    const redirects: string[] = []
    session.bindRedirect(target => redirects.push(target))
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))

    await session.revalidate()

    expect(session.status).toBe('authenticated')
    expect(session.account?.username).toBe('userA')
    expect(organizations.items).toHaveLength(1)
    expect(redirects).toEqual([])
  })

  it('离组后刷新目录，较早的同会话响应不得恢复已退出组织', async () => {
    const directory = useOrganizationsStore()
    const stale = deferred<Response>()
    fetchMock.mockImplementationOnce(() => stale.promise)
    const pending = directory.fetchDirectory()
    fetchMock.mockResolvedValueOnce(jsonResponse({ items: [] }))
    await directory.fetchDirectory(true)
    stale.resolve(jsonResponse({ items: [{ id: '1', name: '已退出组织', role: 'MEMBER', joinedAt: '' }] }))
    await pending
    expect(directory.items).toEqual([])
    expect(directory.loaded).toBe(true)
  })

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
