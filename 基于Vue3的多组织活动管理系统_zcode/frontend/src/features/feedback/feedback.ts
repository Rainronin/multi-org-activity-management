import { request } from '../../shared/http/client'

/** P6.3 反馈：固定 1～5 分总体评分与文字建议，每账号每活动一份。 */
export interface FeedbackItem {
  feedbackId: string
  version: number
  accountId: string
  accountUsername: string
  rating: number
  comment: string | null
  createdAt: string
  updatedAt: string
}

export interface FeedbackSummary {
  count: number
  /** 平均分（一位小数）；无反馈时为 null，界面显示“暂无反馈”与“—”。 */
  averageRating: string | null
  items: FeedbackItem[]
  mineSubmitted: boolean
  myRating: number | null
  myComment: string | null
  myVersion: number | null
  canSubmit: boolean
  submitBlockedReason: string | null
}

function feedbackUrl(orgId: string, activityId: string): string {
  return `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/feedback`
}

/** 反馈汇总与可见范围：管理者看全部，其他账号只看本人一份（后端逐请求判定）。 */
export async function fetchFeedback(orgId: string, activityId: string): Promise<FeedbackSummary> {
  const response = await request<FeedbackSummary>(feedbackUrl(orgId, activityId))
  return response.data
}

/** 提交或修改本人反馈；无资格或已关闭时后端返回 403／409。 */
export async function submitFeedback(
  orgId: string, activityId: string, rating: number, comment?: string, version?: number,
): Promise<FeedbackItem> {
  const response = await request<FeedbackItem>(feedbackUrl(orgId, activityId), {
    method: 'POST',
    json: { rating, comment: comment || null, version },
  })
  return response.data
}

/** 无反馈时显示“暂无反馈”与“—”，不推算到场率或满意度。 */
export function averageLabel(summary: FeedbackSummary | null): string {
  if (!summary || summary.count === 0 || summary.averageRating === null) {
    return '—'
  }
  return summary.averageRating
}
