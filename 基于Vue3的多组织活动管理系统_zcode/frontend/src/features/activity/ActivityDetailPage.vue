<script setup lang="ts">
import { Grid, Document, List, Tickets, Wallet, DataAnalysis } from '@element-plus/icons-vue'
import DocumentReadView from './DocumentReadView.vue'
import { focusDraftEditor } from '../../shared/utils/focusDraftEditor'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import {
  ElButton,
  ElAlert,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessageBox,
  ElSkeleton,
  ElSkeletonItem,
  ElTabPane,
  ElTabs,
  ElTag,
  type FormInstance,
  type FormRules,
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
import ActivityLifecyclePanel from './ActivityLifecyclePanel.vue'
import AiDraftPanel from '../ai/AiDraftPanel.vue'
import { confirmDraftSave } from './confirmDraftSave'

const props = defineProps<{ orgDetail: { id: string; role?: 'OWNER' | 'MEMBER' } | null }>()

/** 本人在当前组织的角色（由组织布局下发），用于决定 P3 区块按钮展示；权限仍由后端判定。 */
const orgRole = computed(() => props.orgDetail?.role ?? null)
const isOrgOwner = computed(() => orgRole.value === 'OWNER')

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

/** 详情页签：功能按业务域分组，首屏只保留状态、关键信息与主操作。 */
const activeTab = computed({
  get: () => allowedTabs.value.includes(String(route.query.tab)) ? String(route.query.tab) : 'overview',
  set: (tab: string) => { void router.push({ query: { ...route.query, tab } }) },
})
const statusHints: Record<string, string> = {
  DRAFT: '完善信息与方案后提交确认',
  PENDING_APPROVAL: '等待组织负责人确认或驳回',
  APPROVED: '筹备中：分配任务、准备物资、审核报名',
  EXECUTING: '执行中：记录进展与问题，结束后进入复盘',
  REVIEWING: '复盘中：收集反馈、编写并确认总结',
  ARCHIVED: '已归档：只读留档，如需更正先重新打开',
  CANCELLED: '已取消：处理遗留事项后可归档',
}
const statusHint = computed(() => statusHints[activity.value?.status ?? ''] ?? '')

/** 当前状态与权限下可见的页签（与模板 v-if 一致），用于校验 ?tab= 目标。 */
const allowedTabs = computed(() => {
  const status = activity.value?.status ?? 'DRAFT'
  const tabs = ['overview', 'proposal']
  if (status !== 'DRAFT') {
    tabs.push('tasks', 'expense')
  }
  if (status === 'APPROVED') {
    tabs.push('registration')
  }
  if ((status !== 'DRAFT' && status !== 'PENDING_APPROVAL') || orgRole.value === 'OWNER') {
    tabs.push('lifecycle')
  }
  return tabs
})

const proposalEditor = ref<HTMLElement>()
const proposalApplied = ref(false)
const proposalDraft = ref('')
const proposalDirty = ref(false)
let proposalVersion = 0
const proposalSaving = ref(false)

const editVisible = ref(false)
const editSubmitting = ref(false)
const p3Panel = ref<InstanceType<typeof ActivityP3Sections> | null>(null)
const lifecyclePanel = ref<InstanceType<typeof ActivityLifecyclePanel> | null>(null)
const registrationPanel = ref<InstanceType<typeof RegistrationPanel> | null>(null)
const expensePanel = ref<InstanceType<typeof ExpenseSection> | null>(null)
const planAiPanel = ref<InstanceType<typeof AiDraftPanel> | null>(null)
const editFormRef = ref<FormInstance>()
const editRules: FormRules = {
  name: [{ required: true, whitespace: true, message: '请输入活动主题', trigger: 'blur' }, { max: 128, message: '活动主题不能超过 128 字', trigger: 'blur' }],
  description: [{ max: 2000, message: '活动简介不能超过 2000 字', trigger: 'blur' }],
  budget: [{ pattern: /^\d+(?:\.\d{1,2})?$/, message: '请输入非负金额，最多两位小数', trigger: 'blur' }],
}

const editForm = reactive({
  name: '',
  description: '',
  startTime: '',
  endTime: '',
  location: '',
  budget: '0.00',
})
let initialEditForm = ''
let editVersion = 0
const editDirty = computed(() => editVisible.value && JSON.stringify(editForm) !== initialEditForm)

const rejectVisible = ref(false)
const rejectReason = ref('')
let rejectVersion = 0
const rejectSubmitting = ref(false)

const acting = ref(false)

const statusLabel = computed(() => {
  const map = { DRAFT: '草稿', PENDING_APPROVAL: '待确认', APPROVED: '筹备中', EXECUTING: '执行中', REVIEWING: '复盘中', ARCHIVED: '已归档', CANCELLED: '已取消' } as const
  return activity.value ? map[activity.value.status as keyof typeof map] ?? activity.value.status : ''
})

let requestSeq = 0
let alive = true
const permissionGeneration = ref(0)

function clearManagementDrafts(): void {
  proposalApplied.value = false
  proposalDraft.value = ''
  proposalDirty.value = false
  editVisible.value = false
  Object.assign(editForm, { name: '', description: '', startTime: '', endTime: '', location: '', budget: '0.00' })
  rejectVisible.value = false
  rejectReason.value = ''
  permissionGeneration.value++
}

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
    if (activity.value && !isOrgOwner.value && activity.value.ownerUsername === session.account?.username
      && activity.value.ownerMemberId !== detail.ownerMemberId) {
      clearManagementDrafts()
    }
    activity.value = detail
    if (!proposalDirty.value) {
      proposalDraft.value = detail.proposal ?? ''
      proposalVersion = detail.version
    }
    // R02：工作台 AI 卡等入口可指定页签；状态或权限不允许时回到概览，不静默停在无关页签
    const requestedTab = String(route.query.tab ?? '')
    if (requestedTab && !allowedTabs.value.includes(requestedTab)) {
      await router.replace({ query: { ...route.query, tab: 'overview' } })
    }
  } catch (error) {
    if (seq !== requestSeq || targetId !== activityId.value || gen !== session.generation) {
      return
    }
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    if (error instanceof ApiError && !error.isTransportError && [403, 404].includes(error.status)) {
      activity.value = null
      clearManagementDrafts()
      loadError.value = '活动不存在或无权访问。'
    } else {
      loadError.value = activity.value
        ? '活动权限核对失败，请重试。'
        : '活动信息加载失败，请检查网络后重试。'
    }
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

watch([orgId, activityId], () => {
  activity.value = null
  clearManagementDrafts()
  void loadActivity()
})
// 页签间保留已挂载编辑器；离开活动时才会丢失编辑内容。
async function confirmLeave(): Promise<boolean> {
  if (editSubmitting.value || proposalSaving.value || rejectSubmitting.value || p3Panel.value?.isSubmitting() || expensePanel.value?.isSubmitting() || lifecyclePanel.value?.isSummarySubmitting()
    || planAiPanel.value?.isGenerating() || registrationPanel.value?.isSubmitting()
    || lifecyclePanel.value?.isFeedbackSubmitting() || lifecyclePanel.value?.isRecordSubmitting()) return false
  if (!proposalDirty.value && !editDirty.value && !p3Panel.value?.hasUnsavedForm() && !expensePanel.value?.hasUnsavedForm()
    && !planAiPanel.value?.hasPendingGeneration() && !registrationPanel.value?.hasUnsavedForm()
    && !lifecyclePanel.value?.hasUnsavedSummary() && !lifecyclePanel.value?.hasUnsavedFeedback()
    && !lifecyclePanel.value?.hasUnsavedRecord()) return true
  try {
    await ElMessageBox.confirm(lifecyclePanel.value?.hasPendingRecord()
      ? '记录结果尚未确认。离开后请先核对执行记录列表，避免重复追加。确定离开？'
      : '有尚未保存的编辑内容，确定离开当前活动？', '未保存的修改', {
      confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning',
    })
    return true
  } catch { return false }
}
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate((to, from) => to.path === from.path ? true : confirmLeave())
function beforeUnload(event: BeforeUnloadEvent) {
  if (proposalDirty.value || proposalSaving.value || editDirty.value || editSubmitting.value || rejectSubmitting.value
    || planAiPanel.value?.hasPendingGeneration() || planAiPanel.value?.isGenerating()
    || registrationPanel.value?.hasUnsavedForm() || registrationPanel.value?.isSubmitting()
    || p3Panel.value?.hasUnsavedForm() || p3Panel.value?.isSubmitting()
    || expensePanel.value?.hasUnsavedForm() || expensePanel.value?.isSubmitting()
    || lifecyclePanel.value?.hasUnsavedSummary() || lifecyclePanel.value?.isSummarySubmitting()
    || lifecyclePanel.value?.hasUnsavedFeedback() || lifecyclePanel.value?.isFeedbackSubmitting()
    || lifecyclePanel.value?.hasUnsavedRecord() || lifecyclePanel.value?.isRecordSubmitting()) {
    event.preventDefault()
    event.returnValue = ''
  }
}
function checkActivityPermissions(): void {
  if (!document.hidden && session.status === 'authenticated') void loadActivity()
}
onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  document.addEventListener('visibilitychange', checkActivityPermissions)
})
onBeforeUnmount(() => {
  alive = false
  window.removeEventListener('beforeunload', beforeUnload)
  document.removeEventListener('visibilitychange', checkActivityPermissions)
  requestSeq++
})
onMounted(loadActivity)

