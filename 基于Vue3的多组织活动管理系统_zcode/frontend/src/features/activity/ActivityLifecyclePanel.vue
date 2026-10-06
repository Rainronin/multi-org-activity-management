<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  ElButton, ElForm, ElFormItem, ElInput, ElMessage, ElMessageBox, ElOption, ElRate, ElSelect,
  ElTag, ElCollapse, ElCollapseItem, ElPagination,
} from 'element-plus'
import { ApiError, readErrorMessage } from '../../shared/http/error'
import { newIdempotencyKey } from '../../shared/http/idempotency'
import { formatDateTime } from './types'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { resolveMyMemberId } from '../activity/p3'
import {
  addExecutionRecord, archiveActivity, cancelActivity, confirmSummary, executionCategoryLabel,
  fetchConfirmations, fetchExecutionRecords, finishExecution, isArchivable, isExecutionRecordable, isFeedbackWindowOpen,
  reopenActivity, saveSummary, startExecution,
  type ActivityLifecycleStatus, type ConfirmationPage, type ExecutionRecordCategory, type ExecutionRecordItem,
} from '../activity/lifecycle'
import {
  averageLabel, fetchFeedback, submitFeedback, type FeedbackSummary,
} from '../feedback/feedback'
import AiDraftPanel from '../ai/AiDraftPanel.vue'
import { confirmDraftSave } from './confirmDraftSave'

// P6 生命周期区块：执行、取消、执行记录、反馈、总结与归档。
// 权限仍由后端逐请求判定：负责人或组织负责人可执行／记录／总结；确认、归档与重新打开仅组织负责人。
const props = defineProps<{
  orgDetail: {
    id: string
    version?: number
    status?: ActivityLifecycleStatus
    ownerMemberId?: string
    summary?: string | null
    summaryConfirmedAt?: string | null
    summaryNeedsReview?: boolean
    archivedAt?: string | null
    cancelReason?: string | null
    cancelledAt?: string | null
    reopenCount?: number
  } | null
  orgRole?: 'OWNER' | 'MEMBER' | null
}>()
const emit = defineEmits<{ changed: [] }>()

const route = useRoute()
const session = useSessionStore()
const orgId = computed(() => String(route.params.orgId ?? ''))
const activityId = computed(() => String(route.params.activityId ?? ''))

const status = computed<ActivityLifecycleStatus>(() => (props.orgDetail?.status ?? 'DRAFT') as ActivityLifecycleStatus)
const everArchived = computed(() => Boolean(props.orgDetail?.archivedAt))
const feedbackOpen = computed(() => isFeedbackWindowOpen(status.value, everArchived.value))

const records = ref<ExecutionRecordItem[]>([])
const recordCategory = ref<ExecutionRecordCategory>('PROGRESS')
const recordContent = ref('')
const recordSubmitting = ref(false)
const pendingRecord = ref<{ category: ExecutionRecordCategory; content: string; key: string } | null>(null)

const feedback = ref<FeedbackSummary | null>(null)
const rating = ref(0)
let feedbackVersion = 0
const feedbackConflict = ref(false)
const comment = ref('')
const feedbackSubmitting = ref(false)
const feedbackDirty = computed(() => rating.value !== (feedback.value?.myRating ?? 0)
  || comment.value !== (feedback.value?.myComment ?? ''))

const summaryDraft = ref('')
const summaryDirty = ref(false)
let summaryVersion = 0
const summarySubmitting = ref(false)
const summaryAiPanel = ref<InstanceType<typeof AiDraftPanel> | null>(null)

const loading = ref(true)
const errorText = ref('')
const actionError = ref('')
const acting = ref(false)
const myMemberId = ref<string | null>(null)
const historyOpen = ref<string[]>([])
const historyPage = ref(1)
const history = ref<ConfirmationPage | null>(null)
const historyLoading = ref(false)
const historyError = ref('')
const confirmationLabel = { PROPOSAL: '方案', SUMMARY: '总结', CANCEL_NOTE: '取消说明' }
let historyRequestSeq = 0

