<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessageBox, ElRate, ElSkeleton, ElTag } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { ApiError, readErrorMessage } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import {
  applyBlockReason, fetchPublicActivity, fetchPublicFeedback, submitPublicFeedback,
  type PublicActivityItem, type PublicFeedbackSummary,
} from './publicActivity'
import {
  applyRegistration, cancelMyRegistration, fetchMyRegistrations, isCancellable,
  registrationStatusLabel, type RegistrationItem,
} from './registrations'
import { formatDateTime, scopeLabel } from './types'

// 公开活动详情（P4.1／F01）：登录后在本页直接报名、更新与取消，不要求外部参与者加入组织。
// 未登录时仍可只读浏览；页面只消费公开端点与本人报名接口，不出现任何内部字段。
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const activityId = computed(() => String(route.params.activityId ?? ''))
const item = ref<PublicActivityItem | null>(null)
const mine = ref<RegistrationItem | null>(null)
const loading = ref(true)
const errorText = ref('')
const missing = ref(false)
const mineLoading = ref(false)
const mineError = ref('')
const actionError = ref('')
const acting = ref(false)

const applyVisible = ref(false)
const applySubmitting = ref(false)
const applyForm = ref({ displayName: '', note: '' })
const applyInitial = ref({ displayName: '', note: '' })
let applyVersion = 0
const applyError = ref('')
const applyDirty = computed(() => applyVisible.value && (
  applyForm.value.displayName !== applyInitial.value.displayName
  || applyForm.value.note !== applyInitial.value.note
))
/** F10：报名表单失焦即校验，提交前再整体校验一次（与后端 1-64 字符规则一致）。 */
const applyFormRef = ref<FormInstance | null>(null)
const applyRules: FormRules = {
  displayName: [
    { required: true, message: '请填写展示姓名', trigger: 'blur' },
    { min: 1, max: 64, message: '展示姓名 1-64 字符', trigger: 'blur' },
  ],
  note: [{ max: 500, message: '备注不超过 500 字符', trigger: 'blur' }],
}

let requestSeq = 0
let alive = true
let actionGeneration = 0
watch([activityId, () => session.generation], () => {
  actionGeneration++
  acting.value = false
  applySubmitting.value = false
  feedbackSubmitting.value = false
  applyVisible.value = false
  mine.value = null
  mineError.value = ''
  feedback.value = null
  feedbackReadError.value = ''
  feedbackRating.value = 0
  feedbackComment.value = ''
}, { flush: 'sync' })

// P9.1：外部参与者的本人反馈（复盘中可提交，不必加入组织）
const feedback = ref<PublicFeedbackSummary | null>(null)
const feedbackRating = ref(0)
let feedbackVersion = 0
const feedbackConflict = ref(false)
const feedbackComment = ref('')
const feedbackError = ref('')
const feedbackReadError = ref('')
const feedbackLoading = ref(false)
const feedbackSubmitting = ref(false)
const feedbackDirty = computed(() => Boolean(feedback.value && (
  feedbackRating.value !== (feedback.value.myRating ?? 0)
  || feedbackComment.value !== (feedback.value.myComment ?? '')
)))

