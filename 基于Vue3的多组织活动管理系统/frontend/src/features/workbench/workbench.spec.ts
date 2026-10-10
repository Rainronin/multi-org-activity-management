import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchWorkbench, todoKindLabel, workbenchRoleLabel, type Workbench } from './workbench'
import {
  fetchNotifications, markNotificationRead, notificationTypeLabel, type NotificationItem,
} from '../notification/notifications'

function item(overrides: Partial<NotificationItem> = {}): NotificationItem {
  return {
    notificationId: '1',
    type: 'TASK_ASSIGNED',
    payload: '新任务「布置场地」：测试活动',
    createdAt: '2027-09-01T10:00:00+08:00',
    readAt: null,
    activityId: '10',
    activityName: '测试活动',
    activityPath: '/org/1/activities/10',
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

describe('P7 工作台', () => {
  it('请求带上待办条数上限，卡片与待办原样返回给界面', async () => {
    const workbench: Workbench = {
      role: 'OWNER',
      cards: [{ key: 'activities-in-progress', label: '进行中活动', value: '2', hint: '筹备中 1 个、执行中 1 个', detailPath: '/org/7/activities', detailLabel: '活动管理' }],
      todos: [{ kind: 'REGISTRATION_PENDING', title: '待审报名：张三', detail: '活动「测试活动」提交', path: '/org/7/activities/10' }],
      myCards: [{ key: 'my-tasks-open', label: '我的待处理任务', value: '1', hint: '只含本人任务', detailPath: '/org/7/my-tasks', detailLabel: '我的任务' }],
    }
    fetchMock.mockResolvedValue(new Response(JSON.stringify(workbench), {
      headers: { 'Content-Type': 'application/json' },
    }))
    const result = await fetchWorkbench('7', 8)
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/api/organizations/7/workbench?todoLimit=8')
    expect(result.role).toBe('OWNER')
    expect(result.cards[0]?.value).toBe('2')
    expect(result.todos[0]?.path).toBe('/org/7/activities/10')
  })

  it('身份与待办类型标签覆盖全部取值（缺标签会在界面上暴露英文码）', () => {
    expect(Object.keys(workbenchRoleLabel).sort()).toEqual(['ACTIVITY_OWNER', 'MEMBER', 'OWNER'])
    expect(todoKindLabel.REGISTRATION_PENDING).toBeTruthy()
    expect(todoKindLabel.TASK_OVERDUE).toBeTruthy()
    expect(todoKindLabel.MY_TASK_OVERDUE).toBeTruthy()
    expect(todoKindLabel.EXPENSE_PENDING).toBeTruthy()
    expect(todoKindLabel.ACTIVITY_REVIEW).toBeTruthy()
  })
})

describe('P7 站内提示', () => {
  it('列表保留总数与未读数，条目含按当前权限生成的活动地址', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({
      items: [item()], page: 1, pageSize: 20, total: 1, unread: 1,
    }), { headers: { 'Content-Type': 'application/json' } }))
    const page = await fetchNotifications(1, 20)
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/api/me/notifications?page=1&pageSize=20')
    expect(page.unread).toBe(1)
    expect(page.items[0]?.activityName).toBe('测试活动')
    expect(page.items[0]?.activityPath).toBe('/org/1/activities/10')
  })

  it('标记已读走本人端点，服务端返回空响应体也能正常结束', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    await markNotificationRead('9')
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/api/me/notifications/9/read')
    expect(init.method).toBe('POST')
  })

  it('提示类型标签覆盖全部来源（缺标签会在界面上暴露英文码）', () => {
    for (const type of ['TASK_ASSIGNED', 'REGISTRATION_APPROVED', 'REGISTRATION_REJECTED',
      'ACTIVITY_RECONFIRMED', 'ACTIVITY_CANCELLED', 'EXPENSE_SUBMITTED', 'EXPENSE_APPROVED',
      'EXPENSE_REJECTED', 'REGISTRATION_CANCELLED']) {
      expect(notificationTypeLabel[type]).toBeTruthy()
    }
  })
})
