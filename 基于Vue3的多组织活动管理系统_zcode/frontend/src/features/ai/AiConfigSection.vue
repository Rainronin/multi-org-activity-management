<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ElAlert,
  ElButton,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElOption,
  ElSelect,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { useSessionStore } from '../../stores/session'
import {
  aiConfigStateLabel,
  aiOutputLimitLabel,
  aiProtocolLabel,
  clearAiConfigKey,
  fetchAiConfig,
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
const errorText = ref('')
const noticeText = ref('')

const protocol = ref<AiProtocol>('OPENAI_CHAT_COMPLETIONS')
const endpoint = ref('')
const model = ref('')
const authScheme = ref<AiAuthScheme>('BEARER')
const apiKey = ref('')
const maxOutputTokens = ref(1024)
const outputLimitField = ref<AiOutputLimitField>('MAX_TOKENS')
const timeoutSeconds = ref(20)

const isOwner = computed(() => config.value?.viewerRole === 'OWNER')
const hasApiKey = computed(() => config.value?.hasApiKey ?? false)
const canClearKey = computed(() => isOwner.value && hasApiKey.value)

function applyConfig(value: AiConfig): void {
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
}

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  const gen = session.capture()
  try {
    const loaded = await fetchAiConfig(props.orgId)
    if (gen !== session.generation) {
      return
    }
    applyConfig(loaded)
  } catch (error) {
    config.value = null
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '模型配置加载失败，请重试。'
  } finally {
    loading.value = false
  }
}

function reportError(error: unknown, fallback: string, gen: number): void {
  if (session.observeUnauthorized(error, gen)) {
    return
  }
  if (error instanceof ApiError) {
    const field = error.fieldErrors[0]
    errorText.value = field ? `${field.message}` : error.detail
    return
  }
  errorText.value = fallback
}

/** 保存：密钥留空表示保留原密钥；协议或地址变化必须重新输入密钥。 */
async function save(): Promise<void> {
  const gen = session.capture()
  saving.value = true
  errorText.value = ''
  noticeText.value = ''
  try {
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
    noticeText.value = '配置已保存。保存本身不调用模型；修改后需要重新测试才能启用。'
  } catch (error) {
    reportError(error, '模型配置保存失败，请重试。', gen)
  } finally {
    saving.value = false
  }
}

async function test(): Promise<void> {
  if (config.value?.version === null || config.value?.version === undefined) {
    errorText.value = '请先保存配置再测试连接。'
    return
  }
  const gen = session.capture()
  testing.value = true
  errorText.value = ''
  noticeText.value = ''
  try {
    const result = await testAiConfig(props.orgId, config.value.version)
    noticeText.value = `${result.message}（已记录测试版本 r${result.testedRevision}，测试会消耗少量额度）`
    applyConfig(await fetchAiConfig(props.orgId))
  } catch (error) {
    reportError(error, '连接测试失败，请核对地址、模型 ID 与密钥。', gen)
    await load()
  } finally {
    testing.value = false
  }
}

async function toggleEnabled(enabled: boolean): Promise<void> {
  if (config.value?.version === null || config.value?.version === undefined) {
    return
  }
  const gen = session.capture()
  errorText.value = ''
  noticeText.value = ''
  try {
    applyConfig(await setAiConfigEnabled(props.orgId, config.value.version, enabled))
    noticeText.value = enabled ? '模型已启用，可在活动方案与复盘中生成草稿。' : '模型已停用，方案与总结仍可手工编写。'
  } catch (error) {
    reportError(error, enabled ? '启用失败，请先对当前版本完成连接测试。' : '停用失败，请重试。', gen)
    await load()
  }
}

async function clearKey(): Promise<void> {
  if (config.value?.version === null || config.value?.version === undefined) {
    return
  }
  const gen = session.capture()
  errorText.value = ''
  noticeText.value = ''
  try {
    applyConfig(await clearAiConfigKey(props.orgId, config.value.version))
    ElMessage.success('密钥已清除，配置同时停用')
  } catch (error) {
    reportError(error, '清除密钥失败，请重试。', gen)
  }
}

