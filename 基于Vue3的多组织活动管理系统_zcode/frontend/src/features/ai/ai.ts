import { request } from '../../shared/http/client'
import { newIdempotencyKey } from '../../shared/http/idempotency'

/**
 * P8 AI 文本辅助（M11 活动总结、M12 方案生成）：
 * 组织模型配置与生成记录。密钥只在保存时提交一次，读取只返回是否已配置。
 */

export type AiProtocol = 'OPENAI_CHAT_COMPLETIONS' | 'ANTHROPIC_MESSAGES'
export type AiAuthScheme = 'BEARER' | 'X_API_KEY'
export type AiOutputLimitField = 'MAX_TOKENS' | 'MAX_COMPLETION_TOKENS'
export type AiConfigState = 'NOT_CONFIGURED' | 'NEEDS_KEY' | 'NEEDS_TEST' | 'ENABLED' | 'DISABLED'
export type AiGenerationType = 'ACTIVITY_SUMMARY' | 'ACTIVITY_PLAN'
export type AiGenerationStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface AiConfig {
  viewerRole: 'OWNER' | 'MEMBER'
  available: boolean
  availabilityHint: string
  configState: AiConfigState
  protocol: AiProtocol | null
  model: string | null
  endpoint: string | null
  authScheme: AiAuthScheme | null
  maxOutputTokens: number | null
  outputLimitField: AiOutputLimitField | null
  timeoutSeconds: number | null
  hasApiKey: boolean
  enabled: boolean
  version: number | null
  connectionRevision: number | null
  testedRevision: number | null
  testedForCurrentRevision: boolean
  testedAt: string | null
  testedBy: string | null
}

export interface SaveAiConfigPayload {
  protocol: AiProtocol
  endpoint: string
  model: string
  authScheme: AiAuthScheme | null
  apiKey: string | null
  clearApiKey: boolean
  maxOutputTokens: number
  outputLimitField: AiOutputLimitField
  timeoutSeconds: number
  version: number | null
}

export interface AiConnectionTestResult {
  status: string
  message: string
  testedRevision: number | null
  requestId: string | null
  testedAt: string
}

export interface AiInputPreview {
  available: boolean
  availabilityHint: string
  sourceHash: string
  scope: string[]
  missing: string[]
}

export interface AiPlanConditions {
  topic?: string
  goal?: string
  audience?: string
  expectedParticipants?: number | null
  budget?: string
  timePlace?: string
  constraints?: string
}

export interface AiGeneration {
  generationId: string
  type: AiGenerationType
  status: AiGenerationStatus
  activityId: string | null
  output: string | null
  errorCode: string | null
  errorMessage: string | null
  protocol: AiProtocol | null
  model: string | null
  endpointHost: string | null
  scope: string[]
  missing: string[]
  sourceChanged: boolean
  createdAt: string
  adoptedAt: string | null
  adoptedBy: string | null
  requestId: string | null
}

export const aiProtocolLabel: Record<AiProtocol, string> = {
  OPENAI_CHAT_COMPLETIONS: 'OpenAI Chat Completions 兼容',
  ANTHROPIC_MESSAGES: 'Anthropic Messages 兼容',
}

export const aiOutputLimitLabel: Record<AiOutputLimitField, string> = {
  MAX_TOKENS: 'max_tokens（多数兼容服务）',
  MAX_COMPLETION_TOKENS: 'max_completion_tokens（部分新模型）',
}

export const aiConfigStateLabel: Record<AiConfigState, string> = {
  NOT_CONFIGURED: '尚未配置',
  NEEDS_KEY: '缺少密钥',
  NEEDS_TEST: '待测试',
  ENABLED: '已启用',
  DISABLED: '已停用',
}

/** 生成状态文案：界面只展示后端给出的状态与错误说明，不把失败当成功。 */
export function generationStatusText(generation: AiGeneration): string {
  if (generation.status === 'RUNNING') {
    return '生成中，请稍候……'
  }
  if (generation.status === 'FAILED') {
    return generation.errorMessage ?? '本次生成失败，可继续手工编写'
  }
  return generation.adoptedAt ? `已采用（${generation.adoptedAt}）` : '生成成功，尚未采用'
}

/** 草稿能否采用：只有生成成功且有正文的记录可以记录采用状态。 */
export function canAdoptGeneration(generation: AiGeneration | null): boolean {
  return Boolean(generation && generation.status === 'SUCCEEDED' && generation.output && !generation.adoptedAt)
}

