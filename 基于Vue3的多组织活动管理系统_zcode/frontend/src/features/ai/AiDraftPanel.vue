<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElInput, ElInputNumber, ElTag } from 'element-plus'
import { ApiError, readErrorMessage } from '../../shared/http/error'
import { newIdempotencyKey } from '../../shared/http/idempotency'
import { useSessionStore } from '../../stores/session'
import {
  adoptAiGeneration,
  canAdoptGeneration,
  fetchAiInputPreview,
  generateAiDraft,
  getAiGenerationByRequestKey,
  generationStatusText,
  planConditionsReady,
  toPlanConditions,
  type AiGeneration,
  type AiGenerationType,
  type AiInputPreview,
  type AiPlanConditions,
} from './ai'

/**
 * P8.1 AiDraftPanel：两项 AI 功能共用的生成与采用面板。
 *
 * 只负责生成草稿、展示输入范围与状态、把草稿交给调用方编辑，以及记录人工采用；
 * 方案与总结的保存仍由所属页面调用原有业务端点（版本与来源校验在那里执行），
 * 模型输出一律按纯文本显示，不执行其中的 HTML、脚本或业务指令。
 */
const props = defineProps<{
  orgId: string
  activityId: string
  type: AiGenerationType
  /** 方案生成在草稿页填写条件；总结生成只用活动记录。 */
  conditions?: AiPlanConditions
  /** 活动已归档或当前身份不可写时禁用生成入口。 */
  disabled?: boolean
}>()

const emit = defineEmits<{
  (event: 'apply', draft: string): void
}>()

const session = useSessionStore()
const preview = ref<AiInputPreview | null>(null)
const loadingPreview = ref(false)
const previewFailed = ref(false)
let previewRequestSeq = 0
const generating = ref(false)
const adopting = ref(false)
const errorText = ref('')
const noticeText = ref('')
const generation = ref<AiGeneration | null>(null)
const pendingGeneration = ref<{ key: string; conditions: AiPlanConditions } | null>(null)
const draft = ref('')
const draftBaseline = ref('')
const showScope = ref(false)
const localConditions = ref<AiPlanConditions>({
  topic: '',
  goal: '',
  audience: '',
  expectedParticipants: null,
  budget: '',
  timePlace: '',
  constraints: '',
})

const isPlan = computed(() => props.type === 'ACTIVITY_PLAN')
const effectiveConditions = computed<AiPlanConditions>(() => props.conditions ?? localConditions.value)
const ready = computed(() => !isPlan.value || planConditionsReady(effectiveConditions.value))
const canGenerate = computed(() => !props.disabled && (Boolean(pendingGeneration.value) || (ready.value && Boolean(preview.value?.available))))
const statusText = computed(() => (generation.value ? generationStatusText(generation.value) : ''))
const adoptable = computed(() => canAdoptGeneration(generation.value))

function reportError(error: unknown, fallback: string, gen: number): void {
  if (session.observeUnauthorized(error, gen)) {
    return
  }
  errorText.value = error instanceof ApiError ? error.detail : fallback
}

async function loadPreview(): Promise<void> {
  if (!props.orgId || !props.activityId) {
    return
  }
  const seq = ++previewRequestSeq
  const gen = session.capture()
  loadingPreview.value = true
  previewFailed.value = false
  errorText.value = ''
  try {
    const result = await fetchAiInputPreview(props.orgId, props.activityId, props.type)
    if (seq !== previewRequestSeq || gen !== session.generation) return
    preview.value = result
  } catch (error) {
    if (session.observeUnauthorized(error, gen) || seq !== previewRequestSeq || gen !== session.generation) return
    preview.value = null
    previewFailed.value = true
    errorText.value = readErrorMessage(error, '输入范围加载失败，可继续手工编写。')
  } finally {
    if (seq === previewRequestSeq) loadingPreview.value = false
  }
}

/** 未知结果只按原键查询；确认结束后下一次点击才是新的生成意图。 */
async function generate(): Promise<void> {
  if (generating.value) return
  const gen = session.capture()
  const checking = Boolean(pendingGeneration.value)
  if (!pendingGeneration.value) pendingGeneration.value = {
    key: newIdempotencyKey(), conditions: toPlanConditions(effectiveConditions.value),
  }
  const pending = pendingGeneration.value
  generating.value = true
  errorText.value = ''
  noticeText.value = ''
  try {
    const result = checking
      ? await getAiGenerationByRequestKey(props.orgId, props.activityId, pending.key)
      : await generateAiDraft(props.orgId, props.activityId, props.type, pending.conditions, pending.key)
    if (pendingGeneration.value !== pending) return
    await loadPreview()
    if (pendingGeneration.value !== pending) return
    generation.value = result
    if (result.status !== 'RUNNING') pendingGeneration.value = null
    if (result.status === 'SUCCEEDED' && result.output) {
      draft.value = result.output
      draftBaseline.value = result.output
      noticeText.value = '草稿仅在本面板中显示，点“采用到编辑区”后可继续人工修改，再走原有保存与确认流程。'
    } else if (result.status === 'FAILED') {
      errorText.value = result.errorMessage ?? '本次生成失败，可继续手工编写。'
    } else {
      errorText.value = '生成仍在进行，请稍后核对原请求结果。'
    }
  } catch (error) {
    if (pendingGeneration.value !== pending) return
    if (!checking && error instanceof ApiError && error.kind === 'server'
      && error.status >= 400 && error.status < 500 && error.code !== 'REQUEST_IN_PROGRESS') {
      pendingGeneration.value = null
      reportError(error, '本次生成未被受理，可修改条件后重试。', gen)
    } else if (!session.observeUnauthorized(error, gen)) {
      errorText.value = '生成结果尚未确认，请点“核对生成结果”；核对不会再次调用模型。'
    }
  } finally {
    generating.value = false
  }
}

