import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './error'
import { request, setCsrfHeaderProvider } from './client'

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

function problemResponse(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/problem+json', 'X-Request-Id': 'req-1' },
  })
}

const fetchMock = vi.fn<typeof fetch>()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  setCsrfHeaderProvider(null)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('request', () => {
  it('正常 JSON 响应返回解析后的数据与请求标识', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ status: 'UP' }, { headers: { 'Content-Type': 'application/json', 'X-Request-Id': 'req-9' } }))
    const response = await request<{ status: string }>('/api/health')
    expect(response.status).toBe(200)
    expect(response.data.status).toBe('UP')
    expect(response.headers.get('X-Request-Id')).toBe('req-9')
  })

  it('204 无响应体返回 null，不调用 json()', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    const response = await request<void>('/api/auth/logout', { method: 'POST' })
    expect(response.status).toBe(204)
    expect(response.data).toBeNull()
  })

  it('ProblemDetail 错误映射为 ApiError，携带业务码与字段错误', async () => {
    fetchMock.mockResolvedValue(
      problemResponse(400, {
        title: '输入无效',
        status: 400,
        detail: '字段校验失败',
        code: 'VALIDATION_FAILED',
        requestId: 'req-1',
        fieldErrors: [{ field: 'amount', message: '金额必须大于零' }],
      }),
    )
    const error = await request('/api/expenses', { method: 'POST', json: {} }).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.isTransportError).toBe(false)
    expect(apiError.status).toBe(400)
    expect(apiError.code).toBe('VALIDATION_FAILED')
    expect(apiError.requestId).toBe('req-1')
    expect(apiError.fieldErrors[0]?.field).toBe('amount')
  })

  it('非 JSON 的失败响应返回本地传输错误，不冒充业务错误', async () => {
    fetchMock.mockResolvedValue(new Response('oops', { status: 502 }))
    const error = (await request('/api/ai/generate').catch((e: unknown) => e)) as ApiError
    expect(error.isTransportError).toBe(true)
    expect(error.code).toBe('INVALID_RESPONSE')
  })

  it('格式损坏的成功 JSON 返回安全的本地错误与请求标识', async () => {
    fetchMock.mockResolvedValue(new Response('{"secret":"broken', { headers: { 'Content-Type': 'application/json', 'X-Request-Id': 'req-body' } }))
    await expect(request('/api/data')).rejects.toMatchObject({ kind: 'transport', code: 'INVALID_RESPONSE', requestId: 'req-body' })
  })

  it.each([['json', 200], ['blob', 200], ['json', 503]] as const)('%s 正文中断（%s）也转换为传输错误', async (mode, status) => {
    const response = jsonResponse({ status, code: 'UNAVAILABLE' }, { status })
    vi.spyOn(response, mode).mockRejectedValue(new TypeError('body interrupted'))
    fetchMock.mockResolvedValue(response)
    await expect(request('/api/data', { response: mode })).rejects.toMatchObject({ kind: 'transport', code: 'NETWORK_ERROR' })
  })

  it('响应头已到而正文超时仍返回 REQUEST_TIMEOUT', async () => {
    fetchMock.mockImplementation(async (_path, options) => {
      const response = jsonResponse({ ok: true })
      vi.spyOn(response, 'json').mockImplementation(() => new Promise((_resolve, reject) => {
        options?.signal?.addEventListener('abort', () => reject(new DOMException('body aborted', 'AbortError')), { once: true })
      }))
      return response
    })
    await expect(request('/api/data', { timeoutMs: 5 })).rejects.toMatchObject({ kind: 'transport', code: 'REQUEST_TIMEOUT' })
  })

  it('响应正文读取中主动取消仍返回 REQUEST_ABORTED', async () => {
    const controller = new AbortController()
    const response = jsonResponse({ ok: true })
    vi.spyOn(response, 'json').mockImplementation(async () => {
      controller.abort()
      throw new DOMException('body aborted', 'AbortError')
    })
    fetchMock.mockResolvedValue(response)
    await expect(request('/api/data', { signal: controller.signal })).rejects.toMatchObject({ kind: 'transport', code: 'REQUEST_ABORTED' })
  })

  it('网络失败返回传输错误而非业务错误', async () => {
    fetchMock.mockRejectedValue(new TypeError('fetch failed'))
    const error = (await request('/api/health').catch((e: unknown) => e)) as ApiError
    expect(error.isTransportError).toBe(true)
    expect(error.code).toBe('NETWORK_ERROR')
  })

  it('外部取消返回 REQUEST_ABORTED', async () => {
    const controller = new AbortController()
    fetchMock.mockImplementation(() => {
      controller.abort()
      return Promise.reject(new DOMException('The operation was aborted.', 'AbortError'))
    })
    const error = (await request('/api/health', { signal: controller.signal }).catch((e: unknown) => e)) as ApiError
    expect(error.isTransportError).toBe(true)
    expect(error.code).toBe('REQUEST_ABORTED')
  })

  it('JSON 请求体设置 application/json，URLSearchParams 不手工设置边界', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse({ ok: true })))
    await request('/api/things', { method: 'POST', json: { a: 1 } })
    expect(fetchMock.mock.calls[0]?.[1]?.headers instanceof Headers).toBe(true)
    const jsonHeaders = fetchMock.mock.calls[0]?.[1]?.headers as Headers
    expect(jsonHeaders.get('Content-Type')).toBe('application/json')

    const params = new URLSearchParams({ username: 'a', password: 'b' })
    await request('/api/auth/login', { method: 'POST', form: params })
    const formInit = fetchMock.mock.calls[1]?.[1]
    expect(formInit?.body).toBe(params)
    expect((formInit?.headers as Headers).get('Content-Type')).toBeNull()
  })

  it('非安全方法自动附加会话层注册的 CSRF 头', async () => {
    setCsrfHeaderProvider(() => ({ 'X-CSRF-TOKEN': 'token-1' }))
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    await request('/api/things/1', { method: 'DELETE', response: 'none' })
    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Headers
    expect(headers.get('X-CSRF-TOKEN')).toBe('token-1')

    await request('/api/health')
    const getHeaders = fetchMock.mock.calls[1]?.[1]?.headers as Headers
    expect(getHeaders.get('X-CSRF-TOKEN')).toBeNull()
  })

  it('等待 CSRF 凭据期间取消，不发出写请求', async () => {
    const controller = new AbortController()
    let resolve!: (headers: Record<string, string>) => void
    setCsrfHeaderProvider(() => new Promise<Record<string, string>>(r => { resolve = r }))
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    const pending = request('/api/things', { method: 'POST', signal: controller.signal, response: 'none' })
    controller.abort()
    resolve({ 'X-CSRF-TOKEN': 'fresh-token' })
    await expect(pending).rejects.toMatchObject({ kind: 'transport', code: 'REQUEST_ABORTED' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('blob 响应模式返回 Blob', async () => {
    fetchMock.mockResolvedValue(
      new Response('csv,data', { headers: { 'Content-Type': 'text/csv' } }),
    )
    const response = await request<Blob>('/api/export', { response: 'blob' })
    expect(response.data).toBeInstanceOf(Blob)
  })

  it('JSON 响应模式遇到非 JSON 内容类型报传输错误', async () => {
    fetchMock.mockResolvedValue(new Response('plain', { headers: { 'Content-Type': 'text/plain' } }))
    const error = (await request('/api/health').catch((e: unknown) => e)) as ApiError
    expect(error.isTransportError).toBe(true)
    expect(error.code).toBe('UNEXPECTED_CONTENT_TYPE')
  })
})
