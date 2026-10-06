export interface FieldError {
  field: string
  message: string
}

/** 与后端 ProblemDetail（application/problem+json）一致的错误结构，含业务扩展字段。 */
export interface ProblemDetail {
  type?: string
  title?: string
  status: number
  detail?: string
  code?: string
  requestId?: string
  fieldErrors?: FieldError[]
}

export type ApiErrorKind = 'server' | 'transport'

/**
 * 统一错误对象：server 表示后端返回的业务错误响应；transport 表示网络、超时或主动取消，
 * 不能当作业务错误码提示。传输层不做弹窗与路由跳转（开发规范 2.3）。
 */
export class ApiError extends Error {
  readonly kind: ApiErrorKind
  readonly status: number
  readonly code: string
  readonly detail: string
  readonly requestId?: string
  readonly fieldErrors: FieldError[]

  constructor(params: {
    kind: ApiErrorKind
    status?: number
    code: string
    detail: string
    requestId?: string
    fieldErrors?: FieldError[]
    cause?: unknown
  }) {
    super(params.detail || params.code, { cause: params.cause })
    this.name = 'ApiError'
    this.kind = params.kind
    this.status = params.status ?? 0
    this.code = params.code
    this.detail = params.detail
    this.requestId = params.requestId
    this.fieldErrors = params.fieldErrors ?? []
  }

  get isTransportError(): boolean {
    return this.kind === 'transport'
  }
}

/** 读取区段统一区分传输失败与服务端权限／资源状态，不附带页面副作用。 */
export function readErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.isTransportError) return '网络连接失败，请检查网络后重试。'
    if (error.status === 403) return '无权访问此内容。'
    if (error.status === 404) return '内容不存在或已不可访问。'
  }
  return fallback
}

export function toProblemDetail(value: unknown): ProblemDetail | null {
  if (typeof value !== 'object' || value === null) {
    return null
  }
  const record = value as Record<string, unknown>
  if (typeof record.status !== 'number') {
    return null
  }
  const fieldErrors = Array.isArray(record.fieldErrors)
    ? record.fieldErrors.filter(
        (item): item is FieldError =>
          typeof item === 'object' &&
          item !== null &&
          typeof (item as Record<string, unknown>).field === 'string' &&
          typeof (item as Record<string, unknown>).message === 'string',
      )
    : undefined
  return {
    type: typeof record.type === 'string' ? record.type : undefined,
    title: typeof record.title === 'string' ? record.title : undefined,
    status: record.status,
    detail: typeof record.detail === 'string' ? record.detail : undefined,
    code: typeof record.code === 'string' ? record.code : undefined,
    requestId: typeof record.requestId === 'string' ? record.requestId : undefined,
    fieldErrors,
  }
}
