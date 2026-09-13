export type ActivityStatus =
  | 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED'
  | 'EXECUTING' | 'REVIEWING' | 'ARCHIVED' | 'CANCELLED'
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
  /** S02：独立报名开关，新建与重新确认后默认关闭，仅影响新申请。 */
  registrationOpen: boolean
  /** S08／F05：编辑、方案保存与审批都需要回传的当前版本。 */
  version: number
  // P6：执行、取消、归档与总结
  startedAt: string | null
  endedAt: string | null
  archivedAt: string | null
  cancelledAt: string | null
  cancelReason: string | null
  reopenReason: string | null
  reopenCount: number
  summary: string | null
  summaryConfirmedAt: string | null
  summaryConfirmedBy: number | null
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
