import { request } from '../../shared/http/client'

export interface TaskItem {
  taskId: string
  activityId: number
  title: string
  description: string | null
  assigneeMemberId: string
  assigneeUsername: string
  priority: 'HIGH' | 'MEDIUM' | 'LOW'
  dueAt: string | null
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'
  completedAt: string | null
  overdue: boolean
}

export interface SaveTaskInput {
  title: string
  description?: string
  assigneeMemberId: number
  priority?: string
  dueAt?: string
}

export async function fetchTasks(orgId: string, activityId: string): Promise<TaskItem[]> {
  const response = await request<{ items: TaskItem[] }>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/tasks`,
  )
  return response.data.items
}

export async function fetchMyTasks(orgId: string): Promise<TaskItem[]> {
  const response = await request<{ items: TaskItem[] }>(
    `/api/organizations/${encodeURIComponent(orgId)}/my-tasks`,
  )
  return response.data.items
}

export async function createTask(orgId: string, activityId: string, input: SaveTaskInput): Promise<TaskItem> {
  const response = await request<TaskItem>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/tasks`,
    { method: 'POST', json: input },
  )
  return response.data
}

export async function updateTask(
  orgId: string, activityId: string, taskId: string, input: SaveTaskInput,
): Promise<TaskItem> {
  const response = await request<TaskItem>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/tasks/${encodeURIComponent(taskId)}/update`,
    { method: 'POST', json: input },
  )
  return response.data
}

export async function advanceTask(
  orgId: string, activityId: string, taskId: string, toStatus: string,
): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/tasks/${encodeURIComponent(taskId)}/advance`,
    { method: 'POST', json: { toStatus }, response: 'none' },
  )
}

export async function transitionTask(
  orgId: string, activityId: string, taskId: string, toStatus: string,
): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/tasks/${encodeURIComponent(taskId)}/transition`,
    { method: 'POST', json: { toStatus }, response: 'none' },
  )
}

export interface MaterialItem {
  materialId: string
  activityId: number
  name: string
  quantity: number
  unit: string
  assigneeMemberId: string | null
  assigneeUsername: string
  status: 'PENDING' | 'IN_PROGRESS' | 'READY' | 'CANCELLED'
  note: string | null
}

export interface SaveMaterialInput {
  name: string
  quantity: number
  unit: string
  assigneeMemberId?: number
  note?: string
}

export async function fetchMaterials(orgId: string, activityId: string): Promise<MaterialItem[]> {
  const response = await request<{ items: MaterialItem[] }>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/materials`,
  )
  return response.data.items
}

export async function createMaterial(orgId: string, activityId: string, input: SaveMaterialInput): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/materials`,
    { method: 'POST', json: input, response: 'none' },
  )
}

export async function updateMaterial(
  orgId: string, activityId: string, materialId: string, input: SaveMaterialInput,
): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/materials/${encodeURIComponent(materialId)}/update`,
    { method: 'POST', json: input, response: 'none' },
  )
}

export async function changeMaterialStatus(
  orgId: string, activityId: string, materialId: string, toStatus: string,
): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/materials/${encodeURIComponent(materialId)}/transition`,
    { method: 'POST', json: { toStatus }, response: 'none' },
  )
}

export interface AttachmentItem {
  attachmentId: string
  activityId: string
  originalName: string
  contentType: string
  sizeBytes: number
  uploaderUsername: string
  createdAt: string
}

export async function fetchAttachments(orgId: string, activityId: string): Promise<AttachmentItem[]> {
  const response = await request<{ items: AttachmentItem[] }>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/attachments`,
  )
  return response.data.items
}

export async function uploadAttachment(
  orgId: string, activityId: string, file: File,
): Promise<void> {
  const csrfResp = await request<{ token: string; headerName: string }>('/api/auth/csrf')
  const form = new FormData()
  form.append('file', file)
  // 走统一 fetch 入口并带 CSRF 头（与 ActivityAttachments 约定一致）
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/attachments`,
    { method: 'POST', form, headers: { [csrfResp.data.headerName]: csrfResp.data.token }, response: 'none' },
  )
}

export async function deleteAttachment(orgId: string, activityId: string, attachmentId: string): Promise<void> {
  await request(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/attachments/${encodeURIComponent(attachmentId)}`,
    { method: 'DELETE', response: 'none' },
  )
}

export function downloadAttachment(orgId: string, activityId: string, attachmentId: string, name: string): void {
  // 浏览器下载走受控端点（携带会话 Cookie）
  const a = document.createElement('a')
  a.href = `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/attachments/${encodeURIComponent(attachmentId)}/download`
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
}