onMounted(load)
defineExpose({ reload: load })
</script>

<template>
  <section class="ai-config">
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
      活动总结与方案生成共用本组织的一套模型配置。密钥只提交到本系统后端并加密保存，读取不回显；
      未配置、停用或调用失败时，方案与总结仍可手工编写。
    </p>

    <p
      v-if="loading"
      class="ai-config__note"
      aria-live="polite"
    >
      正在加载模型配置……
    </p>
    <p
      v-else-if="errorText"
      class="ai-config__error"
      role="alert"
    >
      {{ errorText }}
    </p>

    <template v-if="!loading && config">
      <ElAlert
        v-if="noticeText"
        class="ai-config__notice"
        type="success"
        :closable="false"
        :title="noticeText"
      />

      <p
        v-if="!isOwner"
        class="ai-config__note"
        data-ai-role="member"
      >
        当前身份为组织成员：只能查看模型是否可用（{{ config.availabilityHint }}），
        配置、测试、启用与密钥管理仅组织负责人可操作。
      </p>

      <ElForm
        v-else
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
        <ElFormItem label="完整 API 请求地址">
          <ElInput
            v-model="endpoint"
            class="ai-config__control"
            data-ai-field="endpoint"
            placeholder="例如 https://api.deepseek.com/chat/completions"
          />
          <p class="ai-config__hint">
            填写完整端点，不自动拼接路径；只允许部署允许的 HTTPS 公网域名，禁止把密钥写进地址。
          </p>
        </ElFormItem>
        <ElFormItem label="模型 ID">
          <ElInput
            v-model="model"
            class="ai-config__control"
            data-ai-field="model"
            placeholder="例如 deepseek-chat，按账号当前可用值填写"
          />
        </ElFormItem>
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
        <ElFormItem label="输出上限字段">
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
        <ElFormItem label="输出上限（tokens）">
          <ElInputNumber
            v-model="maxOutputTokens"
            :min="64"
            :max="4096"
            :step="64"
            data-ai-field="maxOutputTokens"
          />
        </ElFormItem>
        <ElFormItem label="单次时限（秒）">
          <ElInputNumber
            v-model="timeoutSeconds"
            :min="5"
            :max="60"
            :step="5"
            data-ai-field="timeoutSeconds"
          />
        </ElFormItem>

        <div class="ai-config__actions">
          <ElButton
            type="primary"
            :loading="saving"
            data-ai-action="save"
            @click="save"
          >
            保存配置
          </ElButton>
          <ElButton
            :loading="testing"
            :disabled="!hasApiKey"
            data-ai-action="test"
            @click="test"
          >
            测试连接
          </ElButton>
          <ElButton
            v-if="!config.enabled"
            type="success"
            plain
            :disabled="!config.testedForCurrentRevision || !hasApiKey"
            data-ai-action="enable"
            @click="toggleEnabled(true)"
          >
            启用
          </ElButton>
          <ElButton
            v-else
            plain
            data-ai-action="disable"
            @click="toggleEnabled(false)"
          >
            停用
          </ElButton>
          <ElButton
            v-if="canClearKey"
            type="danger"
            plain
            data-ai-action="clear-key"
            @click="clearKey"
          >
            清除密钥
          </ElButton>
        </div>

        <dl class="ai-config__state">
          <div>
            <dt>当前版本</dt>
            <dd>{{ config.version }}</dd>
          </div>
          <div>
            <dt>配置修订</dt>
            <dd>r{{ config.connectionRevision }}</dd>
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
          未通过当前修订的测试不能启用，启用后修改任何调用参数都需要重新测试。
        </p>
      </ElForm>
    </template>
  </section>
</template>

<style scoped>
.ai-config {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
}

.ai-config__head {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
}

.ai-config__head h2 {
  margin: 0;
  font-size: var(--ui-font-size-section);
}

.ai-config__intro,
.ai-config__hint,
.ai-config__note {
  margin: 0;
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-body);
}

.ai-config__error {
  margin: 0;
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.ai-config__form {
  max-width: var(--ui-form-max-width);
}

.ai-config__control {
  width: var(--ui-layout-card-min-width-wide);
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
</style>
