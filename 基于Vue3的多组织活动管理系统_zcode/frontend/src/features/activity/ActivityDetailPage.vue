<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessageBox,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import {
  approveActivity,
  deleteActivityDraft,
  fetchActivity,
  rejectActivity,
  saveProposal,
  submitActivity,
  updateActivity,
  withdrawActivity,
  withdrawApproval,
} from './api'
import { formatDateTime, scopeLabel, type ActivityDetail } from './types'
import ActivityP3Sections from './ActivityP3Sections.vue'
import RegistrationPanel from './RegistrationPanel.vue'
import ExpenseSection from '../expense/ExpenseSection.vue'

const props = defineProps<{ orgDetail: { id: string; role?: 'OWNER' | 'MEMBER' } | null }>()

/** 本人在当前组织的角色（由组织布局下发），用于决定 P3 区块按钮展示；权限仍由后端判定。 */
const orgRole = computed(() => props.orgDetail?.role ?? null)

// 活动详情（P2.1 余项 + P2.2）：概览字段、方案编辑与状态流转操作。
// 读取随路由与代次守卫；危险操作二次确认并固定目标。
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const orgId = computed(() => String(route.params.orgId ?? ''))
const activityId = computed(() => String(route.params.activityId ?? ''))

const activity = ref<ActivityDetail | null>(null)
const loading = ref(false)
const loadError = ref('')
const actionError = ref('')

const proposalDraft = ref('')
const proposalDirty = ref(false)
const proposalSaving = ref(false)

const editVisible = ref(false)
const editSubmitting = ref(false)
const editForm = reactive({
  name: '',
  description: '',
  startTime: '',
  endTime: '',
  location: '',
  budget: '0.00',
})

const rejectVisible = ref(false)
const rejectReason = ref('')
const rejectSubmitting = ref(false)

const acting = ref(false)

const statusLabel = computed(() => {
  const map = { DRAFT: '草稿', PENDING_APPROVAL: '待确认', APPROVED: '已确认' } as const
  return activity.value ? map[activity.value.status as keyof typeof map] ?? activity.value.status : ''
})

let requestSeq = 0

/** F05：编辑与审批必须携带当前版本；活动未加载时不发起写请求。 */
function currentVersion(): number {
  return activity.value?.version ?? 0
}

