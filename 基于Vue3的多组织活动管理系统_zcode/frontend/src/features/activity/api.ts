import { request } from '../../shared/http/client'
import { fetchMembers } from '../organization/api'
import type { MemberItem } from '../organization/types'
import type { ActivityItem, ActivityPage, CreateActivityInput } from './types'

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