const canManage = computed(() => props.orgRole === 'OWNER'
  || (myMemberId.value !== null && myMemberId.value === props.orgDetail?.ownerMemberId))
const isOrgOwner = computed(() => props.orgRole === 'OWNER')
/** 取消与归档都是组织负责人的最终确认动作（计划书 7.4）。 */
const canFinalize = computed(() => isOrgOwner.value)
/** P8.2：总结生成与后端一致——只在复盘期或取消后的遗留处理期可用，归档后只读。 */
const summaryWritable = computed(() => (status.value === 'REVIEWING' || status.value === 'CANCELLED')
  && !everArchived.value)
const summaryEditable = computed(() => status.value === 'REVIEWING' || status.value === 'CANCELLED')

const statusLabel: Record<ActivityLifecycleStatus, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待确认',
  APPROVED: '已确认',
  EXECUTING: '执行中',
  REVIEWING: '复盘中',
  ARCHIVED: '已归档',
  CANCELLED: '已取消',
}

let requestSeq = 0
let alive = true
onBeforeUnmount(() => {
  alive = false
  requestSeq++
  historyRequestSeq++
})

async function loadHistory() {
  const context = captureAction()
  const seq = ++historyRequestSeq
  historyLoading.value = true
  historyError.value = ''
  try {
    const result = await fetchConfirmations(context.orgId, context.activityId, historyPage.value)
    if (seq === historyRequestSeq && isActionCurrent(context)) history.value = result
  } catch (error) {
    if (seq !== historyRequestSeq || !isActionCurrent(context)) return
    history.value = null
    if (!session.observeUnauthorized(error, context.generation)) historyError.value = readErrorMessage(error, '确认历史加载失败，请重试。')
  } finally {
    if (seq === historyRequestSeq) historyLoading.value = false
  }
}

watch([historyOpen, historyPage, () => props.orgDetail?.version], () => {
  if (historyOpen.value.length > 0) void loadHistory()
})
watch([orgId, activityId], () => {
  historyRequestSeq++
  history.value = null
  historyError.value = ''
  historyLoading.value = false
  historyOpen.value = []
  historyPage.value = 1
})

