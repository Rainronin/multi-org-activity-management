import { describe, expect, it } from 'vitest'
import {
  aiConfigStateLabel,
  canAdoptGeneration,
  generationStatusText,
  planConditionsReady,
  toPlanConditions,
  type AiGeneration,
} from './ai'

/** P8 前端纯逻辑：草稿状态文案、采用条件与方案条件整理（界面对失败不显示为成功）。 */
function generation(overrides: Partial<AiGeneration> = {}): AiGeneration {
  return {
    generationId: '1',
    type: 'ACTIVITY_SUMMARY',
    status: 'SUCCEEDED',
    activityId: '9',
    output: '草稿正文',
    errorCode: null,
    errorMessage: null,
    protocol: 'OPENAI_CHAT_COMPLETIONS',
    model: 'stub-model',
    endpointHost: 'api.example.com:443',
    scope: [],
    missing: [],
    sourceChanged: false,
    createdAt: '2026-09-13T10:00:00Z',
    adoptedAt: null,
    adoptedBy: null,
    requestId: 'req-1',
    ...overrides,
  }
}

describe('ai draft helpers', () => {
  it('描述生成状态时不把失败当成成功', () => {
    expect(generationStatusText(generation({ status: 'RUNNING', output: null }))).toContain('生成中')
    expect(generationStatusText(generation({
      status: 'FAILED',
      output: null,
      errorCode: 'AI_TIMEOUT',
      errorMessage: '模型调用超时，本次未生成草稿，可继续手工编写或稍后重试',
    }))).toContain('超时')
    expect(generationStatusText(generation())).toContain('尚未采用')
    expect(generationStatusText(generation({ adoptedAt: '2026-09-13T11:00:00Z' }))).toContain('已采用')
  })

  it('只有生成成功且未采用的草稿可以记录采用', () => {
    expect(canAdoptGeneration(generation())).toBe(true)
    expect(canAdoptGeneration(generation({ status: 'FAILED', output: null }))).toBe(false)
    expect(canAdoptGeneration(generation({ output: null }))).toBe(false)
    expect(canAdoptGeneration(generation({ adoptedAt: '2026-09-13T11:00:00Z' }))).toBe(false)
    expect(canAdoptGeneration(null)).toBe(false)
  })

  it('方案条件只提交填写过的字段，并要求主题或目标', () => {
    expect(planConditionsReady({ topic: '   ', goal: '' })).toBe(false)
    expect(planConditionsReady({ topic: '迎新晚会' })).toBe(true)
    expect(planConditionsReady({ goal: '帮助新成员熟悉组织' })).toBe(true)
    const conditions = toPlanConditions({
      topic: ' 迎新晚会 ',
      goal: '',
      audience: undefined,
      expectedParticipants: 60,
      budget: '  ',
      timePlace: '礼堂',
      constraints: '',
    })
    expect(conditions).toEqual({
      topic: '迎新晚会',
      goal: undefined,
      audience: undefined,
      expectedParticipants: 60,
      budget: undefined,
      timePlace: '礼堂',
      constraints: undefined,
    })
  })

  it('配置状态有中文文案，未知状态不显示为可用', () => {
    expect(aiConfigStateLabel.ENABLED).toBe('已启用')
    expect(aiConfigStateLabel.NEEDS_TEST).toBe('待测试')
    expect(aiConfigStateLabel.NOT_CONFIGURED).toBe('尚未配置')
  })
})
