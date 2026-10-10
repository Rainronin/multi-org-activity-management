import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  executionCategoryLabel,
  isArchivable,
  isExecutionRecordable,
  isFeedbackWindowOpen,
  startExecution,
  type ActivityLifecycleStatus,
} from './lifecycle'
import { averageLabel, type FeedbackSummary } from '../feedback/feedback'
import { request } from '../../shared/http/client'

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

const fetchMock = vi.fn<typeof fetch>()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const ALL_STATUSES: ActivityLifecycleStatus[] = [
  'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'EXECUTING', 'REVIEWING', 'ARCHIVED', 'CANCELLED',
]

describe('P6 生命周期界面口径', () => {
  it('执行记录只在执行中、复盘期与取消遗留处理期间可写', () => {
    const writable = ALL_STATUSES.filter(isExecutionRecordable)
    expect(writable).toEqual(['EXECUTING', 'REVIEWING', 'CANCELLED'])
  })

  it('归档只允许复盘中的正常活动与取消处理中的活动', () => {
    const archivable = ALL_STATUSES.filter(isArchivable)
    expect(archivable).toEqual(['REVIEWING', 'CANCELLED'])
  })

  it('反馈窗口只在首次复盘期开放，归档后不再恢复', () => {
    expect(isFeedbackWindowOpen('REVIEWING', false)).toBe(true)
    expect(isFeedbackWindowOpen('REVIEWING', true)).toBe(false)
    expect(isFeedbackWindowOpen('EXECUTING', false)).toBe(false)
    expect(isFeedbackWindowOpen('CANCELLED', false)).toBe(false)
    expect(isFeedbackWindowOpen('ARCHIVED', false)).toBe(false)
  })

  it('启动执行携带期望版本，缺失版本由后端拒绝', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ status: 'EXECUTING' }))
    await startExecution('7', '10', 3)
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/api/organizations/7/activities/10/start-execution')
    expect(String(init.body)).toContain('"version":3')
  })

  it('执行记录类型标签覆盖全部取值（缺标签会在界面上暴露英文码）', () => {
    expect(Object.keys(executionCategoryLabel).sort()).toEqual(
      ['ARRANGEMENT', 'DEVIATION', 'HANDLING', 'ISSUE', 'PROGRESS'])
  })
})

describe('P6 反馈汇总口径', () => {
  function summary(overrides: Partial<FeedbackSummary> = {}): FeedbackSummary {
    return {
      count: 0,
      averageRating: null,
      items: [],
      mineSubmitted: false,
      myRating: null,
      myComment: null,
      myVersion: null,
      canSubmit: false,
      submitBlockedReason: null,
      ...overrides,
    }
  }

  it('无反馈时显示“—”，有反馈时显示一位小数平均分', () => {
    expect(averageLabel(null)).toBe('—')
    expect(averageLabel(summary())).toBe('—')
    expect(averageLabel(summary({ count: 2, averageRating: '4.5' }))).toBe('4.5')
  })

  it('反馈接口走统一请求入口，关闭原因原样返回给界面', async () => {
    fetchMock.mockResolvedValue(jsonResponse(summary({
      count: 1, averageRating: '3.0', canSubmit: false,
      submitBlockedReason: '活动已归档，反馈不再接受新增或修改。',
    })))
    const response = await request<FeedbackSummary>('/api/organizations/7/activities/10/feedback')
    expect(response.data.canSubmit).toBe(false)
    expect(response.data.submitBlockedReason).toContain('已归档')
  })
})