async function loadActivity(): Promise<void> {
  if (!orgId.value || !activityId.value) {
    return
  }
  const seq = ++requestSeq
  const targetId = activityId.value
  const gen = session.capture()
  loading.value = true
  loadError.value = ''
  try {
    const detail = await fetchActivity(orgId.value, targetId)
    if (seq !== requestSeq || targetId !== activityId.value || gen !== session.generation) {
      return
    }
    activity.value = detail
    proposalDraft.value = detail.proposal ?? ''
    proposalDirty.value = false
  } catch (error) {
    if (seq !== requestSeq || targetId !== activityId.value || gen !== session.generation) {
      return
    }
    activity.value = null
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    loadError.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '活动不存在或无权访问。'
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

watch([orgId, activityId], loadActivity)
onMounted(loadActivity)

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
      } else if (error.code === 'ACTIVITY_STATE_CONFLICT' || error.code === 'EDIT_CONFLICT') {
        // F05：版本过期或状态已变化都要求刷新后由用户重新决定
        actionError.value = error.detail
        await loadActivity()
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

function onProposalInput() {
  proposalDirty.value = proposalDraft.value !== (activity.value?.proposal ?? '')
}

async function saveProposalDraft() {
  await guard(async () => {
    await saveProposal(orgId.value, activityId.value, proposalDraft.value, currentVersion())
    proposalDirty.value = false
    await loadActivity()
  })
}

async function submitForApproval() {
  try {
    await ElMessageBox.confirm('提交后活动将进入待确认状态，确认前可撤回。', '提交确认', {
      confirmButtonText: '提交',
      cancelButtonText: '取消',
      type: 'info',
    })
  } catch {
    return
  }
  await guard(async () => {
    await submitActivity(orgId.value, activityId.value)
    await loadActivity()
  })
}

async function withdrawSubmission() {
  try {
    await ElMessageBox.confirm('撤回后活动回到草稿状态。', '撤回提交', {
      confirmButtonText: '撤回',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  await guard(async () => {
    await withdrawActivity(orgId.value, activityId.value)
    await loadActivity()
  })
}

async function approve() {
  await guard(async () => {
    await approveActivity(orgId.value, activityId.value, currentVersion())
    await loadActivity()
  })
}

function openReject() {
  rejectReason.value = ''
  rejectVisible.value = true
}

async function confirmReject() {
  if (!rejectReason.value.trim()) {
    return
  }
  rejectSubmitting.value = true
  await guard(async () => {
    await rejectActivity(orgId.value, activityId.value, rejectReason.value.trim(), currentVersion())
    rejectVisible.value = false
    await loadActivity()
  })
  rejectSubmitting.value = false
}

function openEdit() {
  if (!activity.value) {
    return
  }
  editForm.name = activity.value.name
  editForm.description = activity.value.description ?? ''
  editForm.startTime = toLocalInput(activity.value.startTime)
  editForm.endTime = toLocalInput(activity.value.endTime)
  editForm.location = activity.value.location ?? ''
  editForm.budget = activity.value.budget
  editVisible.value = true
}

function toLocalInput(value: string | null): string {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function toIso(local: string): string {
  if (!local) {
    return ''
  }
  // datetime-local 值按本机时区（Asia/Shanghai）解释为带偏移时间点
  return `${local}:00+08:00`
}

async function submitEdit() {
  editSubmitting.value = true
  await guard(async () => {
    await updateActivity(orgId.value, activityId.value, currentVersion(), {
      name: editForm.name.trim(),
      description: editForm.description.trim() || undefined,
      startTime: toIso(editForm.startTime) || undefined,
      endTime: toIso(editForm.endTime) || undefined,
      location: editForm.location.trim() || undefined,
      budget: editForm.budget || undefined,
    })
    editVisible.value = false
    await loadActivity()
  })
  editSubmitting.value = false
}

async function withdrawConfirmed() {
  try {
    await ElMessageBox.confirm(
      '撤回后活动回到草稿状态，可修改关键内容后重新提交确认。', '撤回确认',
      { confirmButtonText: '撤回', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  await guard(async () => {
    await withdrawApproval(orgId.value, activityId.value, currentVersion(), '关键内容变更')
    await loadActivity()
  })
}

async function confirmDelete() {
  const targetId = activityId.value
  try {
    await ElMessageBox.confirm('确定删除该草稿？删除后不可恢复。', '删除草稿', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  await guard(async () => {
    if (activityId.value !== targetId) {
      return
    }
    await deleteActivityDraft(orgId.value, targetId)
    await router.replace(`/org/${orgId.value}/activities`)
  })
}
</script>

<template>
  <div class="activity-detail">
    <div class="page-heading">
      <div class="page-heading__title">
        <p class="page-eyebrow">
          组织协作 / 活动详情
        </p>
        <h1>{{ activity?.name ?? (loading ? '加载中……' : '活动不可访问') }}</h1>
        <p
          v-if="activity"
          class="page-description"
        >
          <ElTag :type="activity.status === 'APPROVED' ? 'success' : activity.status === 'PENDING_APPROVAL' ? 'warning' : 'info'">
            {{ statusLabel }}
          </ElTag>
          <span
            v-if="activity.reviewNote"
            class="detail-review-note"
          >审批说明：{{ activity.reviewNote }}</span>
        </p>
      </div>
      <div class="detail-actions">
        <ElButton
          v-if="activity?.status === 'DRAFT'"
          @click="openEdit"
        >
          编辑信息
        </ElButton>
        <ElButton
          v-if="activity?.status === 'DRAFT'"
          type="primary"
          @click="submitForApproval"
        >
          提交确认
        </ElButton>
        <ElButton
          v-if="activity?.status === 'PENDING_APPROVAL'"
          @click="withdrawSubmission"
        >
          撤回提交
        </ElButton>
        <ElButton
          v-if="activity?.status === 'PENDING_APPROVAL'"
          type="primary"
          @click="approve"
        >
          通过
        </ElButton>
        <ElButton
          v-if="activity?.status === 'PENDING_APPROVAL'"
          type="warning"
          plain
          @click="openReject"
        >
          驳回
        </ElButton>
        <ElButton
          v-if="activity?.status === 'APPROVED'"
          type="warning"
          plain
          @click="withdrawConfirmed"
        >
          撤回确认
        </ElButton>
        <ElButton
          v-if="activity?.status === 'DRAFT'"
          type="danger"
          plain
          @click="confirmDelete"
        >
          删除草稿
        </ElButton>
      </div>
    </div>

    <p
      v-if="loading"
      class="detail-note"
      aria-live="polite"
    >
      正在加载活动信息……
    </p>
    <section
      v-else-if="activity"
      class="detail-body"
    >
      <p
        v-if="actionError"
        class="detail-error"
        role="alert"
      >
        {{ actionError }}
      </p>
      <ActivityP3Sections
        v-if="activity.status !== 'DRAFT'"
        :org-detail="activity"
        :org-role="orgRole"
        @changed="loadActivity"
      />

      <RegistrationPanel
        v-if="activity.status === 'APPROVED'"
        :org-detail="activity"
        :org-role="orgRole"
        @changed="loadActivity"
      />

      <!-- P5：经费区块（组织负责人或本活动负责人可见，权限仍由后端判定） -->
      <ExpenseSection
        v-if="activity.status !== 'DRAFT'"
        :org-detail="activity"
        :org-role="orgRole"
        @changed="loadActivity"
      />

      <dl class="detail-grid">
        <div><dt>活动简介</dt><dd>{{ activity.description || '暂无' }}</dd></div>
        <div><dt>活动负责人</dt><dd>{{ activity.ownerUsername }}</dd></div>
        <div><dt>开始时间</dt><dd>{{ formatDateTime(activity.startTime) }}</dd></div>
        <div><dt>结束时间</dt><dd>{{ formatDateTime(activity.endTime) }}</dd></div>
        <div><dt>地点</dt><dd>{{ activity.location || '待定' }}</dd></div>
        <div>
          <dt>预算（元）</dt><dd class="detail-amount">
            {{ activity.budget }}
          </dd>
        </div>
        <div><dt>报名范围</dt><dd>{{ scopeLabel(activity.registrationScope) }}</dd></div>
        <div><dt>名额</dt><dd>{{ activity.capacity ?? '—' }}</dd></div>
      </dl>

      <section class="proposal-section">
        <div class="proposal-section__head">
          <h2>活动方案</h2>
          <ElButton
            v-if="activity.status === 'DRAFT'"
            size="small"
            type="primary"
            :loading="proposalSaving"
            :disabled="!proposalDirty"
            @click="saveProposalDraft"
          >
            保存方案
          </ElButton>
        </div>
        <ElInput
          v-if="activity.status === 'DRAFT'"
          v-model="proposalDraft"
          type="textarea"
          :rows="10"
          @input="onProposalInput"
        />
        <p
          v-else
          class="proposal-readonly"
        >
          {{ activity.proposal || '未填写方案' }}
        </p>
      </section>
    </section>
    <p
      v-else
      class="detail-note"
      role="alert"
    >
      {{ loadError }}
    </p>

    <ElDialog
      v-model="editVisible"
      title="编辑活动信息"
      width="min(100%, var(--ui-drawer-width))"
    >
      <ElForm
        :model="editForm"
        label-position="top"
      >
        <ElFormItem
          label="活动主题"
          required
        >
          <ElInput v-model="editForm.name" />
        </ElFormItem>
        <ElFormItem label="活动简介">
          <ElInput
            v-model="editForm.description"
            type="textarea"
            :rows="3"
          />
        </ElFormItem>
        <ElFormItem label="开始时间">
          <ElInput
            v-model="editForm.startTime"
            type="datetime-local"
          />
        </ElFormItem>
        <ElFormItem label="结束时间">
          <ElInput
            v-model="editForm.endTime"
            type="datetime-local"
          />
        </ElFormItem>
        <ElFormItem label="地点">
          <ElInput v-model="editForm.location" />
        </ElFormItem>
        <ElFormItem label="预算（元）">
          <ElInput v-model="editForm.budget" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="editVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="editSubmitting"
          @click="submitEdit"
        >
          保存
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="rejectVisible"
      title="驳回活动"
      width="var(--ui-dialog-width)"
    >
      <ElForm
        label-position="top"
        @submit.prevent="confirmReject"
      >
        <ElFormItem
          label="驳回原因（必填）"
          required
        >
          <ElInput
            v-model="rejectReason"
            type="textarea"
            :rows="3"
            placeholder="说明需要补充或修改的内容"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="rejectVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="rejectSubmitting"
          @click="confirmReject"
        >
          确认驳回
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.activity-detail {
  min-width: 0;
}

.page-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ui-space-6);
  flex-wrap: wrap;
}

.page-heading__title {
  min-width: 0;
}

.page-eyebrow {
  margin-bottom: var(--ui-space-3);
  color: var(--ui-color-selected-text);
  font-size: var(--ui-font-size-caption);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
}

.page-heading h1 {
  font-size: var(--ui-font-size-page-title);
  font-weight: var(--ui-font-weight-bold);
  letter-spacing: var(--ui-letter-spacing-title);
}

.page-description {
  margin-top: var(--ui-space-3);
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  flex-wrap: wrap;
}

.detail-review-note {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}

.detail-actions {
  display: flex;
  gap: var(--ui-space-3);
  flex-wrap: wrap;
}

.detail-note {
  margin-top: var(--ui-space-6);
  color: var(--ui-color-text-secondary);
}

.detail-error {
  margin-bottom: var(--ui-space-4);
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.detail-body {
  margin-top: var(--ui-space-8);
}

.detail-grid {
  display: grid;
  grid-template-columns: var(--ui-layout-form-columns);
  gap: var(--ui-space-6);
  margin: 0;
}

.detail-grid dt {
  margin-bottom: var(--ui-space-2);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.detail-grid dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.detail-amount {
  font-variant-numeric: tabular-nums;
}

.proposal-section {
  margin-top: var(--ui-space-10);
}

.proposal-section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  margin-bottom: var(--ui-space-4);
}

.proposal-section__head h2 {
  font-size: var(--ui-font-size-section);
  font-weight: var(--ui-font-weight-semibold);
}

.proposal-readonly {
  white-space: pre-wrap;
  line-height: var(--ui-line-height-prose);
  color: var(--ui-color-text);
}
</style>
