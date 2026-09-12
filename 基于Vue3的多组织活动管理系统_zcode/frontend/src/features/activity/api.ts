import { request } from '../../shared/http/client'
import { fetchMembers } from '../organization/api'
import type { MemberItem } from '../organization/types'
import type { ActivityDetail, ActivityItem, ActivityPage, CreateActivityInput } from './types'

export async function fetchActivities(
  orgId: string,
  params: { page?: number; pageSize?: number; query?: string; status?: string } = {},
): Promise<ActivityPage> {
  const search = new URLSearchParams()
  search.set('page', String(params.page ?? 1))
  search.set('pageSize', String(params.pageSize ?? 20))
  if (params.query) {
    search.set('query', params.query)
  }
  if (params.status) {
    search.set('status', params.status)
  }
  const response = await request<ActivityPage>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities?${search.toString()}`,
  )
  return response.data
}

export async function createActivity(orgId: string, input: CreateActivityInput): Promise<ActivityItem> {
  const response = await request<ActivityItem>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities`,
    { method: 'POST', json: input },
  )
  return response.data
}

/** 负责人选择数据：复用组织模块的成员分页查询（每组织量级小，首页 100 条足够）。 */
export async function fetchOwnerCandidates(orgId: string): Promise<MemberItem[]> {
  const page = await fetchMembers(orgId, { page: 1, pageSize: 100 })
  return page.items
}

export async function fetchActivity(orgId: string, activityId: string): Promise<ActivityDetail> {
  const response = await request<ActivityDetail>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}`,
  )
  return response.data
}

export async function updateActivity(
  orgId: string,
  activityId: string,
  patch: Partial<CreateActivityInput>,
): Promise<ActivityDetail> {
  const response = await request<ActivityDetail>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}`,
    { method: 'PATCH', json: patch },
  )
  return response.data
}

export async function saveProposal(orgId: string, activityId: string, content: string): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/proposal`,
    { method: 'PUT', json: { content }, response: 'none' },
  )
}

export async function submitActivity(orgId: string, activityId: string): Promise<ActivityDetail> {
  const response = await request<ActivityDetail>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/submit`,
    { method: 'POST', response: 'json' },
  )
  return response.data
}

export async function withdrawActivity(orgId: string, activityId: string): Promise<ActivityDetail> {
  const response = await request<ActivityDetail>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/withdraw`,
    { method: 'POST', response: 'json' },
  )
  return response.data
}

export async function approveActivity(orgId: string, activityId: string, note?: string): Promise<ActivityDetail> {
  const response = await request<ActivityDetail>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/approve`,
    { method: 'POST', json: { note: note || null } },
  )
  return response.data
}

export async function rejectActivity(orgId: string, activityId: string, reason: string): Promise<ActivityDetail> {
  const response = await request<ActivityDetail>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/reject`,
    { method: 'POST', json: { reason } },
  )
  return response.data
}

export async function withdrawApproval(orgId: string, activityId: string, reason?: string): Promise<ActivityDetail> {
  const response = await request<ActivityDetail>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/withdraw-approval`,
    { method: 'POST', json: { reason: reason || null } },
  )
  return response.data
}

export async function deleteActivityDraft(orgId: string, activityId: string): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}`,
    { method: 'DELETE', response: 'none' },
  )
}
