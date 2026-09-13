<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  ElButton, ElForm, ElFormItem, ElInput, ElMessage, ElMessageBox, ElOption, ElRate, ElSelect,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { resolveMyMemberId } from '../activity/p3'
import {
  addExecutionRecord, archiveActivity, cancelActivity, confirmSummary, executionCategoryLabel,
  fetchExecutionRecords, finishExecution, isArchivable, isExecutionRecordable, isFeedbackWindowOpen,
  reopenActivity, saveSummary, startExecution,
  type ActivityLifecycleStatus, type ExecutionRecordCategory, type ExecutionRecordItem,
} from '../activity/lifecycle'
import {
  averageLabel, fetchFeedback, submitFeedback, type FeedbackSummary,
} from '../feedback/feedback'

// P6 生命周期区块：执行、取消、执行记录、反馈、总结与归档。
// 权限仍由后端逐请求判定：负责人或组织负责人可执行／记录／总结；确认、归档与重新打开仅组织负责人。
const props = defineProps<{
  orgDetail: {
    id: string
    status?: ActivityLifecycleStatus
    ownerMemberId?: string
    summary?: string | null
    summaryConfirmedAt?: string | null
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

const feedback = ref<FeedbackSummary | null>(null)
const rating = ref(0)
const comment = ref('')
const feedbackSubmitting = ref(false)

const summaryDraft = ref('')
const summaryDirty = ref(false)
const summarySubmitting = ref(false)

const loading = ref(false)
const errorText = ref('')
const actionError = ref('')
const acting = ref(false)
const myMemberId = ref<string | null>(null)

const canManage = computed(() => props.orgRole === 'OWNER'
  || (myMemberId.value !== null && myMemberId.value === props.orgDetail?.ownerMemberId))
const isOrgOwner = computed(() => props.orgRole === 'OWNER')
/** 取消与归档都是组织负责人的最终确认动作（计划书 7.4）。 */
const canFinalize = computed(() => isOrgOwner.value)

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
    summaryDraft.value = props.orgDetail?.summary ?? ''
    summaryDirty.value = false
    const [recordItems, feedbackSummary] = await Promise.all([
      fetchExecutionRecords(orgId.value, activityId.value),
      fetchFeedback(orgId.value, activityId.value),
    ])
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    records.value = recordItems
    feedback.value = feedbackSummary
    rating.value = feedbackSummary.myRating ?? 0
    comment.value = feedbackSummary.myComment ?? ''
  } catch (error) {
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    records.value = []
    feedback.value = null
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '生命周期信息加载失败，请重试。'
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

watch([orgId, activityId, () => props.orgDetail?.status], () => {
  void loadAll()
})
onMounted(loadAll)

async function guard(fn: () => Promise<void>): Promise<void> {
  actionError.value = ''
  acting.value = true
  try {
    await fn()
  } catch (error) {
    if (session.observeUnauthorized(error, session.capture())) {
      return
    }
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
  } finally {
    acting.value = false
  }
}

function currentVersion(): number {
  return (props.orgDetail as { version?: number } | null)?.version ?? 0
}

function start() {
  void ElMessageBox.confirm(
    '启动执行后停止新报名与自助取消，并固定反馈资格依据（执行开始时的已批准报名者）。',
    '启动执行', { confirmButtonText: '启动执行', cancelButtonText: '取消', type: 'info' },
  )
    .then(() => guard(async () => {
      await startExecution(orgId.value, activityId.value, currentVersion())
      ElMessage.success('活动已进入执行')
      emit('changed')
      await loadAll()
    }))
    .catch(() => undefined)
}

function finish() {
  void ElMessageBox.confirm('确认执行结束后进入复盘；未完成任务不会自动标记为完成。', '结束执行', {
    confirmButtonText: '进入复盘', cancelButtonText: '取消', type: 'info',
  })
    .then(() => guard(async () => {
      await finishExecution(orgId.value, activityId.value, currentVersion())
      ElMessage.success('活动已进入复盘')
      emit('changed')
      await loadAll()
    }))
    .catch(() => undefined)
}

function cancel() {
  void ElMessageBox.prompt('取消后停止报名、关闭待审申请、有效报名转为活动取消结果，未完成任务转为取消。', '取消活动', {
    confirmButtonText: '确认取消活动', cancelButtonText: '返回', inputPlaceholder: '取消原因（必填）', type: 'warning',
  })
    .then(({ value }) => guard(async () => {
      await cancelActivity(orgId.value, activityId.value, (value ?? '').trim(), currentVersion())
      ElMessage.success('活动已取消，请处理遗留事项')
      emit('changed')
      await loadAll()
    }))
    .catch(() => undefined)
}

async function addRecord() {
  if (!recordContent.value.trim()) {
    actionError.value = '执行记录内容不能为空'
    return
  }
  recordSubmitting.value = true
  await guard(async () => {
    await addExecutionRecord(orgId.value, activityId.value, recordCategory.value, recordContent.value.trim())
    recordContent.value = ''
    records.value = await fetchExecutionRecords(orgId.value, activityId.value)
  })
  recordSubmitting.value = false
}

async function submitMyFeedback() {
  if (rating.value < 1) {
    actionError.value = '请先给出 1～5 分评分'
    return
  }
  feedbackSubmitting.value = true
  await guard(async () => {
    await submitFeedback(orgId.value, activityId.value, rating.value, comment.value.trim() || undefined)
    feedback.value = await fetchFeedback(orgId.value, activityId.value)
    ElMessage.success('反馈已保存')
  })
  feedbackSubmitting.value = false
}

async function saveSummaryDraft() {
  if (!summaryDraft.value.trim()) {
    actionError.value = '总结内容不能为空'
    return
  }
  summarySubmitting.value = true
  await guard(async () => {
    await saveSummary(orgId.value, activityId.value, summaryDraft.value.trim(), currentVersion())
    summaryDirty.value = false
    emit('changed')
    await loadAll()
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

function archive() {
  void ElMessageBox.confirm('归档后普通编辑、上传、删除与反馈修改全部关闭；如需更正请重新打开。', '归档活动', {
    confirmButtonText: '归档', cancelButtonText: '返回', type: 'warning',
  })
    .then(() => guard(async () => {
      await archiveActivity(orgId.value, activityId.value, currentVersion())
      ElMessage.success('活动已归档')
      emit('changed')
      await loadAll()
    }))
    .catch(() => undefined)
}

function reopen() {
  void ElMessageBox.prompt('重新打开不恢复报名与反馈入口；归档活动回到复盘，取消活动回到取消处理。', '重新打开活动', {
    confirmButtonText: '重新打开', cancelButtonText: '返回', inputPlaceholder: '重新打开理由（必填）', type: 'info',
  })
    .then(({ value }) => guard(async () => {
      await reopenActivity(orgId.value, activityId.value, (value ?? '').trim(), currentVersion())
      ElMessage.success('活动已重新打开')
      emit('changed')
      await loadAll()
    }))
    .catch(() => undefined)
}

defineExpose({ reload: loadAll })
</script>

<template>
  <section
    v-if="canManage || feedback"
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
        v-if="canFinalize && (status === 'APPROVED' || status === 'EXECUTING' || status === 'REVIEWING')"
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
      <ul
        v-if="records.length > 0"
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
        v-else
        class="lifecycle-note"
      >
        暂无执行记录。
      </p>
      <div
        v-if="canManage && isExecutionRecordable(status)"
        class="lifecycle-record-form"
      >
        <ElSelect
          v-model="recordCategory"
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
          记录
        </ElButton>
      </div>
    </section>

    <section class="lifecycle-block">
      <h3>反馈</h3>
      <p class="lifecycle-note">
        份数 {{ feedback?.count ?? 0 }} · 平均评分 {{ averageLabel(feedback) }}
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
            :disabled="!feedback?.canSubmit"
            :max="5"
          />
        </ElFormItem>
        <ElFormItem label="文字建议">
          <ElInput
            v-model="comment"
            type="textarea"
            :rows="2"
            :disabled="!feedback?.canSubmit"
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
      <h3>总结</h3>
      <ElInput
        v-model="summaryDraft"
        type="textarea"
        :rows="4"
        placeholder="目标、执行概况、任务与报名汇总、经费、反馈要点、问题及改进项（处理人与后续安排）"
        @input="summaryDirty = true"
      />
      <p class="lifecycle-note">
        <template v-if="orgDetail?.summaryConfirmedAt">
          已确认：{{ orgDetail.summaryConfirmedAt }}
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
          @click="saveSummaryDraft"
        >
          保存总结
        </ElButton>
        <ElButton
          size="small"
          type="primary"
          plain
          :disabled="!orgDetail?.summary"
          @click="confirmSummaryDraft"
        >
          确认总结
        </ElButton>
      </div>
    </section>
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