function applyDraft(): void {
  if (draft.value.trim()) {
    emit('apply', draft.value)
    draftBaseline.value = draft.value
    noticeText.value = '草稿已放入编辑区，请人工修改后再保存。'
  }
}

/** 人工采用：只记录采用状态；真正生效仍以业务端点的保存与确认为准。 */
async function adopt(): Promise<void> {
  const target = generation.value
  if (!target || adopting.value) return
  const gen = session.capture()
  adopting.value = true
  errorText.value = ''
  try {
    const result = await adoptAiGeneration(props.orgId, props.activityId, target.generationId)
    if (generation.value !== target || gen !== session.generation) return
    generation.value = result
    noticeText.value = '已记录采用状态；保存后仍按各自流程校验权限与来源。'
  } catch (error) {
    if (generation.value === target && gen === session.generation) {
      reportError(error, '采用状态记录失败，内容不受影响。', gen)
    } else {
      session.observeUnauthorized(error, gen)
    }
  } finally {
    adopting.value = false
  }
}

watch(() => [props.orgId, props.activityId, props.type], () => {
  pendingGeneration.value = null
  generation.value = null
  draft.value = ''
  draftBaseline.value = ''
  preview.value = null
  errorText.value = ''
  noticeText.value = ''
  void loadPreview()
}, { immediate: true })

defineExpose({
  hasPendingGeneration: () => Boolean(pendingGeneration.value) || draft.value !== draftBaseline.value,
  isGenerating: () => generating.value,
})
</script>