function captureAction() {
  return { orgId: orgId.value, activityId: activityId.value, generation: session.capture(), permissions: permissionGeneration.value, version: currentVersion() }
}

function isActionCurrent(context: ReturnType<typeof captureAction>): boolean {
  return alive && context.orgId === orgId.value && context.activityId === activityId.value
    && context.generation === session.generation && context.permissions === permissionGeneration.value
}

async function guard(fn: () => Promise<void>, context = captureAction()): Promise<void> {
  if (!isActionCurrent(context)) return
  actionError.value = ''
  acting.value = true
  try {
    await fn()
  } catch (error) {
    if (session.observeUnauthorized(error, context.generation)) {
      return
    }
    if (!isActionCurrent(context)) return
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

/** P8.3：采用 AI 草稿只替换编辑区内容，保存仍由用户点击“保存方案”。 */
function applyProposalDraft(draft: string) {
  proposalDraft.value = draft
  onProposalInput()
  proposalApplied.value = true
  void focusDraftEditor(proposalEditor.value)
}

async function saveProposalDraft() {
  if (proposalSaving.value) return
  proposalSaving.value = true
  const version = await confirmDraftSave(proposalVersion, currentVersion())
  if (version === null) {
    proposalSaving.value = false
    return
  }
  await guard(async () => {
    await saveProposal(orgId.value, activityId.value, proposalDraft.value, version)
    proposalDirty.value = false
    await loadActivity()
  })
  proposalSaving.value = false
}

async function submitForApproval() {
  const context = captureAction()
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
    await submitActivity(context.orgId, context.activityId)
    await loadActivity()
  }, context)
}

