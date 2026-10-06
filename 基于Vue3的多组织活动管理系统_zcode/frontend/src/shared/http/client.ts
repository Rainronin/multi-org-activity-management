import { ApiError, toProblemDetail } from './error'

export type RequestMethod = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'

export type ResponseMode = 'json' | 'blob' | 'none'

export interface ClientRequest {
  method?: RequestMethod
  /** JSON 请求体，自动设置 application/json。 */
  json?: unknown
  /** URLSearchParams（登录等表单编码）或 FormData（上传，不手工设置边界）。 */
  form?: URLSearchParams | FormData
  /** Blob 请求体，Content-Type 取 blob.type。 */
  blob?: Blob
  query?: URLSearchParams
  signal?: AbortSignal
  timeoutMs?: number
  /** 会话层注入的额外请求头（如 CSRF）；CSRF 头由 provider 对非安全方法自动附加。 */
  headers?: Record<string, string>
  response?: ResponseMode
}

export interface ClientResponse<T> {
  status: number
  headers: Headers
  data: T
}

type CsrfHeaderProvider = () => Record<string, string> | Promise<Record<string, string>>

// CSRF 获取与刷新由会话协调层单飞处理后注册到这里；client 不反向依赖 store（前端架构 2.1）。
let csrfHeaderProvider: CsrfHeaderProvider | null = null

export function setCsrfHeaderProvider(provider: CsrfHeaderProvider | null): void {
  csrfHeaderProvider = provider
}

function isSafeMethod(method: RequestMethod): boolean {
  return method === 'GET'
}

async function resolveHeaders(request: ClientRequest, method: RequestMethod): Promise<Headers> {
  const headers = new Headers(request.headers)
  if (request.json !== undefined) {
    headers.set('Content-Type', 'application/json')
  }
  if (typeof request.blob !== 'undefined' && request.blob.type) {
    headers.set('Content-Type', request.blob.type)
  }
  if (!isSafeMethod(method) && csrfHeaderProvider) {
    const csrfHeaders = await csrfHeaderProvider()
    for (const [name, value] of Object.entries(csrfHeaders)) {
      if (!headers.has(name)) {
        headers.set(name, value)
      }
    }
  }
  return headers
}

export async function request<T = unknown>(path: string, options: ClientRequest = {}): Promise<ClientResponse<T>> {
  const method = options.method ?? 'GET'
  const responseMode: ResponseMode = options.response ?? 'json'
  // 发起前已取消的请求直接失败，不进入网络（开发规范 2.3：主动取消不冒充业务错误）。
  if (options.signal?.aborted) {
    throw new ApiError({ kind: 'transport', code: 'REQUEST_ABORTED', detail: '请求已取消。' })
  }

  const headers = await resolveHeaders(options, method)
  if (options.signal?.aborted) {
    throw new ApiError({ kind: 'transport', code: 'REQUEST_ABORTED', detail: '请求已取消。' })
  }

  const timeoutController = new AbortController()
  let timedOut = false
  let timeoutId: ReturnType<typeof setTimeout> | undefined
  if (options.timeoutMs) {
    timeoutId = setTimeout(() => {
      timedOut = true
      timeoutController.abort()
    }, options.timeoutMs)
  }
  const onExternalAbort = () => timeoutController.abort()
  options.signal?.addEventListener('abort', onExternalAbort)

  // 超时与取消保护覆盖响应体的完整读取：头到达即解除监听会让正文读取脱离保护（R08 审核项）。
  let requestId: string | undefined
  try {
    const response = await fetch(options.query ? `${path}?${options.query.toString()}` : path, {
      method,
      headers,
      body: options.json !== undefined ? JSON.stringify(options.json) : options.form ?? options.blob ?? null,
      signal: timeoutController.signal,
      credentials: 'same-origin',
    })

    requestId = response.headers.get('X-Request-Id') ?? undefined

    if (!response.ok) {
      const problem = toProblemDetail(await response.json())
      if (!problem) throw new SyntaxError('Invalid ProblemDetail')
      throw new ApiError({
        kind: 'server',
        status: response.status,
        code: problem?.code ?? `HTTP_${response.status}`,
        detail: problem?.detail ?? `请求失败（HTTP ${response.status}）。`,
        requestId: problem?.requestId ?? requestId,
        fieldErrors: problem?.fieldErrors ?? [],
      })
    }

    if (responseMode === 'none' || response.status === 204) {
      return { status: response.status, headers: response.headers, data: null as T }
    }

    if (responseMode === 'blob') {
      const blob = await response.blob()
      return { status: response.status, headers: response.headers, data: blob as T }
    }

    const contentType = response.headers.get('Content-Type') ?? ''
    if (!contentType.includes('application/json')) {
      throw new ApiError({
        kind: 'transport',
        code: 'UNEXPECTED_CONTENT_TYPE',
        detail: '服务端返回了意外的内容类型。',
        requestId,
      })
    }
    const data = (await response.json()) as T
    return { status: response.status, headers: response.headers, data }
  } catch (cause) {
    if (cause instanceof ApiError) throw cause
    if (timedOut) {
      throw new ApiError({ kind: 'transport', code: 'REQUEST_TIMEOUT', detail: '请求超时，请稍后重试。', requestId, cause })
    }
    if (options.signal?.aborted) {
      throw new ApiError({ kind: 'transport', code: 'REQUEST_ABORTED', detail: '请求已取消。', requestId, cause })
    }
    throw new ApiError(cause instanceof SyntaxError
      ? { kind: 'transport', code: 'INVALID_RESPONSE', detail: '响应内容无法读取，请核对操作结果后重试。', requestId, cause }
      : { kind: 'transport', code: 'NETWORK_ERROR', detail: '网络连接失败，请检查网络后重试。', requestId, cause })
  } finally {
    if (timeoutId) {
      clearTimeout(timeoutId)
    }
    options.signal?.removeEventListener('abort', onExternalAbort)
  }
}
