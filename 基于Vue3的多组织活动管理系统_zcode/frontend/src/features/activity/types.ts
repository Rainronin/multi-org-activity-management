export type ActivityStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED'
export type RegistrationScope = 'INTERNAL' | 'PUBLIC'

export interface ActivityItem {
  id: string
  name: string
  description: string | null
  ownerMemberId: string
  ownerUsername: string
  status: ActivityStatus
  proposal: string | null
  reviewNote: string | null
  submittedBy: number | null
  confirmedBy: number | null
  confirmType: string | null
  startTime: string | null
  endTime: string | null
  location: string | null
  budget: string
  registrationScope: RegistrationScope
  capacity: number | null
  registrationStart: string | null
  registrationEnd: string | null
  createdAt: string
  updatedAt: string
}

export type ActivityDetail = ActivityItem

export interface ActivityPage {
  items: ActivityItem[]
  page: number
  pageSize: number
  total: number
}

export interface CreateActivityInput {
  name: string
  ownerMemberId: string
  description?: string
  startTime?: string
  endTime?: string
  location?: string
  budget?: string
  registrationScope?: RegistrationScope
  capacity?: number
  registrationStart?: string
  registrationEnd?: string
}

export function scopeLabel(scope: RegistrationScope): string {
  return scope === 'PUBLIC' ? '公开报名' : '内部报名'
}

export function formatDateTime(value: string | null): string {
  if (!value) {
    return '—'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value))
}