<template>
  <section
    class="ai-draft"
    :data-ai-type="type"
  >
    <div class="ai-draft__head">
      <h3>{{ isPlan ? 'AI 方案建议' : 'AI 总结草稿' }}</h3>
      <ElTag
        v-if="preview"
        size="small"
        :type="preview.available ? 'success' : 'info'"
        data-ai-availability
      >
        {{ preview.available ? '模型可用' : '模型不可用' }}
      </ElTag>
    </div>
    <p class="ai-draft__hint">
      {{ preview?.availabilityHint ?? (loadingPreview ? '正在读取模型可用状态……' : '模型可用状态读取失败，可继续手工编写') }}；
      生成的文字只是草稿，需人工编辑并保存，不会自动创建任务、改变预算或批准活动。
    </p>

    <template v-if="isPlan">
      <div class="ai-draft__fields">
        <label
          class="ai-draft__field"
          for="ai-plan-topic"
        >
          <span>主题</span>
          <ElInput
            id="ai-plan-topic"
            v-model="localConditions.topic"
            :disabled="Boolean(pendingGeneration)"
            data-ai-field="topic"
            placeholder="例如 迎新晚会"
          />
        </label>
        <label
          class="ai-draft__field"
          for="ai-plan-goal"
        >
          <span>目标</span>
          <ElInput
            id="ai-plan-goal"
            v-model="localConditions.goal"
            :disabled="Boolean(pendingGeneration)"
            data-ai-field="goal"
            placeholder="例如 帮助新成员熟悉组织"
          />
        </label>
        <label
          class="ai-draft__field"
          for="ai-plan-audience"
        >
          <span>参与对象</span>
          <ElInput
            id="ai-plan-audience"
            v-model="localConditions.audience"
            :disabled="Boolean(pendingGeneration)"
            data-ai-field="audience"
            placeholder="例如 新成员与老成员"
          />
        </label>
        <label
          class="ai-draft__field"
          for="ai-plan-participants"
        >
          <span>预计人数</span>
          <ElInputNumber
            id="ai-plan-participants"
            v-model="localConditions.expectedParticipants"
            :disabled="Boolean(pendingGeneration)"
            :min="1"
            :max="100000"
            data-ai-field="expectedParticipants"
          />
        </label>
        <label
          class="ai-draft__field"
          for="ai-plan-budget"
        >
          <span>预算条件</span>
          <ElInput
            id="ai-plan-budget"
            v-model="localConditions.budget"
            :disabled="Boolean(pendingGeneration)"
            data-ai-field="budget"
            placeholder="例如 不超过 3000 元"
          />
        </label>
        <label
          class="ai-draft__field"
          for="ai-plan-time-place"
        >
          <span>时间地点条件</span>
          <ElInput
            id="ai-plan-time-place"
            v-model="localConditions.timePlace"
            :disabled="Boolean(pendingGeneration)"
            data-ai-field="timePlace"
            placeholder="例如 九月第一个周五晚上，礼堂"
          />
        </label>
        <label
          class="ai-draft__field"
          for="ai-plan-constraints"
        >
          <span>补充限制</span>
          <ElInput
            id="ai-plan-constraints"
            v-model="localConditions.constraints"
            :disabled="Boolean(pendingGeneration)"
            data-ai-field="constraints"
            placeholder="例如 不使用明火"
          />
        </label>
      </div>
      <p class="ai-draft__hint">
        未填写的条件会由模型标注“假设”或“待补充”；费用建议只是估算，不代表报价或已批准预算。
      </p>
    </template>

    <div class="ai-draft__scope">
      <ElButton
        size="small"
        link
        data-ai-action="toggle-scope"
        @click="showScope = !showScope"
      >
        {{ showScope ? '收起输入范围' : '查看本次输入范围' }}
      </ElButton>
      <ElButton
        v-if="previewFailed"
        size="small"
        :loading="loadingPreview"
        @click="loadPreview"
      >
        重新读取
      </ElButton>
      <span
        v-if="loadingPreview"
        class="ai-draft__hint"
      >正在读取……</span>
      <ul
        v-else-if="showScope && preview"
        class="ai-draft__scope-list"
        data-ai-scope
      >
        <li
          v-for="item in preview.scope"
          :key="item"
        >
          {{ item }}
        </li>
        <li
          v-if="preview.missing.length > 0"
          class="ai-draft__missing"
          data-ai-missing
        >
          缺失或未记录：{{ preview.missing.join('、') }}
        </li>
      </ul>
    </div>

    <div class="ai-draft__actions">
      <ElButton
        type="primary"
        :loading="generating"
        :disabled="!canGenerate"
        data-ai-action="generate"
        @click="generate"
      >
        {{ generating ? '处理中……' : pendingGeneration ? '核对生成结果' : '生成草稿' }}
      </ElButton>
      <span
        v-if="disabled"
        class="ai-draft__hint"
      >当前状态或身份不可生成，可继续手工编写。</span>
    </div>

    <ElAlert
      v-if="noticeText"
      class="ai-draft__notice"
      type="success"
      :closable="false"
      :title="noticeText"
    />
    <p
      v-if="errorText"
      class="ai-draft__error"
      role="alert"
    >
      {{ errorText }}
    </p>

    <template v-if="generation">
      <p
        class="ai-draft__status"
        data-ai-status
      >
        {{ statusText }}
      </p>
      <p
        v-if="generation.sourceChanged"
        class="ai-draft__warning"
        data-ai-source-changed
      >
        生成后活动记录已变化，草稿可能不再对应当前情况；请复核后重新生成，人工内容不会被自动覆盖。
      </p>
      <textarea
        v-if="generation.status === 'SUCCEEDED' && generation.output"
        v-model="draft"
        class="ai-draft__output"
        data-ai-output
        rows="10"
        aria-label="AI 草稿文本"
      />
      <div
        v-if="generation.status === 'SUCCEEDED'"
        class="ai-draft__actions"
      >
        <ElButton
          data-ai-action="apply"
          @click="applyDraft"
        >
          采用到编辑区
        </ElButton>
        <ElButton
          :loading="adopting"
          :disabled="!adoptable"
          data-ai-action="adopt"
          @click="adopt"
        >
          {{ generation.adoptedAt ? '已采用' : '记录为已采用' }}
        </ElButton>
      </div>
      <p class="ai-draft__hint">
        模型：{{ generation.model ?? '—' }}｜协议：{{ generation.protocol ?? '—' }}
        <template v-if="generation.requestId">
          ｜请求标识：{{ generation.requestId }}
        </template>
      </p>
    </template>
  </section>
</template>

<style scoped>
.ai-draft {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
  padding: var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-ai-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-surface-ai);
}

.ai-draft__head {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
}

.ai-draft__head h3 {
  margin: 0;
  font-size: var(--ui-font-size-card-title);
}

.ai-draft__hint,
.ai-draft__status {
  margin: 0;
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-body);
}

.ai-draft__error {
  margin: 0;
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.ai-draft__warning {
  margin: 0;
  color: var(--ui-color-warning-dark);
  font-size: var(--ui-font-size-small);
}

.ai-draft__fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(var(--ui-layout-card-min-width), 1fr));
  gap: var(--ui-form-field-gap);
}

.ai-draft__field {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
  font-size: var(--ui-font-size-small);
  color: var(--ui-color-text-secondary);
}

.ai-draft__scope-list {
  margin: var(--ui-space-2) 0 0;
  padding-left: var(--ui-space-5);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-body);
}

.ai-draft__missing {
  color: var(--ui-color-warning-dark);
}

.ai-draft__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ui-space-2);
}

.ai-draft__output {
  width: 100%;
  max-width: var(--ui-form-max-width);
  padding: var(--ui-space-3);
  border: var(--ui-border-width) solid var(--ui-color-control-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-surface);
  color: var(--ui-color-text);
  font-family: var(--ui-font-family-body);
  font-size: var(--ui-font-size-body);
  line-height: var(--ui-line-height-prose);
  resize: vertical;
}
</style>
