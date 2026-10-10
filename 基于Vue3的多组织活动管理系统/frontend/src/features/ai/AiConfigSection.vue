<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ElAlert,
  ElButton,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElMessageBox,
  ElSkeleton,
  ElSkeletonItem,
  ElOption,
  ElSelect,
  ElTag,
  type FormInstance,
  type FormRules,
} from 'element-plus'
import { ApiError, readErrorMessage } from '../../shared/http/error'
import { newIdempotencyKey } from '../../shared/http/idempotency'
import { useSessionStore } from '../../stores/session'
import {
  aiConfigStateLabel,
  aiOutputLimitLabel,
  aiProtocolLabel,
  clearAiConfigKey,
  fetchAiConfig,
  fetchAiConfigTestByRequestKey,
  saveAiConfig,
  setAiConfigEnabled,
  testAiConfig,
  type AiAuthScheme,
  type AiConfig,
  type AiOutputLimitField,
  type AiProtocol,
} from './ai'

// P8.1 组织设置 → AI 模型：只有组织负责人能保存、测试、启用与清除密钥；
// 其他成员只看到是否可用与模型显示信息（后端逐请求判定，界面不当作权限来源）。
const props = defineProps<{ orgId: string }>()

const session = useSessionStore()
const config = ref<AiConfig | null>(null)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const pendingTest = ref<{ orgId: string; version: number; key: string; generation: number } | null>(null)
const lastTestFailed = ref(false)
const mutating = ref(false)
const busy = computed(() => saving.value || testing.value || mutating.value)
const errorText = ref('')
const noticeText = ref('')
let configRequestSeq = 0
let loadedOrgId = ''

const protocol = ref<AiProtocol>('OPENAI_CHAT_COMPLETIONS')
const endpoint = ref('')
const model = ref('')
const authScheme = ref<AiAuthScheme>('BEARER')
const apiKey = ref('')
const maxOutputTokens = ref(1024)
const outputLimitField = ref<AiOutputLimitField>('MAX_TOKENS')
const timeoutSeconds = ref(20)
const savedForm = ref('')
const currentForm = computed(() => JSON.stringify([
  protocol.value, endpoint.value, model.value, authScheme.value,
  maxOutputTokens.value, outputLimitField.value, timeoutSeconds.value,
]))

const isOwner = computed(() => config.value?.viewerRole === 'OWNER')
const hasApiKey = computed(() => config.value?.hasApiKey ?? false)
const canClearKey = computed(() => isOwner.value && hasApiKey.value)
const hasUnsaved = computed(() => isOwner.value && (currentForm.value !== savedForm.value || Boolean(apiKey.value)))

