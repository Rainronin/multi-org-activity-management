import { request } from '../../shared/http/client'
import type { CreateOrganizationInput, OrganizationDetail, OrganizationDirectoryItem } from './types'

export async function fetchMyOrganizations(): Promise<OrganizationDirectoryItem[]> {
  const response = await request<{ items: OrganizationDirectoryItem[] }>('/api/me/organizations')
  return response.data.items
}

export async function createOrganization(input: CreateOrganizationInput): Promise<OrganizationDirectoryItem> {
  const response = await request<OrganizationDirectoryItem & { joinedAt: string }>('/api/organizations', {
    method: 'POST',
    json: input,
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