async function confirmLeave(): Promise<boolean> {
  if (feedbackSubmitting.value || applySubmitting.value || acting.value) return false
  if (!feedbackDirty.value && !applyDirty.value) return true
  try {
    await ElMessageBox.confirm('有尚未保存的编辑内容，确定离开当前活动？', '未保存的修改', {
      confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning',
    })
    return true
  } catch { return false }
}
onBeforeRouteLeave((to) => to.path === '/login' && session.status !== 'authenticated' ? true : confirmLeave())
onBeforeRouteUpdate((to, from) => to.path === from.path ? true : confirmLeave())
function beforeUnload(event: BeforeUnloadEvent): void {
  if (feedbackDirty.value || applyDirty.value || feedbackSubmitting.value || applySubmitting.value || acting.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => {
  alive = false
  requestSeq++
  window.removeEventListener('beforeunload', beforeUnload)
})

async function loadFeedback(target: string, seq: number, gen: number): Promise<void> {
  feedbackLoading.value = true
  feedbackReadError.value = ''
  try {
    const summary = await fetchPublicFeedback(target)
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    const preserveDraft = feedbackDirty.value
    feedback.value = summary
    if (!preserveDraft) {
      feedbackVersion = summary.myVersion ?? 0
      feedbackRating.value = summary.myRating ?? 0
      feedbackComment.value = summary.myComment ?? ''
    }
  } catch (error) {
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    // 失败时隐藏读取内容，但保留已加载的草稿基线，重试不覆盖未保存的输入。
    feedbackReadError.value = readErrorMessage(error, '反馈加载失败，请重试。')
  } finally {
    if (seq === requestSeq && gen === session.generation) feedbackLoading.value = false
  }
}

function retryFeedback() {
  if (feedbackLoading.value) return
  void loadFeedback(activityId.value, requestSeq, session.capture())
}

async function loadMine(target: string, seq: number, gen: number) {
  mineLoading.value = true
  mineError.value = ''
  try {
    const items = await fetchMyRegistrations()
    if (seq === requestSeq && gen === session.generation) {
      mine.value = items.find(entry => entry.activityId === target) ?? null
    }
  } catch (error) {
    if (seq !== requestSeq || gen !== session.generation) return
    if (!session.observeUnauthorized(error, gen)) mineError.value = readErrorMessage(error, '本人报名加载失败，请重试。')
  } finally {
    if (seq === requestSeq && gen === session.generation) mineLoading.value = false
  }
}

function retryMine() {
  if (mineLoading.value) return
  void loadMine(activityId.value, requestSeq, session.capture())
}

async function submitFeedback(): Promise<void> {
  if (feedbackRating.value < 1) {
    feedbackError.value = '请先选择 1～5 分评分'
    return
  }
  const context = captureAction()
  const rating = feedbackRating.value
  const comment = feedbackComment.value
  feedbackSubmitting.value = true
  try {
    const failure = await guard(async () => {
      const saved = await submitPublicFeedback(context.activityId, rating, comment, feedbackVersion)
      if (isActionCurrent(context)) {
        feedbackVersion = saved.version
        feedback.value = { ...feedback.value!, mineSubmitted: true,
          myRating: rating, myComment: comment.trim(), myVersion: saved.version }
        feedbackComment.value = comment.trim()
        await loadFeedback(context.activityId, requestSeq, context.generation)
      }
    }, feedbackError, context)
    feedbackConflict.value = failure instanceof ApiError && failure.code === 'EDIT_CONFLICT'
  } finally {
    if (isActionCurrent(context)) feedbackSubmitting.value = false
  }
}

async function reviewFeedbackVersion() {
  const context = captureAction()
  await guard(async () => {
    const latest = await fetchPublicFeedback(context.activityId)
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
    feedbackError.value = '最新反馈已核对，输入仍保留；请再次保存。'
  }, feedbackError, context)
}

async function load(): Promise<void> {
  if (!alive || !activityId.value) {
    return
  }
  const seq = ++requestSeq
  const target = activityId.value
  const gen = session.capture()
  loading.value = true
  errorText.value = ''
  missing.value = false
  try {
    const detail = await fetchPublicActivity(target)
    if (seq !== requestSeq || target !== activityId.value) {
      return
    }
    item.value = detail
    loading.value = false
    // 各读取独立处理失败，已成功读取的公开介绍仍可浏览。
    if (session.status === 'authenticated') {
      await Promise.all([loadMine(target, seq, gen), loadFeedback(target, seq, gen)])
    } else {
      mine.value = null
      feedback.value = null
      mineLoading.value = false
      feedbackLoading.value = false
    }
  } catch (error) {
    if (seq !== requestSeq) {
      return
    }
    item.value = null
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    missing.value = error instanceof ApiError && !error.isTransportError && [403, 404].includes(error.status)
    errorText.value = missing.value ? '活动不存在或未公开，或当前无权访问。' : '公开活动加载失败，请重新加载。'
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

watch([activityId, () => session.status], load)
onMounted(load)

const blockReason = computed(() => (item.value ? applyBlockReason(item.value) : ''))
const canCancel = computed(() => (mine.value ? isCancellable(mine.value) : false))
const statusType = computed(() => {
  switch (mine.value?.status) {
    case 'APPROVED':
      return 'success'
    case 'PENDING':
      return 'warning'
    case 'REJECTED':
      return 'danger'
    default:
      return 'info'
  }
})

/** 未登录时先登录并回跳本页；登录后直接在本页完成报名。 */
function requireLogin(): boolean {
  if (session.status === 'authenticated') {
    return true
  }
  void router.push({
    path: '/login',
    query: { next: `/public/activities/${activityId.value}` },
  })
  return false
}

function captureAction() {
  return { activityId: activityId.value, generation: session.capture(), scope: actionGeneration }
}

function isActionCurrent(context: ReturnType<typeof captureAction>): boolean {
  return alive && context.activityId === activityId.value && context.generation === session.generation
    && context.scope === actionGeneration
}

async function guard(fn: () => Promise<void>, errorTarget = actionError, context = captureAction()): Promise<unknown> {
  if (!isActionCurrent(context)) return
  errorTarget.value = ''
  acting.value = true
  try {
    await fn()
  } catch (error) {
    if (session.observeUnauthorized(error, context.generation) || !isActionCurrent(context)) {
      return error
    }
    if (error instanceof ApiError && !error.isTransportError) {
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        errorTarget.value = '安全校验已过期，请重试。'
      } else {
        errorTarget.value = error.detail || '操作失败，请重试。'
      }
    } else {
      errorTarget.value = '网络连接失败，请检查网络后重试。'
    }
    return error
  } finally {
    if (isActionCurrent(context)) acting.value = false
  }
}

function openApply() {
  applyVersion = mine.value?.version ?? 0
  if (!requireLogin()) {
    return
  }
  applyForm.value = {
    displayName: mine.value?.displayName ?? session.account?.username ?? '',
    note: mine.value?.note ?? '',
  }
  applyInitial.value = { ...applyForm.value }
  applyError.value = ''
  applyVisible.value = true
}

async function closeApply(done?: () => void): Promise<void> {
  if (applySubmitting.value || acting.value) return
  if (applyDirty.value) {
    try {
      await ElMessageBox.confirm('报名信息还未保存，确定放弃修改？', '未保存的修改', {
        confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning',
      })
    } catch { return }
  }
  if (done) done()
  else applyVisible.value = false
}

async function submitApply() {
  if (applySubmitting.value) return
  const context = captureAction()
  const form = applyFormRef.value
  if (form) {
    const valid = await form.validate().catch(() => false)
    if (!valid) {
      return
    }
  }
  if (!isActionCurrent(context)) return
  applySubmitting.value = true
  await guard(async () => {
    await applyRegistration(context.activityId, {
      displayName: applyForm.value.displayName.trim(),
      note: applyForm.value.note.trim() || undefined,
      version: applyVersion,
    })
    if (!isActionCurrent(context)) return
    applyVisible.value = false
    await load()
  }, applyError, context)
  if (isActionCurrent(context)) applySubmitting.value = false
}

function cancelMine() {
  if (!requireLogin()) {
    return
  }
  const context = captureAction()
  const version = mine.value?.version
  if (version === undefined) return
  void ElMessageBox.confirm('确定取消本人报名？名额将立即释放。', '取消报名', {
    confirmButtonText: '取消报名', cancelButtonText: '返回', type: 'warning',
  })
    .then(() => guard(async () => {
      await cancelMyRegistration(context.activityId, version)
      if (!isActionCurrent(context)) return
      await load()
    }, actionError, context))
    .catch(() => undefined)
}
</script>

<template>
  <section class="public-detail">
    <ElSkeleton
      v-if="loading"
      :rows="6"
      animated
      data-public-detail-skeleton
      aria-busy="true"
      aria-label="正在加载公开活动"
    />

    <!-- 读取失败与不存在分别说明；均保留重试及返回入口。 -->
    <div
      v-else-if="!item"
      class="public-empty"
      :data-public-detail-missing="missing ? '' : undefined"
      role="alert"
    >
      <p>{{ errorText || '活动不存在或未公开。' }}</p>
      <div class="public-detail__missing-actions">
        <ElButton @click="load">
          重新加载
        </ElButton>
        <RouterLink
          class="public-link"
          to="/public/activities"
        >
          返回公开活动
        </RouterLink>
      </div>
    </div>

    <template v-else>
      <nav class="public-breadcrumb">
        <RouterLink to="/public/activities">
          公开活动
        </RouterLink>
        <span aria-hidden="true">/</span>
        <span>{{ item.name }}</span>
      </nav>

      <header class="public-detail__head">
        <div>
          <p class="public-eyebrow">
            {{ item.organizationName }}
          </p>
          <h1>{{ item.name }}<span class="public-accent">。</span></h1>
          <p class="public-intro">
            {{ item.description || '发布者暂未填写活动简介。' }}
          </p>
        </div>
        <ElTag
          size="small"
          :type="blockReason ? 'info' : 'success'"
        >
          {{ blockReason || '报名开放中' }}
        </ElTag>
      </header>

      <dl class="public-facts">
        <div><dt>开始时间</dt><dd>{{ formatDateTime(item.startTime) }}</dd></div>
        <div><dt>结束时间</dt><dd>{{ formatDateTime(item.endTime) }}</dd></div>
        <div><dt>活动地点</dt><dd>{{ item.location || '待定' }}</dd></div>
        <div><dt>报名范围</dt><dd>{{ scopeLabel(item.registrationScope) }}</dd></div>
        <div><dt>名额</dt><dd>{{ item.capacity ?? '—' }}</dd></div>
        <div><dt>报名开始</dt><dd>{{ formatDateTime(item.registrationStart) }}</dd></div>
        <div><dt>报名截止</dt><dd>{{ formatDateTime(item.registrationEnd) }}</dd></div>
      </dl>

      <section class="public-apply">
        <div class="public-apply__head">
          <h2>我的报名</h2>
          <ElTag
            v-if="mine && !mineLoading && !mineError"
            size="small"
            :type="statusType"
          >
            {{ registrationStatusLabel[mine.status] ?? mine.status }}
          </ElTag>
        </div>

        <p
          v-if="actionError"
          class="public-error"
          role="alert"
        >
          {{ actionError }}
        </p>

        <template v-if="session.status !== 'authenticated'">
          <p class="public-hint">
            登录后可直接在本页报名、查看审核结果与取消；报名不会产生组织成员身份。
          </p>
          <ElButton
            type="primary"
            :disabled="Boolean(blockReason)"
            @click="requireLogin()"
          >
            {{ blockReason || '登录并报名' }}
          </ElButton>
        </template>

        <p
          v-else-if="mineLoading"
          class="public-hint"
          role="status"
        >
          正在读取本人报名…
        </p>
        <p
          v-else-if="mineError"
          class="public-error"
          role="alert"
        >
          {{ mineError }} <ElButton @click="retryMine">
            重新加载
          </ElButton>
        </p>
        <template v-else>
          <p
            v-if="mine && mine.adjusting"
            class="public-hint"
          >
            活动安排调整中：你的报名记录仍然有效，可查看本人状态，重新确认后会给出安排。
          </p>
          <p
            v-if="mine?.cancelReason"
            class="public-hint"
          >
            处理说明：{{ mine.cancelReason }}
          </p>
          <p
            v-if="mine && mine.decidedAt"
            class="public-hint"
          >
            处理时间：{{ formatDateTime(mine.decidedAt) }}
          </p>

          <div class="public-apply__actions">
            <ElButton
              type="primary"
              :disabled="Boolean(blockReason)"
              :loading="acting"
              @click="openApply"
            >
              {{ mine ? (blockReason ? '当前不可修改' : '更新报名信息') : (blockReason || '我要报名') }}
            </ElButton>
            <ElButton
              v-if="canCancel"
              type="danger"
              plain
              :loading="acting"
              @click="cancelMine"
            >
              取消报名
            </ElButton>
            <span
              v-if="!mine && blockReason"
              class="public-hint"
            >
              {{ blockReason }}
            </span>
          </div>
        </template>
      </section>

      <!-- P9.1：复盘期本人反馈；外部参与者不必加入组织，也不是到场核验 -->
      <section
        v-if="session.status === 'authenticated' && (feedbackLoading || feedbackReadError || (feedback && (feedback.canSubmit || feedback.mineSubmitted || feedback.count > 0)))"
        class="public-feedback"
        data-public-feedback
      >
        <h2>活动反馈</h2>
        <p
          v-if="feedbackLoading"
          class="public-hint"
          role="status"
        >
          正在读取活动反馈…
        </p>
        <p
          v-else-if="feedbackReadError"
          class="public-error"
          role="alert"
        >
          {{ feedbackReadError }} <ElButton @click="retryFeedback">
            重新加载
          </ElButton>
        </p>
        <template v-if="feedback && !feedbackReadError && !feedbackLoading">
          <p
            v-if="feedbackError"
            class="public-error"
            role="alert"
          >
            {{ feedbackError }}
          </p>
          <p class="public-hint">
            当前 {{ feedback.count }} 份反馈
            <template v-if="feedback.averageRating">
              ，平均分 {{ feedback.averageRating }}
            </template>
            ；反馈是本人主动表达，不代表到场核验，每账号一份、归档前可修改。
          </p>
          <p
            v-if="!feedback.canSubmit && feedback.submitBlockedReason"
            class="public-hint"
            data-public-feedback-blocked
          >
            {{ feedback.submitBlockedReason }}
          </p>
          <ElRate
            v-model="feedbackRating"
            :disabled="!feedback.canSubmit || feedbackSubmitting || feedbackLoading || Boolean(feedbackReadError)"
            data-public-feedback-rating
          />
          <ElInput
            v-model="feedbackComment"
            type="textarea"
            :rows="3"
            :disabled="!feedback.canSubmit || feedbackSubmitting || feedbackLoading || Boolean(feedbackReadError)"
            placeholder="文字建议（可选，不超过 2000 字符）"
            data-public-feedback-comment
          />
          <ElButton
            type="primary"
            :disabled="!feedback.canSubmit || feedbackRating < 1 || feedbackLoading || Boolean(feedbackReadError)"
            :loading="feedbackSubmitting"
            data-public-feedback-submit
            @click="submitFeedback"
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
        </template>
      </section>

      <p class="public-note">
        本页只包含发布者公开的活动信息；任务分工、成员、预算与资料属于组织内部内容，不在此展示。
      </p>
    </template>

    <ElDialog
      v-model="applyVisible"
      :title="mine ? '更新报名信息' : '报名'"
      width="min(var(--ui-dialog-width), calc(100vw - 2 * var(--ui-space-4)))"
      :before-close="closeApply"
    >
      <p
        v-if="applyError"
        class="public-error"
        role="alert"
      >
        {{ applyError }}
      </p>
      <ElForm
        ref="applyFormRef"
        :model="applyForm"
        :rules="applyRules"
        label-position="top"
      >
        <ElFormItem
          label="展示姓名"
          prop="displayName"
          required
        >
          <ElInput
            v-model="applyForm.displayName"
            :disabled="applySubmitting"
            placeholder="报名姓名（1-64 字符）"
          />
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput
            v-model="applyForm.note"
            type="textarea"
            :rows="2"
            :disabled="applySubmitting"
            placeholder="选填，例如联系方式用途说明"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton
          :disabled="applySubmitting"
          @click="closeApply()"
        >
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="applySubmitting"
          @click="submitApply"
        >
          提交
        </ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.public-breadcrumb {
  display: flex;
  gap: var(--ui-space-2);
  margin-bottom: var(--ui-space-6);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.public-breadcrumb a {
  color: var(--ui-color-selected-text);
  text-decoration: none;
}

.public-detail__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ui-space-6);
  flex-wrap: wrap;
}

/* 终态占位：说明原因并给出重试与返回入口，与公开列表同口径。 */
.public-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ui-space-4);
  padding: var(--ui-space-8);
  border: var(--ui-border-width) dashed var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
  text-align: center;
}