async function confirmLeave(): Promise<boolean> {
  if (busy.value) return false
  if (!hasUnsaved.value && !pendingTest.value) return true
  try {
    await ElMessageBox.confirm(pendingTest.value ? '连接测试结果尚未确认。离开后请先核对配置测试状态，避免重复测试。确定离开？' : 'AI 配置还未保存，确定离开？', '未保存的修改', {
      confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning',
    })
    return true
  } catch { return false }
}
function beforeUnload(event: BeforeUnloadEvent): void {
  if (hasUnsaved.value || busy.value || pendingTest.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

/**
 * 基础字段失焦校验（UI 规范 12.1）：地址必须是完整 HTTPS 端点，模型 ID 必填；
 * 协议是离散选择，选完即校验。域名与安全校验仍由后端负责，前端不冒充权威。
 */
const formRef = ref<FormInstance>()
const configRoot = ref<HTMLElement>()
const advancedFields = ref<HTMLDetailsElement>()
const serverFieldError = ref<{ field: string; message: string } | null>(null)
const advancedNames = ['outputLimitField', 'maxOutputTokens', 'timeoutSeconds']
watch(currentForm, () => { serverFieldError.value = null })
async function revealInvalidField(field: string) {
  const targetOrgId = props.orgId
  if (advancedNames.includes(field) && advancedFields.value) advancedFields.value.open = true
  await nextTick()
  if (targetOrgId !== props.orgId) return
  const control = configRoot.value?.querySelector<HTMLElement>(`[data-ai-field="${CSS.escape(field)}"]`)
  const input = control?.matches('input') ? control : control?.querySelector<HTMLElement>('input')
  if (!input?.isConnected) return
  input.focus({ preventScroll: true })
  input.scrollIntoView({ block: 'center', behavior: 'instant' })
}
/** ElForm 校验需要具名模型；字段仍是各自独立的 ref（接口按字段提交）。 */
const formModel = computed(() => ({
  endpoint: endpoint.value,
  model: model.value,
  maxOutputTokens: maxOutputTokens.value,
  timeoutSeconds: timeoutSeconds.value,
}))
const formRules: FormRules = {
  maxOutputTokens: [{ type: 'integer', required: true, min: 64, max: 4096, message: '输出上限须为64～4096之间的整数', trigger: 'change' }],
  timeoutSeconds: [{ type: 'integer', required: true, min: 5, max: 60, message: '单次时限须为5～60之间的整数', trigger: 'change' }],
  endpoint: [
    { required: true, message: '请输入完整的 API 请求地址', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        const text = (value ?? '').trim()
        if (!text) {
          callback()
          return
        }
        let parsed: URL
        try {
          parsed = new URL(text)
        } catch {
          callback(new Error('请输入完整地址，例如 https://api.example.com/chat/completions'))
          return
        }
        if (parsed.protocol !== 'https:') {
          callback(new Error('请求地址必须使用 HTTPS'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
  model: [{ required: true, whitespace: true, message: '请填写模型 ID', trigger: 'blur' }],
}

/** 校验状态同步到 aria-invalid，屏幕阅读器不只靠文字位置判断字段是否出错。 */
function invalidBy(field: string): string {
  return formRef.value?.fields?.some((item) => item.prop === field && item.validateState === 'error') ? 'true' : 'false'
}

function applyConfig(value: AiConfig): void {
  serverFieldError.value = null
  config.value = value
  if (value.protocol) {
    protocol.value = value.protocol
  }
  endpoint.value = value.endpoint ?? ''
  model.value = value.model ?? ''
  authScheme.value = value.authScheme ?? (value.protocol === 'ANTHROPIC_MESSAGES' ? 'X_API_KEY' : 'BEARER')
  maxOutputTokens.value = value.maxOutputTokens ?? 1024
  outputLimitField.value = value.outputLimitField ?? 'MAX_TOKENS'
  timeoutSeconds.value = value.timeoutSeconds ?? 20
  apiKey.value = ''
  savedForm.value = currentForm.value
}

async function load(): Promise<void> {
  const targetOrgId = props.orgId
  const requestSeq = ++configRequestSeq
  if (targetOrgId !== loadedOrgId) {
    loadedOrgId = targetOrgId
    config.value = null
    endpoint.value = ''
    model.value = ''
    apiKey.value = ''
    noticeText.value = ''
    pendingTest.value = null
  }
  loading.value = true
  errorText.value = ''
  const gen = session.capture()
  try {
    const loaded = await fetchAiConfig(targetOrgId)
    if (requestSeq !== configRequestSeq || targetOrgId !== props.orgId || gen !== session.generation) {
      return
    }
    applyConfig(loaded)
  } catch (error) {
    if (requestSeq !== configRequestSeq || targetOrgId !== props.orgId || gen !== session.generation) {
      return
    }
    config.value = null
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = readErrorMessage(error, '模型配置加载失败，请重试。')
  } finally {
    if (requestSeq === configRequestSeq) {
      loading.value = false
    }
  }
}

function reportError(error: unknown, fallback: string, gen: number): void {
  if (session.observeUnauthorized(error, gen)) {
    return
  }
  if (error instanceof ApiError) {
    const field = error.fieldErrors[0]
    errorText.value = field ? `${field.message}` : error.detail
    serverFieldError.value = field ?? null
    if (field) void revealInvalidField(field.field)
    return
  }
  errorText.value = fallback
}

function requireSavedConfig(): boolean {
  if (!hasUnsaved.value) return true
  errorText.value = '请先保存配置，再执行此操作。'
  return false
}

/** 保存：密钥留空表示保留原密钥；协议或地址变化必须重新输入密钥。 */
async function save(): Promise<void> {
  if (busy.value) return
  saving.value = true
  const gen = session.capture()
  try {
    // 校验期间也固定输入，不能只相信已触发的 blur（UI 规范 7.4）。
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) {
      const invalid = formRef.value?.fields.find(field => field.validateState === 'error')
      saving.value = false
      if (invalid?.prop) void revealInvalidField(String(invalid.prop))
      return
    }
    serverFieldError.value = null
    errorText.value = ''
    noticeText.value = ''
    const protocolChanged = config.value?.protocol !== protocol.value
    const endpointChanged = (config.value?.endpoint ?? '') !== endpoint.value.trim()
    if (config.value?.hasApiKey && (protocolChanged || endpointChanged) && !apiKey.value.trim()) {
      errorText.value = '修改协议或请求地址必须重新输入密钥。'
      return
    }
    applyConfig(await saveAiConfig(props.orgId, {
      protocol: protocol.value,
      endpoint: endpoint.value.trim(),
      model: model.value.trim(),
      authScheme: protocol.value === 'OPENAI_CHAT_COMPLETIONS' ? 'BEARER' : authScheme.value,
      apiKey: apiKey.value.trim() ? apiKey.value.trim() : null,
      clearApiKey: false,
      maxOutputTokens: maxOutputTokens.value,
      outputLimitField: outputLimitField.value,
      timeoutSeconds: timeoutSeconds.value,
      version: config.value?.version ?? null,
    }))
    noticeText.value = config.value?.enabled
      ? '配置已保存并生效，可在活动方案与复盘中使用 AI；测试连接为可选操作。'
      : '配置已保存。请填写并保存密钥后使用 AI。'
  } catch (error) {
    saving.value = false
    reportError(error, '模型配置保存失败，请重试。', gen)
  } finally {
    saving.value = false
  }
}

async function test(): Promise<void> {
  if (busy.value) return
  if (!pendingTest.value && lastTestFailed.value) {
    try {
      await ElMessageBox.confirm('上次测试已失败。重新发起会创建一次新的模型请求，可能再次消耗额度。确定重新发起？', '重新发起连接测试', {
        confirmButtonText: '重新发起测试', cancelButtonText: '保留上次结果', type: 'warning',
      })
    } catch { return }
    lastTestFailed.value = false
  }
  if (!pendingTest.value && !requireSavedConfig()) return
  if (config.value?.version === null || config.value?.version === undefined) {
    errorText.value = '请先保存配置再测试连接。'
    return
  }
  const gen = session.capture()
  const checking = pendingTest.value !== null
  pendingTest.value ??= { orgId: props.orgId, version: config.value.version, key: newIdempotencyKey(), generation: gen }
  const pending = pendingTest.value
  const isCurrent = () => pending.orgId === props.orgId && pending.generation === session.generation
  testing.value = true
  errorText.value = ''
  noticeText.value = ''
  try {
    const result = checking
      ? await fetchAiConfigTestByRequestKey(pending.orgId, pending.key)
      : await testAiConfig(pending.orgId, pending.version, pending.key)
    if (!isCurrent()) return
    if (result.status === 'RUNNING' || result.status === 'UNKNOWN') {
      noticeText.value = result.status === 'RUNNING' ? '连接测试仍在处理中，请稍后核对结果。' : '上游测试结果尚未确认，请继续核对原请求；不会再次调用模型。'
      return
    }
    lastTestFailed.value = result.status === 'FAILED'
    pendingTest.value = null
    const revision = result.testedRevision === null ? '' : `已记录测试版本 r${result.testedRevision}，`
    noticeText.value = `${result.message}（${revision}测试会消耗少量额度）`
    const refreshed = await fetchAiConfig(pending.orgId)
    if (isCurrent()) applyConfig(refreshed)
  } catch (error) {
    if (isCurrent()) {
      if (!checking && error instanceof ApiError && !error.isTransportError && error.status < 500) pendingTest.value = null
      reportError(error, '连接测试失败，请核对地址、模型 ID 与密钥。', gen)
      if (pendingTest.value) errorText.value = '连接测试结果尚未确认，请使用原请求核对结果；当前配置暂不可修改。'
    }
  } finally {
    testing.value = false
  }
}

async function toggleEnabled(enabled: boolean): Promise<void> {
  if (busy.value) return
  if (!requireSavedConfig()) return
  if (config.value?.version === null || config.value?.version === undefined) {
    return
  }
  const gen = session.capture()
  mutating.value = true
  errorText.value = ''
  noticeText.value = ''
  try {
    applyConfig(await setAiConfigEnabled(props.orgId, config.value.version, enabled))
    noticeText.value = enabled ? '模型已启用，可在活动方案与复盘中生成草稿。' : '模型已停用，方案与总结仍可手工编写。'
  } catch (error) {
    await load()
    if (gen === session.generation) {
      reportError(error, enabled ? '启用失败，请核对已保存配置与密钥。' : '停用失败，请重试。', gen)
    }
  } finally {
    mutating.value = false
  }
}

async function clearKey(): Promise<void> {
  if (busy.value) return
  if (!requireSavedConfig()) return
  if (config.value?.version === null || config.value?.version === undefined) {
    return
  }
  const gen = session.capture()
  mutating.value = true
  errorText.value = ''
  noticeText.value = ''
  try {
    applyConfig(await clearAiConfigKey(props.orgId, config.value.version))
    ElMessage.success('密钥已清除，配置同时停用')
  } catch (error) {
    reportError(error, '清除密钥失败，请重试。', gen)
  } finally {
    mutating.value = false
  }
}

watch(() => props.orgId, () => { void load() }, { immediate: true })
defineExpose({ reload: load, confirmLeave })
</script>

<template>
  <section
    ref="configRoot"
    class="ai-config"
    data-ai-config-panel
  >
    <div class="ai-config__head">
      <h2>AI 模型</h2>
      <ElTag
        v-if="config"
        size="small"
        :type="config.available ? 'success' : 'info'"
      >
        {{ aiConfigStateLabel[config.configState] }}
      </ElTag>
    </div>
    <p class="ai-config__intro">
      活动总结与方案生成共用本组织的一套模型配置，保存后立即生效，无需另行测试或启用。
      密钥加密保存，读取不回显；未配置、停用或调用失败时，仍可手工编写。
    </p>

    <!-- 加载占位与真实表单同构：按字段给出标签与控件两行，不整段灰条（UI 规范第 10 节）。 -->
    <ElSkeleton
      v-if="loading"
      class="ai-config__skeleton"
      animated
      data-ai-config-skeleton
      aria-busy="true"
      aria-label="正在加载 AI 模型配置"
    >
      <template #template>
        <div
          v-for="field in 6"
          :key="field"
          class="ai-config__skeleton-field"
        >
          <ElSkeletonItem variant="text" />
          <ElSkeletonItem variant="rect" />
        </div>
      </template>
    </ElSkeleton>
    <p
      v-else-if="errorText && !config"
      class="ai-config__error"
      role="alert"
    >
      {{ errorText }}
      <ElButton
        size="small"
        data-ai-action="reload"
        @click="load"
      >
        重新加载
      </ElButton>
    </p>

    <template v-if="!loading && config">
      <ElAlert
        v-if="noticeText"
        class="ai-config__notice"
        :type="pendingTest ? 'warning' : lastTestFailed ? 'error' : 'success'"
        :closable="false"
        :title="noticeText"
      />

      <p
        v-if="errorText"
        class="ai-config__error"
        role="alert"
      >
        {{ errorText }}
      </p>

      <p
        v-if="!isOwner"
        class="ai-config__note"
        data-ai-role="member"
      >
        当前身份为组织成员：只能查看模型是否可用（{{ config.availabilityHint }}），
        配置、测试、启用与密钥管理仅组织负责人可操作。
      </p>

      <p
        v-if="pendingTest && isOwner"
        class="ai-config__note"
        role="status"
      >
        原测试结果待核对；核对不会再次调用模型。
        <ElButton
          :loading="testing"
          :disabled="busy"
          data-ai-action="check-test"
          @click="test"
        >
          核对测试结果
        </ElButton>
      </p>

      <ElForm
        v-if="isOwner"
        ref="formRef"
        :model="formModel"
        :rules="formRules"
        :disabled="busy || Boolean(pendingTest)"
        label-position="top"
        class="ai-config__form"
      >
        <ElFormItem label="兼容协议">
          <ElSelect
            v-model="protocol"
            class="ai-config__control"
            data-ai-field="protocol"
          >
            <ElOption
              v-for="(label, value) in aiProtocolLabel"
              :key="value"
              :label="label"
              :value="value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem
          label="完整 API 请求地址"
          prop="endpoint"
        >
          <ElInput
            v-model="endpoint"
            class="ai-config__control"
            data-ai-field="endpoint"
            :aria-invalid="invalidBy('endpoint')"
            placeholder="例如 https://api.deepseek.com/chat/completions"
          />
          <p class="ai-config__hint">
            填写完整端点，不自动拼接路径；只允许部署允许的 HTTPS 公网域名，禁止把密钥写进地址。
          </p>
        </ElFormItem>
        <ElFormItem
          label="模型 ID"
          prop="model"
        >
          <ElInput
            v-model="model"
            class="ai-config__control"
            data-ai-field="model"
            :aria-invalid="invalidBy('model')"
            placeholder="例如 deepseek-flash，按账号当前可用值填写"
          />
        </ElFormItem>

        <!-- 字段分组：调用配置与连接参数分开，长说明不挤占主要操作（审核 N04）。 -->
        <h3 class="ai-config__group">
          认证信息
        </h3>
        <ElFormItem
          v-if="protocol === 'ANTHROPIC_MESSAGES'"
          label="认证方式"
        >
          <ElSelect
            v-model="authScheme"
            class="ai-config__control"
            data-ai-field="authScheme"
          >
            <ElOption
              label="x-api-key（Anthropic 默认）"
              value="X_API_KEY"
            />
            <ElOption
              label="Bearer（兼容网关要求时选择）"
              value="BEARER"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="hasApiKey ? 'API Key（已配置，留空表示保留）' : 'API Key'">
          <ElInput
            v-model="apiKey"
            class="ai-config__control"
            data-ai-field="apiKey"
            type="password"
            show-password
            autocomplete="off"
            :placeholder="hasApiKey ? '已配置：留空保留，输入新值表示替换' : '粘贴模型服务密钥'"
          />
          <p class="ai-config__hint">
            地址或协议变化必须重新输入密钥，旧密钥不会被静默发送到新地址。
          </p>
        </ElFormItem>
        <details
          ref="advancedFields"
          class="ai-config__advanced"
        >
          <summary>高级调用参数</summary>
          <ElFormItem
            label="输出上限字段"
            prop="outputLimitField"
            :error="serverFieldError?.field === 'outputLimitField' ? serverFieldError.message : undefined"
          >
            <ElSelect
              v-model="outputLimitField"
              class="ai-config__control"
              data-ai-field="outputLimitField"
            >
              <ElOption
                v-for="(label, value) in aiOutputLimitLabel"
                :key="value"
                :label="label"
                :value="value"
              />
            </ElSelect>
            <p class="ai-config__hint">
              由本次真实测试确认目标模型接受的字段，不在失败后自动换参数重发。
            </p>
          </ElFormItem>
          <ElFormItem
            label="输出上限（tokens）"
            prop="maxOutputTokens"
            :error="serverFieldError?.field === 'maxOutputTokens' ? serverFieldError.message : undefined"
          >
            <ElInputNumber
              v-model="maxOutputTokens"
              :min="64"
              :max="4096"
              :step="64"
              data-ai-field="maxOutputTokens"
            />
          </ElFormItem>
          <ElFormItem
            label="单次时限（秒）"
            prop="timeoutSeconds"
            :error="serverFieldError?.field === 'timeoutSeconds' ? serverFieldError.message : undefined"
          >
            <ElInputNumber
              v-model="timeoutSeconds"
              :min="5"
              :max="60"
              :step="5"
              data-ai-field="timeoutSeconds"
            />
          </ElFormItem>
        </details>
        <div class="ai-config__actions">
          <ElButton
            type="primary"
            :loading="saving"
            data-ai-action="save"
            @click="save"
          >
            保存并生效
          </ElButton>
          <ElButton
            :loading="testing"
            :disabled="busy || !hasApiKey"
            data-ai-action="test"
            @click="test"
          >
            测试连接
          </ElButton>
          <ElButton
            v-if="!config.enabled"
            type="success"
            plain
            :loading="mutating"
            :disabled="busy || !hasApiKey"
            data-ai-action="enable"
            @click="toggleEnabled(true)"
          >
            恢复启用
          </ElButton>
          <ElButton
            v-else
            plain
            :loading="mutating"
            data-ai-action="disable"
            @click="toggleEnabled(false)"
          >
            停用
          </ElButton>
        </div>
        <details class="ai-config__advanced">
          <summary>密钥管理</summary>
          <p class="ai-config__hint">
            清除后配置将同时停用，再次使用需要重新填写密钥。
          </p>
          <ElButton
            v-if="canClearKey"
            type="danger"
            plain
            :loading="mutating"
            data-ai-action="clear-key"
            @click="clearKey"
          >
            清除密钥
          </ElButton>
        </details>

        <dl class="ai-config__state">
          <div>
            <dt>当前版本</dt>
            <dd>{{ config.version ?? '未配置' }}</dd>
          </div>
          <div>
            <dt>配置修订</dt>
            <dd>{{ config.connectionRevision === null ? '未配置' : `r${config.connectionRevision}` }}</dd>
          </div>
          <div>
            <dt>测试版本</dt>
            <dd>{{ config.testedRevision === null ? '尚未通过测试' : `r${config.testedRevision}` }}</dd>
          </div>
          <div>
            <dt>密钥</dt>
            <dd>{{ hasApiKey ? '已配置（不回显）' : '未配置' }}</dd>
          </div>
          <div>
            <dt>可用状态</dt>
            <dd>{{ config.availabilityHint }}</dd>
          </div>
        </dl>
        <p class="ai-config__hint">
          测试只发送固定短文本，不发送组织资料，但可能消耗少量额度；测试通过只证明当次基础文本调用可用。
          保存不会调用模型或消耗额度；测试连接仅用于诊断，修改参数后可按需重新测试。
        </p>
      </ElForm>
    </template>
  </section>
</template>

<style scoped>
.ai-config__advanced { margin-bottom: var(--ui-space-5); padding: var(--ui-space-4); border: var(--ui-border-width) solid var(--ui-color-border); border-radius: var(--ui-radius-control); }
.ai-config__advanced[open] > summary { margin-bottom: var(--ui-space-4); }
/* 白色内容面板：与标准页的卡片结构一致，字段不再直接排在页面底色上（审核 N04）。 */
.ai-config {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  min-width: 0;
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
  box-shadow: var(--ui-shadow-glass);
}

.ai-config__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ui-space-3);
}

.ai-config__head h2 {
  margin: 0;
  font-size: var(--ui-font-size-section);
  font-weight: var(--ui-font-weight-semibold);
}

.ai-config__intro,
.ai-config__hint,
.ai-config__note {
  margin: 0;
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-body);
}

/* 字段分组标题：把调用配置与连接参数分开，长说明不挤占主要操作。 */
.ai-config__group {
  margin: var(--ui-space-2) 0 0;
  padding-top: var(--ui-space-4);
  border-top: var(--ui-border-width) solid var(--ui-color-border);
  font-size: var(--ui-font-size-emphasis);
  font-weight: var(--ui-font-weight-semibold);
}

.ai-config__error {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ui-space-3);
  margin: 0;
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.ai-config__skeleton {
  max-width: var(--ui-form-max-width);
}

.ai-config__skeleton-field {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
  margin-bottom: var(--ui-form-field-gap);
}

.ai-config__skeleton-field .el-skeleton__item:first-child {
  width: var(--ui-layout-card-min-width);
}

.ai-config__skeleton-field .el-skeleton__item:last-child {
  height: var(--ui-control-height);
}

.ai-config__form {
  max-width: var(--ui-form-max-width);
}

.ai-config__control {
  width: 100%;
  max-width: 100%;
}

.ai-config__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-2);
  margin-bottom: var(--ui-space-4);
}

.ai-config__state {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(var(--ui-layout-card-min-width), 1fr));
  gap: var(--ui-space-3);
  margin: 0;
}

.ai-config__state dt {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}

.ai-config__state dd {
  margin: 0;
  font-weight: var(--ui-font-weight-medium);
}

/* 说明文字按面板宽度排布，不被窄控件挤压成多行（审核 N04）。 */
.ai-config__hint {
  max-width: none;
}
</style>