async function withdrawSubmission() {
  const context = captureAction()
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
    await withdrawActivity(context.orgId, context.activityId)
    await loadActivity()
  }, context)
}

async function approve() {
  await guard(async () => {
    await approveActivity(orgId.value, activityId.value, currentVersion())
    await loadActivity()
  })
}

function openReject() {
  actionError.value = ''
  rejectVersion = currentVersion()
  rejectReason.value = ''
  rejectVisible.value = true
}

async function confirmReject() {
  if (!rejectReason.value.trim()) {
    return
  }
  rejectSubmitting.value = true
  await guard(async () => {
    await rejectActivity(orgId.value, activityId.value, rejectReason.value.trim(), rejectVersion)
    rejectVisible.value = false
    await loadActivity()
  })
  rejectSubmitting.value = false
}

function openEdit() {
  if (!activity.value) {
    return
  }
  actionError.value = ''
  editForm.name = activity.value.name
  editForm.description = activity.value.description ?? ''
  editForm.startTime = toLocalInput(activity.value.startTime)
  editForm.endTime = toLocalInput(activity.value.endTime)
  editForm.location = activity.value.location ?? ''
  editForm.budget = activity.value.budget
  initialEditForm = JSON.stringify(editForm)
  editVersion = currentVersion()
  editVisible.value = true
}