.public-detail__missing-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: var(--ui-space-4);
}

.public-link {
  color: var(--ui-color-selected-text);
  font-size: var(--ui-font-size-small);
  text-decoration: none;
}

.public-link:hover {
  text-decoration: underline;
}

.public-eyebrow {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
}

.public-detail__head h1 {
  margin-top: var(--ui-space-2);
  font-size: var(--ui-font-size-page-title);
  font-weight: var(--ui-font-weight-bold);
  letter-spacing: var(--ui-letter-spacing-title);
}

.public-accent {
  color: var(--ui-color-accent);
}

.public-intro {
  max-width: var(--ui-form-max-width);
  margin-top: var(--ui-space-4);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-prose);
}

.public-facts {
  display: grid;
  gap: var(--ui-layout-grid-gap);
  grid-template-columns: repeat(auto-fill, minmax(min(100%, var(--ui-layout-card-min-width)), 1fr));
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
  font-size: var(--ui-font-size-small);
}

.public-facts dt {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.public-facts dd {
  margin-top: var(--ui-space-1);
}

.public-apply {
  margin-top: var(--ui-space-6);
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
}

.public-apply__head {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  margin-bottom: var(--ui-space-4);
}

.public-apply__head h2 {
  font-size: var(--ui-font-size-section);
  font-weight: var(--ui-font-weight-semibold);
}

.public-apply__actions {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  flex-wrap: wrap;
}

.public-detail__actions {
  display: flex;
  align-items: center;
  gap: var(--ui-space-4);
  margin-top: var(--ui-space-6);
  flex-wrap: wrap;
}

.public-hint,
.public-note {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}

.public-note {
  margin-top: var(--ui-space-8);
  line-height: var(--ui-line-height-prose);
}

.public-error {
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}
</style>