async function loadAll(): Promise<void> {
  if (!orgId.value || !activityId.value) {
    return
  }
  const seq = ++requestSeq
  const gen = session.capture()
  loading.value = true
  errorText.value = ''
  try {
    const memberId = await resolveMyMemberId(orgId.value, session.account?.username ?? '')
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    myMemberId.value = memberId
    if (!summaryDirty.value) {
      summaryDraft.value = props.orgDetail?.summary ?? ''
      summaryVersion = currentVersion()
    }
    const [recordItems, feedbackSummary] = await Promise.all([
      fetchExecutionRecords(orgId.value, activityId.value),
      fetchFeedback(orgId.value, activityId.value),
    ])
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    records.value = recordItems
    const keepFeedbackDraft = feedbackDirty.value
    feedback.value = feedbackSummary
    if (!keepFeedbackDraft) {
      feedbackVersion = feedbackSummary.myVersion ?? 0
      rating.value = feedbackSummary.myRating ?? 0
      comment.value = feedbackSummary.myComment ?? ''
    }
  } catch (error) {
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    records.value = []
    feedback.value = null
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = readErrorMessage(error, '生命周期信息加载失败，请重试。')
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

watch([orgId, activityId, () => props.orgDetail?.status, () => props.orgDetail?.version], () => {
  void loadAll()
})
onMounted(loadAll)

function captureAction() {
  return { orgId: orgId.value, activityId: activityId.value, generation: session.capture(), version: currentVersion() }
}

function isActionCurrent(context: ReturnType<typeof captureAction>): boolean {
  return alive && context.orgId === orgId.value && context.activityId === activityId.value && context.generation === session.generation
}

async function guard(fn: () => Promise<void>, context = captureAction()): Promise<unknown> {
  if (!isActionCurrent(context)) return
  actionError.value = ''
  acting.value = true
  try {
    await fn()
  } catch (error) {
    if (session.observeUnauthorized(error, context.generation)) {
      return error
    }
    if (!isActionCurrent(context)) return error
    if (error instanceof ApiError && !error.isTransportError) {
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        actionError.value = '安全校验已过期，请重试。'
      } else if (error.code === 'EDIT_CONFLICT') {
        actionError.value = error.detail
        await loadAll()
        emit('changed')
      } else {
        actionError.value = error.detail || '操作失败，请重试。'
      }
    } else {
      actionError.value = '网络连接失败，请检查网络后重试。'
    }
    return error
  } finally {
    acting.value = false
  }
}

function currentVersion(): number {
  return props.orgDetail?.version ?? 0
}

function start() {
  const context = captureAction()
  void ElMessageBox.confirm(
    '启动执行后停止新报名与自助取消，并固定反馈资格依据（执行开始时的已批准报名者）。',
    '启动执行', { confirmButtonText: '启动执行', cancelButtonText: '取消', type: 'info' },
  )
    .then(() => guard(async () => {
      await startExecution(context.orgId, context.activityId, context.version)
      ElMessage.success('活动已进入执行')
      emit('changed')
      await loadAll()
    }, context))
    .catch(() => undefined)
}

function finish() {
  const context = captureAction()
  void ElMessageBox.confirm('确认执行结束后进入复盘；未完成任务不会自动标记为完成。', '结束执行', {
    confirmButtonText: '进入复盘', cancelButtonText: '取消', type: 'info',
  })
    .then(() => guard(async () => {
      await finishExecution(context.orgId, context.activityId, context.version)
      ElMessage.success('活动已进入复盘')
      emit('changed')
      await loadAll()
    }, context))
    .catch(() => undefined)
}

function cancel() {
  const context = captureAction()
  void ElMessageBox.prompt('取消后停止报名、关闭待审申请、有效报名转为活动取消结果，未完成任务转为取消。', '取消活动', {
    confirmButtonText: '确认取消活动', cancelButtonText: '返回', inputPlaceholder: '取消原因（必填）', type: 'warning',
    inputValidator: value => Boolean(value?.trim()) || '请填写取消原因',
    beforeClose: async (action, instance, done) => {
      if (instance.confirmButtonLoading) return
      if (action !== 'confirm') { done(); return }
      instance.confirmButtonLoading = true
      const failure = await guard(async () => {
        await cancelActivity(context.orgId, context.activityId, instance.inputValue.trim(), context.version)
        if (!isActionCurrent(context)) return
        ElMessage.success('活动已取消，请处理遗留事项')
        emit('changed')
        await loadAll()
      }, context)
      instance.confirmButtonLoading = false
      if (!failure || !isActionCurrent(context)) done()
      else instance.editorErrorMessage = actionError.value
    },
  })
    .catch(() => undefined)
}

async function addRecord() {
  if (!recordContent.value.trim()) {
    actionError.value = '执行记录内容不能为空'
    return
  }
  recordSubmitting.value = true
  if (!pendingRecord.value) pendingRecord.value = {
    category: recordCategory.value, content: recordContent.value.trim(), key: newIdempotencyKey(),
  }
  const intent = pendingRecord.value
  const failure = await guard(async () => {
    await addExecutionRecord(orgId.value, activityId.value, intent.category, intent.content, intent.key)
    pendingRecord.value = null
    recordContent.value = ''
    records.value = await fetchExecutionRecords(orgId.value, activityId.value)
  })
  if (failure instanceof ApiError && !failure.isTransportError
    && [400, 403, 404, 422, 429].includes(failure.status)) pendingRecord.value = null
  recordSubmitting.value = false
}

async function submitMyFeedback() {
  if (rating.value < 1) {
    actionError.value = '请先给出 1～5 分评分'
    return
  }
  feedbackSubmitting.value = true
  const context = captureAction()
  const failure = await guard(async () => {
    const result = await submitFeedback(context.orgId, context.activityId, rating.value, comment.value.trim() || undefined, feedbackVersion)
    if (!isActionCurrent(context)) return
    feedbackVersion = result.version
    const saved = await fetchFeedback(context.orgId, context.activityId)
    if (!isActionCurrent(context)) return
    feedback.value = saved
    rating.value = saved.myRating ?? 0
    comment.value = saved.myComment ?? ''
    feedbackVersion = saved.myVersion ?? result.version
    ElMessage.success('反馈已保存')
  }, context)
  feedbackConflict.value = failure instanceof ApiError && failure.code === 'EDIT_CONFLICT'
  feedbackSubmitting.value = false
}

async function reviewFeedbackVersion() {
  const context = captureAction()
  await guard(async () => {
    const latest = await fetchFeedback(context.orgId, context.activityId)
    if (!isActionCurrent(context)) return
    try {
      await ElMessageBox.confirm(`已保存评分：${latest.myRating ?? '—'}；建议：${latest.myComment || '无'}。是否保留当前输入，改为基于此版本保存？`, '核对最新反馈', {
        confirmButtonText: '保留输入并使用此版本', cancelButtonText: '继续核对', type: 'warning',
      })
    } catch { return }
    if (!isActionCurrent(context)) return
    feedback.value = latest
    feedbackVersion = latest.myVersion ?? 0
    feedbackConflict.value = false
    actionError.value = '最新反馈已核对，输入仍保留；请再次保存。'
  }, context)
}

async function saveSummaryDraft() {
  if (!summaryDraft.value.trim()) {
    actionError.value = '总结内容不能为空'
    return
  }
  if (summarySubmitting.value) return
  summarySubmitting.value = true
  const version = await confirmDraftSave(summaryVersion, currentVersion())
  if (version === null) {
    summarySubmitting.value = false
    return
  }
  await guard(async () => {
    const saved = summaryDraft.value.trim()
    await saveSummary(orgId.value, activityId.value, saved, version)
    summaryDraft.value = saved
    summaryDirty.value = false
    emit('changed')
  })
  summarySubmitting.value = false
}

function confirmSummaryDraft() {
  void guard(async () => {
    await confirmSummary(orgId.value, activityId.value, currentVersion())
    ElMessage.success('总结已确认（来源摘要已记录）')
    emit('changed')
    await loadAll()
  })
}

/** P8.2：采用 AI 草稿只替换输入框内容，保存与确认仍由用户点击按钮完成。 */
function applySummaryDraft(draft: string) {
  summaryDraft.value = draft
  summaryDirty.value = draft !== (props.orgDetail?.summary ?? '')
}

function archive() {
  const context = captureAction()
  void ElMessageBox.confirm('归档后普通编辑、上传、删除与反馈修改全部关闭；如需更正请重新打开。', '归档活动', {
    confirmButtonText: '归档', cancelButtonText: '返回', type: 'warning',
  })
    .then(() => guard(async () => {
      await archiveActivity(context.orgId, context.activityId, context.version)
      ElMessage.success('活动已归档')
      emit('changed')
      await loadAll()
    }, context))
    .catch(() => undefined)
}

function reopen() {
  const context = captureAction()
  void ElMessageBox.prompt('重新打开不恢复报名与反馈入口；归档活动回到复盘，取消活动回到取消处理。', '重新打开活动', {
    confirmButtonText: '重新打开', cancelButtonText: '返回', inputPlaceholder: '重新打开理由（必填）', type: 'info',
    inputValidator: value => Boolean(value?.trim()) || '请填写重新打开理由',
    beforeClose: async (action, instance, done) => {
      if (instance.confirmButtonLoading) return
      if (action !== 'confirm') { done(); return }
      instance.confirmButtonLoading = true
      const failure = await guard(async () => {
        await reopenActivity(context.orgId, context.activityId, instance.inputValue.trim(), context.version)
        if (!isActionCurrent(context)) return
        ElMessage.success('活动已重新打开')
        emit('changed')
        await loadAll()
      }, context)
      instance.confirmButtonLoading = false
      if (!failure || !isActionCurrent(context)) done()
      else instance.editorErrorMessage = actionError.value
    },
  })
    .catch(() => undefined)
}

defineExpose({
  reload: loadAll,
  hasUnsavedSummary: () => summaryDirty.value || Boolean(summaryAiPanel.value?.hasPendingGeneration()),
  isSummarySubmitting: () => summarySubmitting.value || Boolean(summaryAiPanel.value?.isGenerating()),
  hasUnsavedFeedback: () => feedbackDirty.value,
  isFeedbackSubmitting: () => feedbackSubmitting.value,
  hasUnsavedRecord: () => Boolean(recordContent.value.trim()) || pendingRecord.value !== null,
  hasPendingRecord: () => pendingRecord.value !== null,
  isRecordSubmitting: () => recordSubmitting.value,
})
</script>

<template>
  <section
    v-if="canManage || feedback || loading || errorText"
    class="lifecycle-section"
  >
    <div class="lifecycle-section__head">
      <h2>执行与复盘</h2>
      <ElTag size="small">
        {{ statusLabel[status] }}
      </ElTag>
    </div>

    <p
      v-if="errorText"
      class="lifecycle-section__error"
      role="alert"
    >
      {{ errorText }}
      <ElButton
        size="small"
        @click="loadAll"
      >
        重试
      </ElButton>
    </p>
    <p
      v-if="actionError"
      class="lifecycle-section__error"
      role="alert"
    >
      {{ actionError }}
    </p>

    <div
      v-if="canManage"
      class="lifecycle-actions"
    >
      <ElButton
        v-if="status === 'APPROVED'"
        type="primary"
        size="small"
        :loading="acting"
        @click="start"
      >
        启动执行
      </ElButton>
      <ElButton
        v-if="status === 'EXECUTING'"
        type="primary"
        size="small"
        :loading="acting"
        @click="finish"
      >
        结束执行
      </ElButton>
      <ElButton
        v-if="canFinalize && status !== 'CANCELLED' && status !== 'ARCHIVED'"
        type="danger"
        size="small"
        plain
        :loading="acting"
        @click="cancel"
      >
        取消活动
      </ElButton>
      <ElButton
        v-if="canFinalize && isArchivable(status)"
        type="primary"
        size="small"
        :loading="acting"
        @click="archive"
      >
        归档活动
      </ElButton>
      <ElButton
        v-if="canFinalize && (status === 'ARCHIVED' || status === 'CANCELLED')"
        size="small"
        :loading="acting"
        @click="reopen"
      >
        重新打开
      </ElButton>
    </div>

    <p
      v-if="status === 'CANCELLED' && orgDetail?.cancelReason"
      class="lifecycle-note"
    >
      取消原因：{{ orgDetail.cancelReason }}（遗留事项请用执行记录说明处置人与结论）
    </p>
    <p
      v-if="everArchived"
      class="lifecycle-note"
    >
      该活动曾归档{{ orgDetail?.reopenCount ? `，已重新打开 ${orgDetail.reopenCount} 次` : '' }}；
      重新打开不恢复报名与反馈入口。
    </p>

    <section class="lifecycle-block">
      <h3>执行记录</h3>
      <p
        v-if="loading"
        class="lifecycle-note"
        role="status"
      >
        加载执行记录与反馈中…
      </p>
      <ul
        v-else-if="!errorText && records.length > 0"
        class="lifecycle-records"
      >
        <li
          v-for="record in records"
          :key="record.recordId"
        >
          <ElTag
            size="small"
            type="info"
          >
            {{ executionCategoryLabel[record.category] ?? record.category }}
          </ElTag>
          <span>{{ record.content }}</span>
          <span class="lifecycle-note">{{ record.recordedByUsername }} · {{ record.createdAt }}</span>
        </li>
      </ul>
      <p
        v-else-if="!errorText"
        class="lifecycle-note"
      >
        暂无执行记录。
      </p>
      <div
        v-if="canManage && (isExecutionRecordable(status) || pendingRecord)"
        class="lifecycle-record-form"
      >
        <ElSelect
          v-model="recordCategory"
          :disabled="recordSubmitting || pendingRecord !== null"
          size="small"
          class="lifecycle-record-form__category"
        >
          <ElOption
            v-for="(label, value) in executionCategoryLabel"
            :key="value"
            :label="label"
            :value="value"
          />
        </ElSelect>
        <ElInput
          v-model="recordContent"
          :disabled="recordSubmitting || pendingRecord !== null"
          size="small"
          placeholder="进展、问题、临时安排、实际差异或处理结果"
        />
        <ElButton
          size="small"
          type="primary"
          plain
          :loading="recordSubmitting"
          @click="addRecord"
        >
          {{ pendingRecord ? '核对记录结果' : '记录' }}
        </ElButton>
      </div>
      <p
        v-if="pendingRecord && !recordSubmitting"
        class="lifecycle-note"
        role="status"
      >
        记录结果尚未确认，请保留原内容并核对，避免重复追加。
      </p>
    </section>

    <section class="lifecycle-block">
      <h3>反馈</h3>
      <p
        v-if="!loading && !errorText && feedback"
        class="lifecycle-note"
      >
        份数 {{ feedback.count }} · 平均评分 {{ averageLabel(feedback) }}
        <template v-if="feedback && feedback.count === 0">
          （暂无反馈）
        </template>
      </p>
      <ElForm
        v-if="canManage || feedback?.mineSubmitted || feedbackOpen"
        label-position="top"
        class="lifecycle-feedback-form"
      >
        <ElFormItem label="总体评分（1～5 分）">
          <ElRate
            v-model="rating"
            :disabled="!feedback?.canSubmit || feedbackSubmitting"
            :max="5"
          />
        </ElFormItem>
        <ElFormItem label="文字建议">
          <ElInput
            v-model="comment"
            type="textarea"
            :rows="2"
            :disabled="!feedback?.canSubmit || feedbackSubmitting"
            placeholder="选填"
          />
        </ElFormItem>
        <p
          v-if="feedback && !feedback.canSubmit && feedback.submitBlockedReason"
          class="lifecycle-note"
        >
          {{ feedback.submitBlockedReason }}
        </p>
        <ElButton
          v-if="feedback?.canSubmit"
          type="primary"
          size="small"
          :loading="feedbackSubmitting"
          @click="submitMyFeedback"
        >
          {{ feedback.mineSubmitted ? '更新反馈' : '提交反馈' }}
        </ElButton>
        <ElButton
          v-if="feedbackConflict"
          :disabled="feedbackSubmitting"
          @click="reviewFeedbackVersion"
        >
          核对最新反馈
        </ElButton>
      </ElForm>

      <ul
        v-if="canManage && feedback && feedback.items.length > 0"
        class="lifecycle-feedback-list"
      >
        <li
          v-for="item in feedback.items"
          :key="item.feedbackId"
        >
          <span>{{ item.accountUsername }}：{{ item.rating }} 分</span>
          <span class="lifecycle-note">{{ item.comment || '（未填写建议）' }}</span>
        </li>
      </ul>
    </section>

    <section
      v-if="canManage"
      class="lifecycle-block"
    >
      <h3>{{ orgDetail?.cancelledAt ? '取消说明' : '总结' }}</h3>
      <ElInput
        v-model="summaryDraft"
        type="textarea"
        :rows="4"
        :disabled="!summaryEditable || summarySubmitting"
        placeholder="目标、执行概况、任务与报名汇总、经费、反馈要点、问题及改进项（处理人与后续安排）"
        @input="summaryDirty = summaryDraft !== (orgDetail?.summary ?? '')"
      />
      <p class="lifecycle-note">
        <template v-if="orgDetail?.summaryConfirmedAt">
          已确认：{{ orgDetail.summaryConfirmedAt }}
          <template v-if="orgDetail.summaryNeedsReview">
            ；来源已变化，需要重新核对并确认
          </template>
          <template v-if="summaryDirty">
            ；当前草稿已修改，确认后归档才算有效
          </template>
        </template>
        <template v-else>
          尚未确认；归档要求先确认总结
        </template>
      </p>
      <div class="lifecycle-actions">
        <ElButton
          size="small"
          :loading="summarySubmitting"
          :disabled="!summaryEditable"
          @click="saveSummaryDraft"
        >
          保存总结
        </ElButton>
        <ElButton
          size="small"
          type="primary"
          plain
          :disabled="!summaryEditable || !orgDetail?.summary || summaryDirty || summarySubmitting || acting"
          :loading="acting"
          @click="confirmSummaryDraft"
        >
          确认总结
        </ElButton>
      </div>

      <!-- P8.2：总结生成；草稿只进入上面的输入框，确认与归档仍走原有流程 -->
      <AiDraftPanel
        ref="summaryAiPanel"
        :org-id="orgId"
        :activity-id="activityId"
        type="ACTIVITY_SUMMARY"
        :disabled="!summaryWritable || summarySubmitting"
        @apply="applySummaryDraft"
      />
    </section>

    <ElCollapse
      v-model="historyOpen"
      class="lifecycle-block"
    >
      <ElCollapseItem
        name="confirmations"
        title="查看文稿确认历史"
      >
        <p
          v-if="historyLoading"
          class="lifecycle-note"
          role="status"
        >
          加载确认历史中…
        </p>
        <p
          v-else-if="historyError"
          class="lifecycle-section__error"
          role="alert"
        >
          {{ historyError }} <ElButton
            size="small"
            @click="loadHistory"
          >
            重试
          </ElButton>
        </p>
        <template v-else-if="history">
          <p
            v-if="history.total === 0"
            class="lifecycle-note"
          >
            暂无可读取的确认快照。旧确认未保存正文，无法还原其历史内容。
          </p>
          <article
            v-for="item in history.items"
            :key="item.id"
            class="lifecycle-block"
          >
            <h3>{{ confirmationLabel[item.kind] }} · 活动版本 {{ item.activityVersion }}</h3>
            <p class="lifecycle-note">
              {{ item.confirmedByUsername }} · {{ formatDateTime(item.confirmedAt) }}
            </p>
            <p
              v-if="item.reopenReason"
              class="lifecycle-note"
            >
              更正理由：{{ item.reopenReason }}（重开 {{ item.reopenCount }} 次）
            </p>
            <p class="lifecycle-confirmation-content">
              {{ item.content }}
            </p>
          </article>
          <ElPagination
            v-if="history.total > history.pageSize"
            v-model:current-page="historyPage"
            :page-size="history.pageSize"
            :total="history.total"
            layout="prev, pager, next"
          />
        </template>
      </ElCollapseItem>
    </ElCollapse>
  </section>
</template>

<style scoped>
.lifecycle-section {
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
}

.lifecycle-section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-4);
}

.lifecycle-section__head h2 {
  font-size: var(--ui-font-size-section);
}

.lifecycle-section__error {
  margin-top: var(--ui-space-3);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.lifecycle-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-3);
  margin-top: var(--ui-space-3);
}

.lifecycle-note {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}

.lifecycle-block {
  margin-top: var(--ui-space-5);
}

.lifecycle-block h3 {
  font-size: var(--ui-font-size-emphasis);
  margin-bottom: var(--ui-space-2);
}

.lifecycle-confirmation-content {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: var(--ui-line-height-prose);
}

.lifecycle-records,
.lifecycle-feedback-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
}

.lifecycle-records li,
.lifecycle-feedback-list li {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  font-size: var(--ui-font-size-small);
}

.lifecycle-record-form {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  margin-top: var(--ui-space-3);
}

.lifecycle-record-form__category {
  width: var(--ui-filter-width);
}

.lifecycle-feedback-form {
  margin-top: var(--ui-space-2);
}
</style>
