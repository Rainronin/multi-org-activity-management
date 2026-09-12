import { describe, expect, it } from 'vitest'
import { describeHealth, type HealthReport } from './health'

describe('describeHealth', () => {
  it('服务与数据库正常时给出正常说明', () => {
    const report: HealthReport = {
      status: 'UP',
      database: 'UP',
      checkedAt: '2026-09-12T00:00:00Z',
    }
    expect(describeHealth(report)).toContain('正常')
  })

  it('数据库异常时如实报告状态', () => {
    const report: HealthReport = {
      status: 'UP',
      database: 'DOWN',
      checkedAt: '2026-09-12T00:00:00Z',
    }
    expect(describeHealth(report)).toContain('DOWN')
  })
})
