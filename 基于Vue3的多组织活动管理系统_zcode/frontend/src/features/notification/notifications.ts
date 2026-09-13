import { request } from '../../shared/http/client'

/** P7.3：站内提示（只含必要摘要；列表已按当前权限复核）。 */
export interface NotificationItem {
  notificationId: string
  type: string
  payload: string
  createdAt: string
  readAt: string | null
  activityId: string | null
  activityName: string | null
}

export interface NotificationPage {
  items: NotificationItem[]
  page: number
  pageSize: number
  total: number
  unread: number
}

export const notificationTypeLabel: Record<string, string> = {
  TASK_ASSIGNED: '任务分派',
  REGISTRATION_APPROVED: '报名通过',
  REGISTRATION_REJECTED: '报名驳回',
  REGISTRATION_CANCELLED: '报名取消',
  REGISTRATION_CANCELLED_SELF: '报名取消',
  ACTIVITY_RECONFIRMED: '活动重新确认',
  ACTIVITY_CANCELLED: '活动取消',
  EXPENSE_SUBMITTED: '支出待确认',
  EXPENSE_APPROVED: '支出已确认',
  EXPENSE_REJECTED: '支出被驳回',
}

/** 本人提示列表：不可访问的条目后端已不下发。 */
export async function fetchNotifications(page = 1, pageSize = 20): Promise<NotificationPage> {
  const search = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  const response = await request<NotificationPage>(`/api/me/notifications?${search.toString()}`)
  return response.data
}

export async function markNotificationRead(notificationId: string): Promise<void> {
  await request(`/api/me/notifications/${encodeURIComponent(notificationId)}/read`, {
    method: 'POST',
    response: 'none',
  })
}

export async function markAllNotificationsRead(): Promise<void> {
  await request('/api/me/notifications/read-all', { method: 'POST', response: 'none' })
}
