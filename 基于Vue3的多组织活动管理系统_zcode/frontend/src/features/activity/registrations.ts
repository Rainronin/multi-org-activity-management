import { request } from '../../shared/http/client'

export type RegistrationStatus =
  | 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED_SELF' | 'CANCELLED_ORG'

export interface RegistrationItem {
  registrationId: string
  activityId: string
  accountId: number
  accountUsername: string
  displayName: string
  note: string | null
  status: RegistrationStatus
  cancelReason: string | null
  decidedAt: string | null
  createdAt: string
}

export interface RegistrationPage {
  items: RegistrationItem[]
  page: number
  pageSize: number
  total: number
}

export const registrationStatusLabel: Record<RegistrationStatus, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  CANCELLED_SELF: '本人取消',
  CANCELLED_ORG: '组织取消',
}

/** 本人报名记录（跨组织）。 */
export async function fetchMyRegistrations(): Promise<RegistrationItem[]> {
  const response = await request<RegistrationPage>('/api/me/registrations')
  return response.data.items
}

/** 报名/重新申请：活动已确认、报名开关开启且在窗口内（后端逐项校验）。 */
export async function applyRegistration(
  activityId: string, input: { displayName: string; note?: string },
): Promise<RegistrationItem> {
  const response = await request<RegistrationItem>(
    `/api/activities/${encodeURIComponent(activityId)}/registrations`,
    { method: 'POST', json: input },
  )
  return response.data
}

/** 本人取消：活动开始前且未进入执行；重复取消幂等。 */
export async function cancelMyRegistration(activityId: string): Promise<void> {
  await request(
    `/api/activities/${encodeURIComponent(activityId)}/registrations/cancel-self`,
    { method: 'POST', response: 'none' },
  )
}

/** 管理名单：组织负责人或本活动负责人。 */
export async function fetchActivityRegistrations(
  orgId: string, activityId: string, status?: string,
): Promise<RegistrationPage> {
  const search = new URLSearchParams({ page: '1', pageSize: '100' })
  if (status) {
    search.set('status', status)
  }
  const response = await request<RegistrationPage>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/registrations?${search.toString()}`,
  )
  return response.data
}

export async function approveRegistration(
  orgId: string, activityId: string, registrationId: string,
): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/registrations/${encodeURIComponent(registrationId)}/approve`,
    { method: 'POST', response: 'none' },
  )
}

export async function rejectRegistration(
  orgId: string, activityId: string, registrationId: string, reason: string,
): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/registrations/${encodeURIComponent(registrationId)}/reject`,
    { method: 'POST', json: { reason }, response: 'none' },
  )
}

export async function cancelRegistrationByManager(
  orgId: string, activityId: string, registrationId: string, reason: string,
): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/registrations/${encodeURIComponent(registrationId)}/cancel-org`,
    { method: 'POST', json: { reason }, response: 'none' },
  )
}

/** 报名开关：组织负责人或活动负责人；仅已确认且活动未开始时可变。 */
export async function setRegistrationOpen(
  orgId: string, activityId: string, open: boolean,
): Promise<boolean> {
  const response = await request<{ registrationOpen: boolean }>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/registration-open`,
    { method: 'POST', json: { open } },
  )
  return response.data.registrationOpen
}

/** 受控导出：浏览器直接下载受控端点（携带会话 Cookie）。 */
export function downloadRegistrationRoster(
  orgId: string, activityId: string, status?: string,
): void {
  const search = status ? `?status=${encodeURIComponent(status)}` : ''
  const link = document.createElement('a')
  link.href = `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/registrations/export${search}`
  link.download = `registrations-${activityId}.csv`
  document.body.appendChild(link)
  link.click()
  link.remove()
}
