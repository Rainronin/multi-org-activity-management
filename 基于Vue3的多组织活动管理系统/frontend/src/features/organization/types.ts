export type OrganizationRole = 'OWNER' | 'MEMBER'

export interface OrganizationDirectoryItem {
  id: string
  name: string
  role: OrganizationRole
  joinedAt: string
}

export interface OrganizationDetail {
  id: string
  name: string
  description: string | null
  role: OrganizationRole
  memberCount: number
  createdAt: string
}

export interface CreateOrganizationInput {
  name: string
  description?: string
}

export interface MemberItem {
  memberId: string
  username: string
  role: OrganizationRole
  joinedAt: string
}

export interface InvitationItem {
  invitationId: string
  createdBy: string
  createdAt: string
  expiresAt: string
  maxUses: number
  usedCount: number
  status: string
}

export interface InvitationCreated {
  invitationId: string
  code: string | null
  expiresAt: string
  maxUses: number
}

export interface AcceptInvitationResult {
  orgId: string
  name: string
  role: OrganizationRole
}

export function roleLabel(role: OrganizationRole): string {
  return role === 'OWNER' ? '组织负责人' : '成员'
}
