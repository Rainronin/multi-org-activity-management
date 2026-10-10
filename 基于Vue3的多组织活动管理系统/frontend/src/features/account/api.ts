import { request } from '../../shared/http/client'

export interface MeResponse {
  id: string
  username: string
}

export interface RegisteredResponse extends MeResponse {
  recoveryCode: string
}

export interface RecoveryResponse {
  recoveryCode: string
}

export interface RegisterInput {
  username: string
  password: string
}

export interface RecoverInput {
  username: string
  recoveryCode: string
  newPassword: string
}

export interface ChangePasswordInput {
  currentPassword: string
  newPassword: string
}

export async function register(input: RegisterInput): Promise<RegisteredResponse> {
  const response = await request<RegisteredResponse>('/api/auth/register', {
    method: 'POST',
    json: input,
  })
  return response.data
}

export async function recover(input: RecoverInput): Promise<RecoveryResponse> {
  const response = await request<RecoveryResponse>('/api/auth/recover', {
    method: 'POST',
    json: input,
  })
  return response.data
}

export async function reissueRecoveryCode(currentPassword: string): Promise<RecoveryResponse> {
  const response = await request<RecoveryResponse>('/api/auth/recovery/reissue', {
    method: 'POST',
    json: { currentPassword },
  })
  return response.data
}

export async function changePassword(input: ChangePasswordInput): Promise<void> {
  await request('/api/auth/password', { method: 'POST', json: input, response: 'none' })
}

/** 登录为表单编码（开发规范 2.4）；成功 204 无响应体。 */
export async function login(username: string, password: string): Promise<void> {
  await request('/api/auth/login', {
    method: 'POST',
    form: new URLSearchParams({ username, password }),
    response: 'none',
  })
}

export async function logout(): Promise<void> {
  await request('/api/auth/logout', { method: 'POST', response: 'none' })
}