async function closeEdit(done: () => void = () => { editVisible.value = false }) {
  if (editSubmitting.value) return
  if (editDirty.value) {
    try {
      await ElMessageBox.confirm('编辑内容还未保存，确定放弃吗？', '未保存的修改', {
        confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning',
      })
    } catch { return }
  }
  done()
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
  if (!(await editFormRef.value?.validate().catch(() => false))) return
  if (editSubmitting.value) return
  editSubmitting.value = true
  const version = await confirmDraftSave(editVersion, currentVersion())
  if (version === null) {
    editSubmitting.value = false
    return
  }
  await guard(async () => {
    await updateActivity(orgId.value, activityId.value, version, {
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
  const context = captureAction()
  try {
    await ElMessageBox.confirm(
      '撤回后活动回到草稿状态，可修改关键内容后重新提交确认。', '撤回确认',
      { confirmButtonText: '撤回', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  await guard(async () => {
    await withdrawApproval(context.orgId, context.activityId, context.version, '关键内容变更')
    await loadActivity()
  }, context)
}

async function confirmDelete() {
  const context = captureAction()
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
    await deleteActivityDraft(context.orgId, targetId)
    await router.replace(`/org/${context.orgId}/activities`)
  }, context)
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
          v-if="activity?.status === 'PENDING_APPROVAL' && (isOrgOwner || String(activity.submittedBy) === session.account?.id)"
          @click="withdrawSubmission"
        >
          撤回提交
        </ElButton>
        <ElButton
          v-if="activity?.status === 'PENDING_APPROVAL' && isOrgOwner"
          type="primary"
          @click="approve"
        >
          通过
        </ElButton>
        <ElButton
          v-if="activity?.status === 'PENDING_APPROVAL' && isOrgOwner"
          type="warning"
          plain
          @click="openReject"
        >
          驳回
        </ElButton>
        <ElButton
          v-if="activity?.status === 'APPROVED' && isOrgOwner"
          type="warning"
          plain
          @click="withdrawConfirmed"
        >
          撤回确认
        </ElButton>
        <ElButton
          v-if="activity?.status === 'DRAFT' && isOrgOwner"
          type="danger"
          plain
          @click="confirmDelete"
        >
          删除草稿
        </ElButton>
      </div>
    </div>

    <section
      v-if="loading && !activity"
      class="detail-skeleton"
      role="status"
      aria-busy="true"
      aria-label="正在加载活动信息"
    >
      <ElSkeleton
        animated
        :rows="0"
      >
        <template #template>
          <ElSkeletonItem
            variant="h1"
            class="detail-skeleton__title"
          />
          <div class="detail-skeleton__grid">
            <ElSkeletonItem
              v-for="n in 4"
              :key="n"
              variant="rect"
            />
          </div>
          <ElSkeletonItem variant="rect" />
        </template>
      </ElSkeleton>
    </section>
    <section
      v-else-if="activity"
      :key="permissionGeneration"
      class="detail-body"
    >
      <ElAlert
        v-if="loadError"
        type="warning"
        :title="loadError"
        :closable="false"
      >
        <ElButton @click="loadActivity()">
          重新核对活动权限
        </ElButton>
      </ElAlert>
      <p
        v-if="actionError"
        class="detail-error"
        role="alert"
      >
        {{ actionError }}
      </p>

      <!-- 详情首屏：状态与下一步 + 关键信息，功能按业务域进入页签，不再纵向堆叠 -->
      <p
        v-if="loading"
        class="detail-note"
        role="status"
      >
        正在更新活动信息…
      </p>
      <section class="detail-status">
        <div>
          <p class="detail-status__label">
            当前状态
          </p>
          <strong>{{ statusLabel }}</strong>
          <span class="detail-status__hint">{{ statusHint }}</span>
          <div
            class="detail-shortcuts"
            aria-label="活动快捷入口"
          >
            <ElButton
              v-if="allowedTabs.includes('tasks')"
              size="small"
              type="primary"
              plain
              @click="activeTab = 'tasks'"
            >
              查看任务与物资
            </ElButton>
            <ElButton
              v-if="allowedTabs.includes('lifecycle')"
              size="small"
              @click="activeTab = 'lifecycle'"
            >
              执行与复盘
            </ElButton>
          </div>
        </div>
        <dl class="detail-status__facts">
          <div>
            <dt>负责人</dt>
            <dd>{{ activity.ownerUsername }}</dd>
          </div>
          <div>
            <dt>开始</dt>
            <dd>{{ formatDateTime(activity.startTime) }}</dd>
          </div>
          <div>
            <dt>地点</dt>
            <dd>{{ activity.location || '待定' }}</dd>
          </div>
          <div>
            <dt>预算（元）</dt>
            <dd class="detail-amount">
              {{ activity.budget }}
            </dd>
          </div>
        </dl>
      </section>

      <ElTabs
        v-model="activeTab"
        class="detail-tabs"
      >
        <ElTabPane
          lazy
          label="概览"
          name="overview"
        >
          <template #label>
            <span class="detail-tab-label"><Grid aria-hidden="true" />概览</span>
          </template>
          <section class="detail-intro">
            <h2>活动安排</h2>
            <p>{{ activity.description || '暂无活动简介' }}</p>
          </section>
          <dl class="detail-grid">
            <div><dt>结束时间</dt><dd>{{ formatDateTime(activity.endTime) }}</dd></div>
            <div><dt>报名范围</dt><dd>{{ scopeLabel(activity.registrationScope) }}</dd></div>
            <div><dt>名额</dt><dd>{{ activity.capacity ?? '—' }}</dd></div>
            <div><dt>报名开关</dt><dd>{{ activity.registrationOpen ? '已开放' : '未开放' }}</dd></div>
          </dl>
        </ElTabPane>

        <ElTabPane
          lazy
          label="方案"
          name="proposal"
        >
          <template #label>
            <span class="detail-tab-label"><Document aria-hidden="true" />方案</span>
          </template>
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
            <div
              class="document-workspace"
              :class="{ 'document-workspace--reading': activity.status !== 'DRAFT' }"
            >
              <div
                ref="proposalEditor"
                class="document-workspace__main"
              >
                <p
                  v-if="proposalApplied"
                  class="draft-apply-feedback"
                  role="status"
                >
                  {{ proposalDirty ? 'AI 草稿已放入正文，请审阅并保存。' : '正文与已保存内容一致。' }}
                </p>
                <ElInput
                  v-if="activity.status === 'DRAFT'"
                  v-model="proposalDraft"
                  type="textarea"
                  :rows="10"
                  :disabled="proposalSaving"
                  @input="onProposalInput"
                />
                <DocumentReadView
                  v-else
                  :text="activity.proposal || ''"
                />
              </div>
              <!-- P8.3：方案生成的草稿只进入上面的编辑区，保存与确认仍走原有流程 -->
              <AiDraftPanel
                v-if="activity.status === 'DRAFT'"
                ref="planAiPanel"
                :org-id="orgId"
                :activity-id="activityId"
                type="ACTIVITY_PLAN"
                :disabled="proposalSaving"
                @apply="applyProposalDraft"
              />
            </div>
          </section>
        </ElTabPane>

        <ElTabPane
          v-if="activity.status !== 'DRAFT'"
          lazy
          label="任务与物资"
          name="tasks"
        >
          <template #label>
            <span class="detail-tab-label"><List aria-hidden="true" />任务与物资</span>
          </template>
          <ActivityP3Sections
            ref="p3Panel"
            :org-detail="activity"
            :org-role="orgRole"
            @changed="loadActivity"
          />
        </ElTabPane>

        <ElTabPane
          v-if="activity.status === 'APPROVED'"
          lazy
          label="报名"
          name="registration"
        >
          <template #label>
            <span class="detail-tab-label"><Tickets aria-hidden="true" />报名</span>
          </template>
          <RegistrationPanel
            ref="registrationPanel"
            :org-detail="activity"
            :org-role="orgRole"
            @changed="loadActivity"
          />
        </ElTabPane>

        <ElTabPane
          v-if="activity.status !== 'DRAFT'"
          lazy
          label="经费"
          name="expense"
        >
          <template #label>
            <span class="detail-tab-label"><Wallet aria-hidden="true" />经费</span>
          </template>
          <ExpenseSection
            ref="expensePanel"
            :org-detail="activity"
            :org-role="orgRole"
            @changed="loadActivity"
          />
        </ElTabPane>

        <ElTabPane
          v-if="allowedTabs.includes('lifecycle')"
          lazy
          label="执行与复盘"
          name="lifecycle"
        >
          <template #label>
            <span class="detail-tab-label"><DataAnalysis aria-hidden="true" />执行与复盘</span>
          </template>
          <!-- P6：执行、取消、执行记录、反馈、总结与归档；P8.2 总结生成在总结区块内 -->
          <ActivityLifecyclePanel
            ref="lifecyclePanel"
            :org-detail="activity"
            :org-role="orgRole"
            @changed="loadActivity"
          />
        </ElTabPane>
      </ElTabs>
    </section>
    <p
      v-else
      class="detail-note"
      role="alert"
    >
      <span>{{ loadError }}</span>
      <ElButton @click="loadActivity()">
        重新加载
      </ElButton>
    </p>

    <ElDialog
      v-model="editVisible"
      title="编辑活动信息"
      width="min(100%, var(--ui-drawer-width))"
      :before-close="closeEdit"
    >
      <ElAlert
        v-if="actionError"
        :title="actionError"
        type="error"
        :closable="false"
      />
      <ElForm
        ref="editFormRef"
        :model="editForm"
        :rules="editRules"
        :disabled="editSubmitting"
        label-position="top"
      >
        <ElFormItem
          label="活动主题"
          prop="name"
          required
        >
          <ElInput v-model="editForm.name" />
        </ElFormItem>
        <ElFormItem
          label="活动简介"
          prop="description"
        >
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
        <ElFormItem
          label="预算（元）"
          prop="budget"
        >
          <ElInput v-model="editForm.budget" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="closeEdit()">
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
      <ElAlert
        v-if="actionError"
        :title="`${actionError} 请关闭弹窗，复核最新方案后重新打开。`"
        type="error"
        :closable="false"
      />
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
  margin-top: var(--ui-space-5);
}

.detail-shortcuts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-2);
  margin-top: var(--ui-space-4);
}

.detail-shortcuts .el-button + .el-button {
  margin-left: 0;
}

.detail-intro {
  margin-bottom: var(--ui-space-6);
}

.detail-intro h2 {
  margin: 0 0 var(--ui-space-2);
  font-size: var(--ui-font-size-emphasis);
}

.detail-intro p {
  margin: 0;
  color: var(--ui-color-text-secondary);
  line-height: var(--ui-line-height-prose);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* 详情首屏：状态与下一步 + 关键事实，功能进入页签后不再纵向堆叠 */

.detail-status__label {
  margin: 0;
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
}

.detail-status strong {
  display: block;
  margin: var(--ui-space-1) 0;
  font-size: var(--ui-font-size-section);
}

.detail-status__hint {
  color: var(--ui-color-text-secondary);
  font-size: var(--ui-font-size-small);
}

.detail-status__facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(var(--ui-detail-fact-min-width), 1fr));
  gap: var(--ui-space-4);
  margin: 0;
}

.detail-status__facts dt {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.detail-skeleton {
  margin-top: var(--ui-space-8);
}

.detail-skeleton__title {
  margin-bottom: var(--ui-space-4);
}

.detail-skeleton__grid {
  display: grid;
  grid-template-columns: var(--ui-layout-kpi-columns);
  gap: var(--ui-layout-grid-gap);
  margin-bottom: var(--ui-space-4);
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
  margin-top: 0;
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


.detail-tabs {
  margin-top: var(--ui-layout-grid-gap);
  background: var(--ui-color-surface);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  padding: var(--ui-layout-card-padding);
  box-shadow: var(--ui-shadow-glass);
}

.detail-tabs :deep(.el-tabs__header) {
  margin-bottom: var(--ui-space-6);
}

.detail-tabs :deep(.el-tabs__item) {
  font-size: var(--ui-font-size-body);
  min-height: var(--ui-control-min-touch);
}

.detail-status {
  display: grid;
  grid-template-columns: var(--ui-detail-status-columns);
  gap: var(--ui-layout-grid-gap);
  align-items: center;
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
  box-shadow: var(--ui-shadow-glass);
  border-top: var(--ui-space-1) solid var(--ui-color-accent);
}

.detail-status__facts dd {
  margin: var(--ui-space-1) 0 0;
  font-size: var(--ui-font-size-small);
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
  font-family: var(--ui-font-family-body);
}

.detail-grid > div {
  padding-bottom: var(--ui-space-4);
  border-bottom: var(--ui-border-width) solid var(--ui-color-border);
}

.detail-skeleton__grid :deep(.el-skeleton__item) {
  border-radius: var(--ui-radius-card);
  height: var(--ui-workbench-kpi-min-height);
}


.document-workspace--reading { grid-template-columns: minmax(0, 1fr); }
.detail-tab-label { display: inline-flex; align-items: center; gap: var(--ui-space-2); }
.detail-tab-label svg { width: var(--ui-icon-small); height: var(--ui-icon-small); }
.detail-tabs :deep(.el-tabs__nav-wrap::after), .detail-tabs :deep(.el-tabs__active-bar) { display: none; }
.detail-tabs :deep(.el-tabs__nav) { gap: var(--ui-space-2); }
/* 覆盖 Element Plus 对首项／末项的零内边距，保证所有分区按钮留白一致。 */
.detail-tabs.el-tabs--top :deep(.el-tabs__header .el-tabs__item.is-top) { padding: var(--ui-space-3) var(--ui-space-5); border-radius: var(--ui-radius-control); background: var(--ui-color-canvas); border: var(--ui-border-width) solid transparent; }
.detail-tabs.el-tabs--top :deep(.el-tabs__header .el-tabs__item.is-top.is-active) { color: var(--ui-color-selected-text); border-color: var(--ui-color-action-tint-7); background: var(--ui-color-selected-bg); font-weight: var(--ui-font-weight-semibold); }
.detail-tabs :deep(.el-tabs__content) { padding-top: var(--ui-space-2); }
.detail-intro { padding: var(--ui-space-5); border-radius: var(--ui-radius-control); background: var(--ui-color-canvas); margin-bottom: var(--ui-space-6); }
</style>
