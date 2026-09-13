<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessageBox, ElRate, ElSkeleton, ElTag } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { ApiError } from '../../shared/http/error'
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
const loading = ref(false)
const errorText = ref('')
const actionError = ref('')
const acting = ref(false)

const applyVisible = ref(false)
const applySubmitting = ref(false)
const applyForm = ref({ displayName: '', note: '' })
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

// P9.1：外部参与者的本人反馈（复盘中可提交，不必加入组织）
const feedback = ref<PublicFeedbackSummary | null>(null)
const feedbackRating = ref(0)
const feedbackComment = ref('')
const feedbackSubmitting = ref(false)

async function loadFeedback(target: string, seq: number, gen: number): Promise<void> {
  try {
    const summary = await fetchPublicFeedback(target)
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    feedback.value = summary
    feedbackRating.value = summary.myRating ?? 0
    feedbackComment.value = summary.myComment ?? ''
  } catch {
    // 未到复盘期或没有资格依据时不展示该区块，不影响公开浏览
    feedback.value = null
  }
}

async function submitFeedback(): Promise<void> {
  if (feedbackRating.value < 1) {
    actionError.value = '请先选择 1～5 分评分'
    return
  }
  const gen = session.capture()
  feedbackSubmitting.value = true
  actionError.value = ''
  try {
    await submitPublicFeedback(activityId.value, feedbackRating.value, feedbackComment.value)
    await loadFeedback(activityId.value, requestSeq, gen)
  } catch (error) {
    actionError.value = error instanceof ApiError ? error.detail : '反馈提交失败，请稍后重试。'
  } finally {
    feedbackSubmitting.value = false
  }
}

async function load(): Promise<void> {
  if (!activityId.value) {
    return
  }
  const seq = ++requestSeq
  const target = activityId.value
  const gen = session.capture()
  loading.value = true
  errorText.value = ''
  try {
    const detail = await fetchPublicActivity(target)
    if (seq !== requestSeq || target !== activityId.value) {
      return
    }
    item.value = detail
    // 本人报名只在登录后查询；失败不影响公开信息展示
    if (session.status === 'authenticated') {
      const mineItems = await fetchMyRegistrations()
      if (seq !== requestSeq || gen !== session.generation) {
        return
      }
      mine.value = mineItems.find((entry) => entry.activityId === target) ?? null
    } else {
      mine.value = null
    }
    if (session.status === 'authenticated') {
      await loadFeedback(target, seq, gen)
    } else {
      feedback.value = null
    }
  } catch (error) {
    if (seq !== requestSeq) {
      return
    }
    item.value = null
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '活动不存在或未公开。'
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

function openApply() {
  if (!requireLogin()) {
    return
  }
  applyForm.value = {
    displayName: mine.value?.displayName ?? session.account?.username ?? '',
    note: mine.value?.note ?? '',
  }
  applyVisible.value = true
}

async function submitApply() {
  const form = applyFormRef.value
  if (form) {
    const valid = await form.validate().catch(() => false)
    if (!valid) {
      return
    }
  }
  applySubmitting.value = true
  await guard(async () => {
    await applyRegistration(activityId.value, {
      displayName: applyForm.value.displayName.trim(),
      note: applyForm.value.note.trim() || undefined,
    })
    applyVisible.value = false
    await load()
  })
  applySubmitting.value = false
}

function cancelMine() {
  if (!requireLogin()) {
    return
  }
  void ElMessageBox.confirm('确定取消本人报名？名额将立即释放。', '取消报名', {
    confirmButtonText: '取消报名', cancelButtonText: '返回', type: 'warning',
  })
    .then(() => guard(async () => {
      await cancelMyRegistration(activityId.value)
      await load()
    }))
    .catch(() => undefined)
}
</script>

<template>
  <section class="public-detail">
    <ElSkeleton
      v-if="loading"
      :rows="6"
      animated
    />

    <template v-else-if="item">
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
            v-if="mine"
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
        v-if="feedback && (feedback.canSubmit || feedback.mineSubmitted || feedback.count > 0)"
        class="public-feedback"
        data-public-feedback
      >
        <h2>活动反馈</h2>
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
          :disabled="!feedback.canSubmit"
          data-public-feedback-rating
        />
        <ElInput
          v-model="feedbackComment"
          type="textarea"
          :rows="3"
          :disabled="!feedback.canSubmit"
          placeholder="文字建议（可选，不超过 2000 字符）"
          data-public-feedback-comment
        />
        <ElButton
          type="primary"
          :disabled="!feedback.canSubmit || feedbackRating < 1"
          :loading="feedbackSubmitting"
          data-public-feedback-submit
          @click="submitFeedback"
        >
          {{ feedback.mineSubmitted ? '更新反馈' : '提交反馈' }}
        </ElButton>
      </section>

      <p class="public-note">
        本页只包含发布者公开的活动信息；任务分工、成员、预算与资料属于组织内部内容，不在此展示。
      </p>
    </template>

    <p
      v-else
      class="public-error"
      role="alert"
    >
      {{ errorText }}
      <ElButton
        text
        @click="load"
      >
        重试
      </ElButton>
    </p>

    <ElDialog
      v-model="applyVisible"
      :title="mine ? '更新报名信息' : '报名'"
      width="var(--ui-dialog-width)"
    >
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
            placeholder="报名姓名（1-64 字符）"
          />
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput
            v-model="applyForm.note"
            type="textarea"
            :rows="2"
            placeholder="选填，例如联系方式用途说明"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="applyVisible = false">
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
  gap: var(--ui-space-4);
  margin-bottom: var(--ui-space-8);
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