/** 方案条件转成提交体：空字段不下发，缺失项由模型标注假设或待补充。 */
export function toPlanConditions(input: AiPlanConditions): AiPlanConditions {
  const trimmed = (value?: string) => {
    const text = (value ?? '').trim()
    return text === '' ? undefined : text
  }
  return {
    topic: trimmed(input.topic),
    goal: trimmed(input.goal),
    audience: trimmed(input.audience),
    expectedParticipants: input.expectedParticipants ?? undefined,
    budget: trimmed(input.budget),
    timePlace: trimmed(input.timePlace),
    constraints: trimmed(input.constraints),
  }
}

/** 至少填写主题或目标，与后端校验一致。 */
export function planConditionsReady(input: AiPlanConditions): boolean {
  const conditions = toPlanConditions(input)
  return Boolean(conditions.topic || conditions.goal)
}

export async function fetchAiConfig(orgId: string): Promise<AiConfig> {
  const response = await request<AiConfig>(`/api/organizations/${encodeURIComponent(orgId)}/ai/config`)
  return response.data
}

export async function saveAiConfig(orgId: string, payload: SaveAiConfigPayload): Promise<AiConfig> {
  const response = await request<AiConfig>(`/api/organizations/${encodeURIComponent(orgId)}/ai/config`, {
    method: 'PUT',
    json: payload,
  })
  return response.data
}

export async function testAiConfig(orgId: string, version: number, requestKey = newIdempotencyKey()): Promise<AiConnectionTestResult> {
  const response = await request<AiConnectionTestResult>(
    `/api/organizations/${encodeURIComponent(orgId)}/ai/config/test`,
    { method: 'POST', json: { version }, headers: { 'Idempotency-Key': requestKey } },
  )
  return response.data
}

export async function fetchAiConfigTestByRequestKey(orgId: string, requestKey: string): Promise<AiConnectionTestResult> {
  const query = new URLSearchParams({ requestKey })
  const response = await request<AiConnectionTestResult>(
    `/api/organizations/${encodeURIComponent(orgId)}/ai/config/test/by-request-key?${query}`,
  )
  return response.data
}

export async function setAiConfigEnabled(orgId: string, version: number, enabled: boolean): Promise<AiConfig> {
  const response = await request<AiConfig>(
    `/api/organizations/${encodeURIComponent(orgId)}/ai/config/${enabled ? 'enable' : 'disable'}`,
    { method: 'POST', json: { version } },
  )
  return response.data
}

export async function clearAiConfigKey(orgId: string, version: number): Promise<AiConfig> {
  const response = await request<AiConfig>(
    `/api/organizations/${encodeURIComponent(orgId)}/ai/config/clear-key`,
    { method: 'POST', json: { version } },
  )
  return response.data
}

export async function fetchAiInputPreview(
  orgId: string,
  activityId: string,
  type: AiGenerationType,
): Promise<AiInputPreview> {
  const response = await request<AiInputPreview>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/ai/input?type=${type}`,
  )
  return response.data
}

/** 生成：一次用户意图使用同一个防重键；重放同键同内容不会重复调用模型。 */
export async function generateAiDraft(
  orgId: string,
  activityId: string,
  type: AiGenerationType,
  conditions?: AiPlanConditions,
  requestKey?: string,
): Promise<AiGeneration> {
  const response = await request<AiGeneration>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/ai/generations`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': requestKey ?? newIdempotencyKey() },
      json: type === 'ACTIVITY_PLAN' ? { type, conditions: toPlanConditions(conditions ?? {}) } : { type },
    },
  )
  return response.data
}

/** 原请求结果未知时按键只读核对，不重新调用模型。 */
export async function getAiGenerationByRequestKey(
  orgId: string,
  activityId: string,
  requestKey: string,
): Promise<AiGeneration> {
  const response = await request<AiGeneration>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/ai/generations/by-request-key?key=${encodeURIComponent(requestKey)}`,
  )
  return response.data
}

export async function listAiGenerations(
  orgId: string,
  activityId: string,
  type: AiGenerationType,
): Promise<AiGeneration[]> {
  const response = await request<{ items: AiGeneration[] }>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/ai/generations?type=${type}`,
  )
  return response.data.items
}

export async function adoptAiGeneration(
  orgId: string,
  activityId: string,
  generationId: string,
): Promise<AiGeneration> {
  const response = await request<AiGeneration>(
    `/api/organizations/${encodeURIComponent(orgId)}/activities/${encodeURIComponent(activityId)}/ai/generations/${encodeURIComponent(generationId)}/adopt`,
    { method: 'POST' },
  )
  return response.data
}
