import { request } from '../../shared/http/client'

/** P7：工作台卡片（数值 + 口径说明 + 明细入口）。 */
export interface WorkbenchCard {
  key: string
  label: string
  value: string
  hint: string
  detailPath: string
  detailLabel: string
}

/** P7：待办（跳转后由目标端点重新鉴权）。 */
export interface WorkbenchTodo {
  kind: string
  title: string
  detail: string
  path: string
}

export interface Workbench {
  role: 'OWNER' | 'ACTIVITY_OWNER' | 'MEMBER'
  cards: WorkbenchCard[]
  todos: WorkbenchTodo[]
  myCards: WorkbenchCard[]
}

export const workbenchRoleLabel: Record<Workbench['role'], string> = {
  OWNER: '组织负责人',
  ACTIVITY_OWNER: '活动负责人',
  MEMBER: '普通成员',
}

export const todoKindLabel: Record<string, string> = {
  REGISTRATION_PENDING: '待审报名',
  TASK_OVERDUE: '逾期任务',
  MY_TASK_OVERDUE: '我的逾期任务',
  EXPENSE_PENDING: '待确认支出',
  ACTIVITY_REVIEW: '待归档',
}

/** 工作台：按当前身份返回管理卡片、待办与本人卡片（后端逐请求判定身份）。 */
export async function fetchWorkbench(orgId: string, todoLimit = 5): Promise<Workbench> {
  const response = await request<Workbench>(
    `/api/organizations/${encodeURIComponent(orgId)}/workbench?todoLimit=${todoLimit}`,
  )
  return response.data
}
