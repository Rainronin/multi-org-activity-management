import { request } from '../../shared/http/client'

export type RegistrationStatus =
  | 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED_SELF' | 'CANCELLED_ORG'

export interface RegistrationItem {
  registrationId: string
  activityId: string
  accountId: string
  accountUsername: string
  displayName: string
  note: string | null
  status: RegistrationStatus
  version: number
  cancelReason: string | null
  decidedAt: string | null
  createdAt: string
  /** F01：所属活动的公开性安排，供“我的报名”展示与调整期提示。 */
  activityName: string | null
  organizationName: string | null
  location: string | null
  startTime: string | null
  endTime: string | null
  registrationOpen: boolean
  /** 活动处于调整期待确认（撤回草稿或重新提交待确认）：本人状态可读，显示安排调整中。 */
  adjusting: boolean
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
  activityId: string, input: { displayName: string; note?: string; version?: number },
): Promise<RegistrationItem> {
  const response = await request<RegistrationItem>(
    `/api/activities/${encodeURIComponent(activityId)}/registrations`,
    { method: 'POST', json: input },
  )
  return response.data
}

/** 本人当前报名是否可自助取消：活动开始前且未进入执行（后端同样校验）。 */
export function isCancellable(item: RegistrationItem, now = new Date()): boolean {
  if (item.status !== 'PENDING' && item.status !== 'APPROVED') {
    return false
  }
  return !item.startTime || now < new Date(item.startTime)
}

/** 本人取消：活动开始前且未进入执行；重复取消幂等。 */
export async function cancelMyRegistration(activityId: string, version: number): Promise<void> {
  await request(
    `/api/activities/${encodeURIComponent(activityId)}/registrations/cancel-self`,
    { method: 'POST', json: { version }, response: 'none' },
  )
}

/** 管理名单：组织负责人或本活动负责人。 */
export async function fetchActivityRegistrations(
  orgId: string, activityId: string, status?: string, page = 1, pageSize = 20,
): Promise<RegistrationPage> {
  // F09：名单分页交由服务端；响应中的 total 供面板显示页码与总数
  const search = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  if (status) {
    search.set('status', status)
  }
  const response = await request<RegistrationPage>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/registrations?${search.toString()}`,
  )
  return response.data
}

export async function approveRegistration(
  orgId: string, activityId: string, registrationId: string, version: number,
): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/registrations/${encodeURIComponent(registrationId)}/approve`,
    { method: 'POST', json: { version }, response: 'none' },
  )
}

export async function rejectRegistration(
  orgId: string, activityId: string, registrationId: string, reason: string, version: number,
): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/registrations/${encodeURIComponent(registrationId)}/reject`,
    { method: 'POST', json: { reason, version }, response: 'none' },
  )
}

export async function cancelRegistrationByManager(
  orgId: string, activityId: string, registrationId: string, reason: string, version: number,
): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/registrations/${encodeURIComponent(registrationId)}/cancel-org`,
    { method: 'POST', json: { reason, version }, response: 'none' },
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

/**
 * 受控导出（F09）：走统一请求入口拿 CSV，超限时后端返回 409 `EXPORT_TOO_LARGE`，
 * 由调用方按普通业务错误提示；不再用直链下载，避免把错误 JSON 当成文件存下来。
 */
export async function exportRegistrationRoster(
  orgId: string, activityId: string, status?: string,
): Promise<Blob> {
  const search = status ? `?status=${encodeURIComponent(status)}` : ''
  const response = await request<Blob>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/registrations/export${search}`,
    { response: 'blob' },
  )
  return response.data
}

/** 下载已取回的名单 CSV（文件名与导出范围对应）。 */
export function saveRosterCsv(blob: Blob, activityId: string, status?: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `registrations-${activityId}${status ? `-${status}` : ''}.csv`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
