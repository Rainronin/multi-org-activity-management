import { request } from '../../shared/http/client'

/** P6：活动主状态（含执行、复盘、归档与取消）。 */
export type ActivityLifecycleStatus =
  | 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'EXECUTING' | 'REVIEWING' | 'ARCHIVED' | 'CANCELLED'

export type ExecutionRecordCategory =
  | 'PROGRESS' | 'ISSUE' | 'ARRANGEMENT' | 'DEVIATION' | 'HANDLING'

export interface ExecutionRecordItem {
  recordId: string
  category: ExecutionRecordCategory
  content: string
  recordedBy: number
  recordedByUsername: string
  createdAt: string
}

export const executionCategoryLabel: Record<ExecutionRecordCategory, string> = {
  PROGRESS: '进展',
  ISSUE: '问题',
  ARRANGEMENT: '临时安排',
  DEVIATION: '实际差异',
  HANDLING: '处理结果',
}

/** P6.1：执行阶段可写（筹备、执行、复盘），归档与取消后的普通写入关闭。 */
export function isExecutionRecordable(status: ActivityLifecycleStatus): boolean {
  return status === 'EXECUTING' || status === 'REVIEWING' || status === 'CANCELLED'
}

/** P6.3：反馈窗口——首次进入复盘后开放，首次归档后永久关闭。 */
export function isFeedbackWindowOpen(status: ActivityLifecycleStatus, everArchived: boolean): boolean {
  return status === 'REVIEWING' && !everArchived
}

/** P6.5：可归档状态（复盘中的正常活动或取消处理中的活动）。 */
export function isArchivable(status: ActivityLifecycleStatus): boolean {
  return status === 'REVIEWING' || status === 'CANCELLED'
}

function base(orgId: string, activityId: string): string {
  return `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}`
}

async function postLifecycle(
  orgId: string, activityId: string, action: string, body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const response = await request<Record<string, unknown>>(`${base(orgId, activityId)}/${action}`, {
    method: 'POST',
    json: body,
  })
  return response.data
}

/** P6.1：明确启动执行（不根据报名开关或时间自动跳转）。 */
export async function startExecution(orgId: string, activityId: string, version: number) {
  return postLifecycle(orgId, activityId, 'start-execution', { version })
}

/** P6.1：结束执行 → 复盘中；未完成任务不自动标为完成。 */
export async function finishExecution(orgId: string, activityId: string, version: number) {
  return postLifecycle(orgId, activityId, 'finish-execution', { version })
}

/** P6.2：取消活动（仅组织负责人），需说明原因。 */
export async function cancelActivity(orgId: string, activityId: string, reason: string, version: number) {
  return postLifecycle(orgId, activityId, 'cancel', { reason, version })
}

/** P6.4：保存总结草稿；保存即作废上一次确认，需重新核对来源后确认。 */
export async function saveSummary(orgId: string, activityId: string, summary: string, version: number) {
  const response = await request<Record<string, unknown>>(`${base(orgId, activityId)}/summary`, {
    method: 'PUT',
    json: { summary, version },
  })
  return response.data
}

export async function confirmSummary(orgId: string, activityId: string, version: number) {
  return postLifecycle(orgId, activityId, 'summary/confirm', { version })
}

/** P6.5：归档；条件不满足时后端返回 409 ARCHIVE_BLOCKED 并列出待处理项。 */
export async function archiveActivity(orgId: string, activityId: string, version: number) {
  return postLifecycle(orgId, activityId, 'archive', { version })
}

/** P6.5：重新打开（归档回复盘、取消回取消处理），需说明理由。 */
export async function reopenActivity(orgId: string, activityId: string, reason: string, version: number) {
  return postLifecycle(orgId, activityId, 'reopen', { reason, version })
}

export async function fetchExecutionRecords(
  orgId: string, activityId: string,
): Promise<ExecutionRecordItem[]> {
  const response = await request<{ items: ExecutionRecordItem[] }>(
    `${base(orgId, activityId)}/execution-records`,
  )
  return response.data.items
}

export async function addExecutionRecord(
  orgId: string, activityId: string, category: ExecutionRecordCategory, content: string,
): Promise<ExecutionRecordItem> {
  const response = await request<ExecutionRecordItem>(`${base(orgId, activityId)}/execution-records`, {
    method: 'POST',
    json: { category, content },
  })
  return response.data
}
