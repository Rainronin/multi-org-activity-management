import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createExpense,
  expenseCategoryLabel,
  expenseStatusLabel,
  fetchExpenses,
  rejectExpense,
  type ExpenseItem,
} from './expense'

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

function item(overrides: Partial<ExpenseItem> = {}): ExpenseItem {
  return {
    expenseId: '1',
    activityId: '10',
    purpose: '横幅制作',
    amount: '40.00',
    incurredOn: '2027-07-01',
    category: 'MATERIAL',
    handledNote: null,
    noReceiptNote: '供应商未开票',
    status: 'DRAFT',
    submittedBy: null,
    submittedAt: null,
    decidedBy: null,
    decidedAt: null,
    rejectReason: null,
    overBudgetNote: null,
    voidReason: null,
    voidedAt: null,
    correctsExpenseId: null,
    voucherCount: '0',
    version: 1,
    ...overrides,
  }
}

const fetchMock = vi.fn<typeof fetch>()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('经费接口与展示口径', () => {
  it('明细查询带上分页与状态筛选，并保留服务端 total', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ items: [item()], page: 1, pageSize: 20, total: 25 }))
    const page = await fetchExpenses('7', '10', 'PENDING', 2, 20)
    const url = String(fetchMock.mock.calls[0]?.[0])
    expect(url).toContain('/api/organizations/7/activities/10/expenses?')
    expect(url).toContain('page=2')
    expect(url).toContain('pageSize=20')
    expect(url).toContain('status=PENDING')
    expect(page.total).toBe(25)
    expect(page.items[0]?.amount).toBe('40.00')
  })

  it('登记支出自动携带 Idempotency-Key 且金额按两位小数字符串提交', async () => {
    fetchMock.mockResolvedValue(jsonResponse(item(), { status: 201 }))
    await createExpense('7', '10', {
      purpose: '横幅制作',
      amount: '40.00',
      incurredOn: '2027-07-01',
      category: 'MATERIAL',
      noReceiptNote: '供应商未开票',
    })
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    const headers = init.headers as Headers
    expect(headers.get('Idempotency-Key')).toBeTruthy()
    expect(String(init.body)).toContain('"amount":"40.00"')
  })

  it('驳回需要原因，缺失版本时后端 400 会映射为业务错误', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({
      title: '输入无效', status: 400, code: 'VALIDATION_FAILED', detail: '请求字段校验失败，请修改后重试。',
      requestId: 'req-1', fieldErrors: [{ field: 'version', message: '请携带当前版本号（version）后再提交' }],
    }), { status: 400, headers: { 'Content-Type': 'application/problem+json' } }))
    const error = await rejectExpense('7', '10', '1', 1, '金额与报价不符').catch((e: unknown) => e)
    expect((error as { code?: string }).code).toBe('VALIDATION_FAILED')
    expect((error as { fieldErrors?: { field: string }[] }).fieldErrors?.[0]?.field).toBe('version')
  })

  it('状态与分类标签覆盖全部取值（缺标签会在界面上暴露成英文码）', () => {
    expect(Object.keys(expenseStatusLabel).sort()).toEqual(
      ['APPROVED', 'DRAFT', 'PENDING', 'REJECTED', 'VOIDED'])
    expect(Object.keys(expenseCategoryLabel).sort()).toEqual(
      ['CATERING', 'MATERIAL', 'OTHER', 'PRINTING', 'TRANSPORT', 'VENUE'])
  })
})
