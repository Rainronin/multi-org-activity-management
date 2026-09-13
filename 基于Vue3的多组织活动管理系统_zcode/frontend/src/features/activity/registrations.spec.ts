import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  exportRegistrationRoster,
  fetchActivityRegistrations,
  isCancellable,
  type RegistrationItem,
} from './registrations'

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
}

const fetchMock = vi.fn<typeof fetch>()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function item(overrides: Partial<RegistrationItem> = {}): RegistrationItem {
  return {
    registrationId: '1',
    activityId: '10',
    accountId: 1,
    accountUsername: 'u',
    displayName: 'u',
    note: null,
    status: 'PENDING',
    cancelReason: null,
    decidedAt: null,
    createdAt: '2027-01-01T00:00:00+08:00',
    activityName: '活动',
    organizationName: '组织',
    location: '礼堂',
    startTime: '2027-06-01T14:00:00+08:00',
    endTime: '2027-06-01T16:00:00+08:00',
    registrationOpen: true,
    adjusting: false,
    ...overrides,
  }
}

describe('isCancellable', () => {
  it('待审与已通过且活动未开始时可以取消', () => {
    const now = new Date('2027-05-01T00:00:00+08:00')
    expect(isCancellable(item({ status: 'PENDING' }), now)).toBe(true)
    expect(isCancellable(item({ status: 'APPROVED' }), now)).toBe(true)
  })

  it('活动开始后或已结束时不能取消', () => {
    expect(isCancellable(item(), new Date('2027-06-02T00:00:00+08:00'))).toBe(false)
    expect(isCancellable(item({ status: 'REJECTED' }), new Date('2027-05-01T00:00:00+08:00'))).toBe(false)
    expect(isCancellable(item({ status: 'CANCELLED_ORG' }), new Date('2027-05-01T00:00:00+08:00'))).toBe(false)
  })
})

describe('名单分页与导出请求', () => {
  it('分页参数与状态筛选都进入查询串，并保留服务端返回的 total', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ items: [item()], page: 2, pageSize: 20, total: 25 }))
    const page = await fetchActivityRegistrations('7', '10', 'PENDING', 2, 20)
    const url = String(fetchMock.mock.calls[0]?.[0])
    expect(url).toContain('/api/organizations/7/activities/10/registrations?')
    expect(url).toContain('page=2')
    expect(url).toContain('pageSize=20')
    expect(url).toContain('status=PENDING')
    expect(page.total).toBe(25)
    expect(page.items).toHaveLength(1)
  })

  it('导出走统一请求入口，超限时把业务错误抛给调用方', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({
      title: '导出记录过多', status: 409, code: 'EXPORT_TOO_LARGE',
      detail: '当前筛选共 6000 条，单次最多导出 5000 条，请缩小筛选范围后重试。',
      requestId: 'req-1', fieldErrors: [],
    }), { status: 409, headers: { 'Content-Type': 'application/problem+json' } }))
    const error = await exportRegistrationRoster('7', '10').catch((e: unknown) => e)
    expect((error as { code?: string }).code).toBe('EXPORT_TOO_LARGE')
  })
})
