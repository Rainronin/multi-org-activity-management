import { request } from '../../shared/http/client'
import { newIdempotencyKey } from '../../shared/http/idempotency'

export type ExpenseStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'VOIDED'

export type ExpenseCategory = 'MATERIAL' | 'VENUE' | 'TRANSPORT' | 'CATERING' | 'PRINTING' | 'OTHER'

export interface ExpenseItem {
  expenseId: string
  activityId: string
  purpose: string
  /** 两位小数字符串，统计与展示都不经浮点运算。 */
  amount: string
  incurredOn: string
  category: ExpenseCategory
  handledNote: string | null
  noReceiptNote: string | null
  status: ExpenseStatus
  submittedBy: string | null
  submittedAt: string | null
  decidedBy: string | null
  decidedAt: string | null
  rejectReason: string | null
  overBudgetNote: string | null
  voidReason: string | null
  voidedAt: string | null
  correctsExpenseId: string | null
  voucherCount: string
  version: number
}

export interface ExpensePage {
  items: ExpenseItem[]
  page: number
  pageSize: number
  total: number
}

/** P5.3 统计：预算、待确认支出、已确认支出与预算余额（余额允许为负）。 */
export interface ExpenseSummary {
  budget: string
  pendingAmount: string
  approvedAmount: string
  balance: string
  total: number
}

export interface SaveExpenseInput {
  purpose: string
  amount: string
  incurredOn: string
  category: ExpenseCategory
  handledNote?: string
  noReceiptNote?: string
}

export interface VoucherItem {
  attachmentId: string
  expenseId: string
  originalName: string
  contentType: string
  sizeBytes: number
  uploaderUsername: string
  createdAt: string
}

export const expenseStatusLabel: Record<ExpenseStatus, string> = {
  DRAFT: '草稿',
  PENDING: '待确认',
  APPROVED: '已确认',
  REJECTED: '已驳回',
  VOIDED: '已作废',
}

export const expenseCategoryLabel: Record<ExpenseCategory, string> = {
  MATERIAL: '物资采购',
  VENUE: '场地',
  TRANSPORT: '交通',
  CATERING: '餐饮',
  PRINTING: '印刷制作',
  OTHER: '其他',
}

function expensesUrl(orgId: string, activityId: string): string {
  return `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/expenses`
}

/** 明细列表：仅组织负责人或本活动负责人可读（后端逐请求判定）。 */
export async function fetchExpenses(
  orgId: string, activityId: string, status?: string, page = 1, pageSize = 20,
): Promise<ExpensePage> {
  const search = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  if (status) {
    search.set('status', status)
  }
  const response = await request<ExpensePage>(`${expensesUrl(orgId, activityId)}?${search.toString()}`)
  return response.data
}

export async function fetchExpenseSummary(
  orgId: string, activityId: string,
): Promise<ExpenseSummary> {
  const response = await request<ExpenseSummary>(`${expensesUrl(orgId, activityId)}/summary`)
  return response.data
}

/** 登记支出：必带 Idempotency-Key；同键同内容重放返回原记录。 */
export async function createExpense(
  orgId: string, activityId: string, input: SaveExpenseInput, idempotencyKey = newIdempotencyKey(),
): Promise<ExpenseItem> {
  const response = await request<ExpenseItem>(expensesUrl(orgId, activityId), {
    method: 'POST',
    json: input,
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return response.data
}

export async function updateExpense(
  orgId: string, activityId: string, expenseId: string, input: SaveExpenseInput, version: number,
): Promise<ExpenseItem> {
  const response = await request<ExpenseItem>(`${expensesUrl(orgId, activityId)}/${encodeURIComponent(expenseId)}/update`, {
    method: 'POST',
    json: { ...input, version },
  })
  return response.data
}

export async function submitExpense(
  orgId: string, activityId: string, expenseId: string, version: number,
): Promise<ExpenseItem> {
  const response = await request<ExpenseItem>(`${expensesUrl(orgId, activityId)}/${encodeURIComponent(expenseId)}/submit`, {
    method: 'POST',
    json: { version },
  })
  return response.data
}

/** 确认：超出预算时后端返回 409 EXPENSE_OVER_BUDGET，需带超支说明再次确认。 */
export async function confirmExpense(
  orgId: string, activityId: string, expenseId: string, version: number, overBudgetNote?: string,
): Promise<ExpenseItem> {
  const response = await request<ExpenseItem>(`${expensesUrl(orgId, activityId)}/${encodeURIComponent(expenseId)}/confirm`, {
    method: 'POST',
    json: { version, overBudgetNote: overBudgetNote || null },
  })
  return response.data
}

export async function rejectExpense(
  orgId: string, activityId: string, expenseId: string, version: number, reason: string,
): Promise<ExpenseItem> {
  const response = await request<ExpenseItem>(`${expensesUrl(orgId, activityId)}/${encodeURIComponent(expenseId)}/reject`, {
    method: 'POST',
    json: { version, reason },
  })
  return response.data
}

export async function voidExpense(
  orgId: string, activityId: string, expenseId: string, version: number, reason: string,
): Promise<ExpenseItem> {
  const response = await request<ExpenseItem>(`${expensesUrl(orgId, activityId)}/${encodeURIComponent(expenseId)}/void`, {
    method: 'POST',
    json: { version, reason },
  })
  return response.data
}

/** 更正登记：原已确认记录须先作废，再新建关联记录，重新提交与确认。 */
export async function createExpenseCorrection(
  orgId: string, activityId: string, expenseId: string, input: SaveExpenseInput,
  idempotencyKey = newIdempotencyKey(),
): Promise<ExpenseItem> {
  const response = await request<ExpenseItem>(
    `${expensesUrl(orgId, activityId)}/${encodeURIComponent(expenseId)}/correction`,
    { method: 'POST', json: input, headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return response.data
}

/** 凭证：与活动资料同一受控端点，归属为该笔支出。 */
export async function fetchVouchers(
  orgId: string, activityId: string, expenseId: string,
): Promise<VoucherItem[]> {
  const response = await request<{ items: VoucherItem[] }>(
    `${expensesUrl(orgId, activityId)}/${encodeURIComponent(expenseId)}/attachments`,
  )
  return response.data.items
}

export async function uploadVoucher(
  orgId: string, activityId: string, expenseId: string, file: File, idempotencyKey = newIdempotencyKey(),
): Promise<VoucherItem> {
  const csrf = await request<{ token: string; headerName: string }>('/api/auth/csrf')
  const form = new FormData()
  form.append('file', file)
  const response = await request<VoucherItem>(
    `${expensesUrl(orgId, activityId)}/${encodeURIComponent(expenseId)}/attachments`,
    {
      method: 'POST',
      form,
      headers: { [csrf.data.headerName]: csrf.data.token, 'Idempotency-Key': idempotencyKey },
    },
  )
  return response.data
}

export function voucherDownloadUrl(orgId: string, activityId: string, attachmentId: string): string {
  return `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}`
    + `/attachments/${encodeURIComponent(attachmentId)}/download`
}
