import { request } from '../../shared/http/client'

/** 公开活动（P4.1）：未登录可读，字段与 `PublicActivityController` 的白名单一一对应。 */
export interface PublicActivityItem {
  id: string
  organizationName: string
  name: string
  description: string | null
  location: string | null
  startTime: string | null
  endTime: string | null
  registrationScope: 'INTERNAL' | 'PUBLIC'
  capacity: number | null
  registrationStart: string | null
  registrationEnd: string | null
  registrationOpen: boolean
  updatedAt: string
}

export interface PublicActivityPage {
  items: PublicActivityItem[]
  page: number
  pageSize: number
  total: number
}

export async function fetchPublicActivities(page = 1, pageSize = 20): Promise<PublicActivityPage> {
  const search = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  const response = await request<PublicActivityPage>(`/api/public/activities?${search.toString()}`)
  return response.data
}

export async function fetchPublicActivity(activityId: string): Promise<PublicActivityItem> {
  const response = await request<PublicActivityItem>(
    `/api/public/activities/${encodeURIComponent(activityId)}`,
  )
  return response.data
}

/** 报名是否处于可申请窗口：开关开启、在时间窗内且活动未开始（与后端 apply 校验一致）。 */
export function canApply(item: PublicActivityItem, now = new Date()): boolean {
  if (!item.registrationOpen) {
    return false
  }
  if (item.registrationStart && now < new Date(item.registrationStart)) {
    return false
  }
  if (item.registrationEnd && now > new Date(item.registrationEnd)) {
    return false
  }
  return !item.startTime || now < new Date(item.startTime)
}

export function applyBlockReason(item: PublicActivityItem, now = new Date()): string {
  if (!item.registrationOpen) {
    return '报名尚未开放。'
  }
  if (item.registrationStart && now < new Date(item.registrationStart)) {
    return '报名尚未开始。'
  }
  if (item.registrationEnd && now > new Date(item.registrationEnd)) {
    return '报名已截止。'
  }
  if (item.startTime && now >= new Date(item.startTime)) {
    return '活动已开始，报名不再开放。'
  }
  return ''
}

/** P9.1：公开活动参与者在复盘中可反馈，不必加入组织；只返回汇总与本人一份。 */
export interface PublicFeedbackSummary {
  count: number
  averageRating: string | null
  mineSubmitted: boolean
  myRating: number | null
  myComment: string | null
  canSubmit: boolean
  submitBlockedReason: string | null
}

export async function fetchPublicFeedback(activityId: string): Promise<PublicFeedbackSummary> {
  const response = await request<PublicFeedbackSummary>(
    `/api/public/activities/${encodeURIComponent(activityId)}/feedback`,
  )
  return response.data
}

export async function submitPublicFeedback(
  activityId: string,
  rating: number,
  comment?: string,
): Promise<void> {
  await request(`/api/public/activities/${encodeURIComponent(activityId)}/feedback`, {
    method: 'POST',
    json: { rating, comment: comment && comment.trim() ? comment.trim() : null },
  })
}
