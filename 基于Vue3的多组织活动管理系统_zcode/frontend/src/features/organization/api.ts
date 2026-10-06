import { request } from '../../shared/http/client'
import type {
  AcceptInvitationResult,
  CreateOrganizationInput,
  InvitationCreated,
  InvitationItem,
  MemberItem,
  OrganizationDetail,
  OrganizationDirectoryItem,
} from './types'

export async function fetchMyOrganizations(): Promise<OrganizationDirectoryItem[]> {
  const response = await request<{ items: OrganizationDirectoryItem[] }>('/api/me/organizations')
  return response.data.items
}

export async function createOrganization(input: CreateOrganizationInput, requestKey: string): Promise<OrganizationDirectoryItem> {
  const response = await request<OrganizationDirectoryItem & { joinedAt: string }>('/api/organizations', {
    method: 'POST',
    json: input,
    headers: { 'Idempotency-Key': requestKey },
  })
  return {
    id: response.data.id,
    name: response.data.name,
    role: response.data.role,
    joinedAt: response.data.joinedAt,
  }
}

export async function fetchOrganization(orgId: string): Promise<OrganizationDetail> {
  const response = await request<OrganizationDetail>(`/api/organizations/${encodeURIComponent(orgId)}`)
  return response.data
}

export interface MemberPage {
  items: MemberItem[]
  page: number
  pageSize: number
  total: number
}

export async function fetchMembers(
  orgId: string,
  params: { page?: number; pageSize?: number; query?: string } = {},
): Promise<MemberPage> {
  const search = new URLSearchParams()
  search.set('page', String(params.page ?? 1))
  search.set('pageSize', String(params.pageSize ?? 20))
  if (params.query) {
    search.set('query', params.query)
  }
  const response = await request<MemberPage>(
    `/api/organizations/${encodeURIComponent(orgId)}/members?${search.toString()}`,
  )
  return response.data
}

export async function createInvitation(
  orgId: string,
  input: { expiresInDays?: number; maxUses?: number },
  requestKey: string,
): Promise<InvitationCreated> {
  const response = await request<InvitationCreated>(`/api/organizations/${encodeURIComponent(orgId)}/invitations`, {
    method: 'POST',
    json: input,
    headers: { 'Idempotency-Key': requestKey },
  })
  return response.data
}

export async function fetchInvitations(orgId: string): Promise<InvitationItem[]> {
  const response = await request<{ items: InvitationItem[] }>(
    `/api/organizations/${encodeURIComponent(orgId)}/invitations`,
  )
  return response.data.items
}

export async function revokeInvitation(orgId: string, invitationId: string): Promise<void> {
  await request(`/api/organizations/${encodeURIComponent(orgId)}/invitations/${encodeURIComponent(invitationId)}/revoke`, {
    method: 'POST',
    response: 'none',
  })
}

export async function acceptInvitation(code: string): Promise<AcceptInvitationResult> {
  const response = await request<AcceptInvitationResult>('/api/invitations/accept', {
    method: 'POST',
    json: { code },
  })
  return response.data
}

export async function leaveOrganization(orgId: string): Promise<void> {
  await request(`/api/organizations/${encodeURIComponent(orgId)}/members/me`, { method: 'DELETE', response: 'none' })
}

export async function removeMember(orgId: string, memberId: string): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/members/${encodeURIComponent(memberId)}`,
    { method: 'DELETE', response: 'none' },
  )
}

export async function promoteOwner(orgId: string, memberId: string): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/members/${encodeURIComponent(memberId)}/promote-owner`,
    { method: 'POST', response: 'none' },
  )
}

export async function revokeOwner(orgId: string, memberId: string): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/members/${encodeURIComponent(memberId)}/revoke-owner`,
    { method: 'POST', response: 'none' },
  )
}

export async function transferOwnership(orgId: string, toMemberId: string): Promise<void> {
  await request(`/api/organizations/${encodeURIComponent(orgId)}/ownership/transfer`, {
    method: 'POST',
    json: { toMemberId },
    response: 'none',
  })
}
