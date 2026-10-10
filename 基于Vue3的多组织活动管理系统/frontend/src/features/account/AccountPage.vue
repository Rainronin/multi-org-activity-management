<script setup lang="ts">
import { OfficeBuilding, Calendar, Right, Lock } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessage, ElMessageBox, ElSkeleton, ElTag, type FormInstance, type FormRules } from 'element-plus'
import { useLoadingSkeleton } from '../../shared/utils/useLoadingSkeleton'
import { ApiError, readErrorMessage } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { useOrganizationsStore } from '../../stores/organizations'
import { reissueRecoveryCode } from './api'
import {
  cancelMyRegistration, fetchMyRegistrations, isCancellable, registrationStatusLabel,
  type RegistrationItem, type RegistrationStatus,
} from '../activity/registrations'
import { formatDateTime } from '../activity/types'
import { roleLabel } from '../organization/types'
import type { OrganizationDirectoryItem } from '../organization/types'
import RecoveryCodeDialog from './RecoveryCodeDialog.vue'
import NotificationsSection from '../notification/NotificationsSection.vue'
import CreateOrganizationDialog from '../organization/CreateOrganizationDialog.vue'
import JoinByCodeDialog from '../organization/JoinByCodeDialog.vue'

const session = useSessionStore()
const directoryStore = useOrganizationsStore()
const router = useRouter()

const passwordFormRef = ref<FormInstance>()
const passwordDialogVisible = ref(false)
const passwordSubmitting = ref(false)
const passwordError = ref('')
const passwordFields = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })

const passwordRules: FormRules = {
  currentPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,72}$/, message: '密码需 8-72 位且同时包含字母和数字', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (value !== passwordFields.newPassword) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

const reissueFormRef = ref<FormInstance>()
const reissueDialogVisible = ref(false)
const reissueSubmitting = ref(false)
const reissueError = ref('')
const reissueFields = reactive({ currentPassword: '' })

const reissueRules: FormRules = {
  currentPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
}

const codeDialogVisible = ref(false)
const issuedRecoveryCode = ref('')
const securitySubmitting = computed(() => passwordSubmitting.value || reissueSubmitting.value)

const createDialogVisible = ref(false)
const joinDialogVisible = ref(false)
const directoryError = ref('')
const directoryLoading = ref(true)
const directorySkeleton = useLoadingSkeleton()

// P4.3 我的报名（跨组织）：入口放在账号页，外部参与者不需要进入组织工作区。
const registrations = ref<RegistrationItem[]>([])
const registrationsError = ref('')
const registrationsLoading = ref(true)
const registrationsSkeleton = useLoadingSkeleton()
const cancellingId = ref('')
const cancellingError = ref('')
let alive = true
onBeforeUnmount(() => { alive = false })

const registrationTagType = (status: RegistrationStatus): 'success' | 'info' | 'warning' | 'danger' =>
  status === 'APPROVED' ? 'success' : status === 'PENDING' ? 'warning'
    : status === 'REJECTED' ? 'danger' : 'info'

/** F01：本人取消后立即刷新状态；名额释放与幂等由后端保证。 */
function cancelRegistration(item: RegistrationItem) {
  const gen = session.capture()
  const isCurrent = () => alive && gen === session.generation
  cancellingError.value = ''
  cancellingId.value = item.registrationId
  void ElMessageBox.confirm('确定取消本人报名？名额将立即释放。', '取消报名', {
    confirmButtonText: '取消报名', cancelButtonText: '返回', type: 'warning',
  })
    .then(async () => {
      if (!isCurrent()) return
      try {
        await cancelMyRegistration(item.activityId, item.version)
      } catch (error) {
        if (session.observeUnauthorized(error, gen)) {
          return
        }
        if (!isCurrent()) return
        cancellingError.value = error instanceof ApiError && !error.isTransportError
          ? error.detail || '取消失败，请重试。'
          : '网络连接失败，请检查网络后重试。'
        return
      }
      if (isCurrent()) await loadRegistrations()
    })
    .catch(() => undefined)
    .finally(() => {
      if (isCurrent()) cancellingId.value = ''
    })
}

async function loadRegistrations(): Promise<void> {
  registrationsError.value = ''
  registrationsLoading.value = true
  if (registrations.value.length === 0) registrationsSkeleton.begin()
  const gen = session.capture()
  try {
    const items = await fetchMyRegistrations()
    if (gen !== session.generation) {
      return
    }
    registrations.value = items
  } catch (error) {
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    registrations.value = []
    registrationsError.value = readErrorMessage(error, '报名记录加载失败，请重试。')
  } finally {
    if (alive && gen === session.generation) {
      registrationsLoading.value = false
      registrationsSkeleton.end()
    }
  }
}

async function loadDirectory(): Promise<void> {
  directoryError.value = ''
  directoryLoading.value = true
  if (directoryStore.items.length === 0) directorySkeleton.begin()
  const gen = session.capture()
  try {
    await directoryStore.fetchDirectory(true)
  } catch (error) {
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    directoryError.value = readErrorMessage(error, '组织目录加载失败，请重试。')
  } finally {
    if (alive && gen === session.generation) {
      directoryLoading.value = false
      directorySkeleton.end()
    }
  }
}

onMounted(() => {
  void loadDirectory()
  void loadRegistrations()
})

function enterOrganization(org: OrganizationDirectoryItem): void {
  void router.push(`/org/${org.id}`)
}

async function enterAfterDirectoryRefresh(orgId: string): Promise<void> {
  if (!alive) return
  const gen = session.capture()
  await loadDirectory()
  if (alive && gen === session.generation) void router.push(`/org/${orgId}`)
}

function onOrganizationCreated(org: OrganizationDirectoryItem): void {
  void enterAfterDirectoryRefresh(org.id)
}

function onOrganizationJoined(org: { orgId: string; name: string }): void {
  void enterAfterDirectoryRefresh(org.orgId)
}

async function submitPasswordChange() {
  if (securitySubmitting.value || codeDialogVisible.value) return
  const gen = session.capture()
  passwordSubmitting.value = true
  passwordError.value = ''
  try {
    const valid = await passwordFormRef.value?.validate().catch(() => false)
    if (!valid || !alive || gen !== session.generation) return
    await session.changePassword(passwordFields.currentPassword, passwordFields.newPassword)
    if (!alive || gen !== session.generation) return
    passwordDialogVisible.value = false
    passwordFields.currentPassword = ''
    passwordFields.newPassword = ''
    passwordFields.confirmPassword = ''
  } catch (error) {
    if (session.observeUnauthorized(error, gen) || !alive || gen !== session.generation) {
      return
    }
    if (error instanceof ApiError && !error.isTransportError && (error.status ?? 0) < 500) {
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        passwordError.value = '安全校验已过期，请重新提交。'
      } else if (error.code === 'AUTH_INVALID_CREDENTIALS') {
        passwordError.value = '当前密码不正确。若刚才修改结果尚未确认，请先用新密码重新登录核对。'
      } else {
        passwordError.value = error.detail || '修改密码失败，请稍后重试。'
      }
    } else {
      passwordError.value = '修改结果尚未确认。请先用新密码重新登录核对；若无法登录，再尝试原密码。'
    }
  } finally {
    passwordSubmitting.value = false
  }
}

async function submitReissue() {
  if (securitySubmitting.value || codeDialogVisible.value) return
  const gen = session.capture()
  reissueSubmitting.value = true
  reissueError.value = ''
  try {
    const valid = await reissueFormRef.value?.validate().catch(() => false)
    if (!valid || !alive || gen !== session.generation) return
    const result = await reissueRecoveryCode(reissueFields.currentPassword)
    if (!alive || gen !== session.generation) return
    reissueDialogVisible.value = false
    reissueFields.currentPassword = ''
    issuedRecoveryCode.value = result.recoveryCode
    codeDialogVisible.value = true
  } catch (error) {
    if (session.observeUnauthorized(error, gen) || !alive || gen !== session.generation) {
      return
    }
    if (error instanceof ApiError && !error.isTransportError && (error.status ?? 0) < 500) {
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        reissueError.value = '安全校验已过期，请重新提交。'
      } else if (error.code === 'AUTH_INVALID_CREDENTIALS') {
        reissueError.value = '当前密码不正确。'
      } else {
        reissueError.value = error.detail || '重签失败，请稍后重试。'
      }
    } else {
      reissueError.value = '重签结果尚未确认，旧恢复码可能已失效。请保持登录，重新重签并保存新码。'
    }
  } finally {
    reissueSubmitting.value = false
  }
}

async function handleLogout() {
  if (securitySubmitting.value || codeDialogVisible.value) return
  const gen = session.capture()
  try {
    await session.logout()
    if (session.status === 'guest') await router.replace('/login')
  } catch {
    if (alive && gen === session.generation) ElMessage.error('退出结果尚未确认，请检查网络后再次退出。')
  }
}
</script>

<template>
  <main class="account-page">
    <div class="page-heading">
      <div class="page-heading__title">
        <p class="page-eyebrow">
          个人工作区
        </p>
        <h1>我的工作区</h1>
        <p class="page-description">
          <strong>{{ session.account?.username }}</strong>，欢迎回来。选择一个组织，继续你的活动协作。
        </p>
      </div>
      <div class="page-actions">
        <ElButton
          plain
          @click="joinDialogVisible = true"
        >
          加入组织
        </ElButton>
        <ElButton
          type="primary"
          @click="createDialogVisible = true"
        >
          创建组织
        </ElButton>
      </div>
    </div>

    <section class="account-page__card account-page__organizations">
      <div class="account-page__card-head">
        <div class="account-section-title">
          <OfficeBuilding aria-hidden="true" /><div>
            <h2>我的组织</h2><p class="account-page__hint">
              进入组织，查看活动进展与待办任务
            </p>
          </div>
        </div>
        <ElTag
          type="info"
          effect="plain"
        >
          {{ directoryStore.items.length }} 个组织
        </ElTag>
      </div>
      <p
        v-if="directoryError"
        class="account-page__hint"
        role="alert"
      >
        {{ directoryError }}
        <ElButton
          text
          @click="loadDirectory"
        >
          重新加载
        </ElButton>
      </p>
      <ElSkeleton
        v-else-if="directorySkeleton.loading.value && directoryStore.items.length === 0"
        :rows="3"
        animated
        aria-busy="true"
        aria-label="正在加载组织目录"
      />
      <ul
        v-else-if="directoryStore.items.length > 0"
        class="account-page__orgs account-page__directory"
      >
        <li
          v-for="org in directoryStore.items"
          :key="org.id"
        >
          <div>
            <span
              class="account-page__org-avatar"
              aria-hidden="true"
            >{{ org.name.slice(0, 1) }}</span>
            <div class="account-page__org-copy">
              <span class="account-page__org-name">{{ org.name }}</span>
              <ElTag
                :type="org.role === 'OWNER' ? 'success' : 'info'"
                size="small"
              >
                {{ roleLabel(org.role) }}
              </ElTag>
            </div>
          </div>
          <ElButton
            size="small"
            @click="enterOrganization(org)"
          >
            进入工作区 <Right
              class="account-action-icon"
              aria-hidden="true"
            />
          </ElButton>
        </li>
      </ul>
      <p
        v-else-if="!directoryLoading"
        class="account-page__hint"
      >
        尚未创建或加入任何组织。创建组织后即可开始协作。
      </p>
      <p
        v-else
        class="account-page__hint"
        role="status"
      >
        正在加载组织目录……
      </p>
    </section>

    <section class="account-page__card">
      <div class="account-page__card-head">
        <div class="account-section-title">
          <Calendar aria-hidden="true" /><div>
            <h2>我的报名</h2><p class="account-page__hint">
              追踪参与活动与报名状态
            </p>
          </div>
        </div>
        <ElButton
          plain
          @click="router.push('/public/activities')"
        >
          浏览公开活动
        </ElButton>
      </div>
      <p
        v-if="registrationsError"
        class="account-page__hint"
        role="alert"
      >
        {{ registrationsError }}
        <ElButton
          text
          @click="loadRegistrations"
        >
          重新加载
        </ElButton>
      </p>
      <ElSkeleton
        v-else-if="registrationsSkeleton.loading.value && registrations.length === 0"
        :rows="3"
        animated
        aria-busy="true"
        aria-label="正在加载报名记录"
      />
      <ul
        v-else-if="registrations.length > 0"
        class="account-page__orgs"
      >
        <li
          v-for="item in registrations"
          :key="item.registrationId"
        >
          <div class="account-page__reg-main">
            <div class="account-page__reg-head">
              <span class="account-page__org-name">{{ item.activityName || '活动已不可见' }}</span>
              <ElTag
                :type="registrationTagType(item.status)"
                size="small"
              >
                {{ registrationStatusLabel[item.status] ?? item.status }}
              </ElTag>
              <ElTag
                v-if="item.adjusting"
                type="warning"
                size="small"
              >
                活动安排调整中
              </ElTag>
            </div>
            <p class="account-page__hint">
              {{ item.organizationName || '' }}
              <template v-if="item.startTime">
                · 开始 {{ formatDateTime(item.startTime) }}
              </template>
              <template v-if="item.location">
                · {{ item.location }}
              </template>
            </p>
            <p class="account-page__hint">
              报名人 {{ item.displayName }} · 提交 {{ formatDateTime(item.createdAt) }}
              <template v-if="item.decidedAt">
                · 处理 {{ formatDateTime(item.decidedAt) }}
              </template>
            </p>
            <p
              v-if="item.cancelReason"
              class="account-page__hint"
            >
              处理说明：{{ item.cancelReason }}
            </p>
          </div>
          <div class="account-page__reg-actions">
            <ElButton
              size="small"
              @click="router.push(`/public/activities/${item.activityId}`)"
            >
              查看活动
            </ElButton>
            <ElButton
              v-if="isCancellable(item)"
              size="small"
              type="danger"
              plain
              :loading="cancellingId === item.registrationId"
              @click="cancelRegistration(item)"
            >
              取消报名
            </ElButton>
          </div>
        </li>
      </ul>
      <p
        v-else-if="!registrationsLoading"
        class="account-page__hint"
      >
        暂无报名记录。登录后可在公开活动页报名，并在活动开始前取消。
      </p>
      <p
        v-else
        class="account-page__hint"
        role="status"
      >
        正在加载报名记录……
      </p>
      <p
        v-if="cancellingError"
        class="account-page__hint"
        role="alert"
      >
        {{ cancellingError }}
      </p>
    </section>

    <!-- P7.3 站内提示：账号维度，列表与跳转都由后端按当前权限复核 -->
    <NotificationsSection />

    <section class="account-page__card account-page__security">
      <div class="account-page__card-head">
        <div class="account-section-title">
          <Lock aria-hidden="true" /><div>
            <h2>账号安全</h2><p class="account-page__hint">
              管理密码与账号恢复方式
            </p>
          </div>
        </div>
      </div>
      <div class="account-page__actions">
        <ElButton
          :disabled="securitySubmitting || codeDialogVisible"
          @click="passwordDialogVisible = true"
        >
          修改密码
        </ElButton>
        <ElButton
          :disabled="securitySubmitting || codeDialogVisible"
          @click="reissueDialogVisible = true"
        >
          重签恢复码
        </ElButton>
        <ElButton
          type="danger"
          plain
          :disabled="securitySubmitting || codeDialogVisible"
          @click="handleLogout"
        >
          退出登录
        </ElButton>
      </div>
    </section>

    <ElDialog
      v-model="passwordDialogVisible"
      title="修改密码"
      width="min(var(--ui-dialog-width), calc(100vw - var(--ui-space-8)))"
      :close-on-click-modal="!securitySubmitting"
      :close-on-press-escape="!securitySubmitting"
      :show-close="!securitySubmitting"
    >
      <p
        v-if="passwordError"
        class="account-page__dialog-error"
        role="alert"
      >
        {{ passwordError }}
      </p>
      <ElForm
        ref="passwordFormRef"
        :model="passwordFields"
        :rules="passwordRules"
        :disabled="securitySubmitting"
        label-position="top"
        @submit.prevent="submitPasswordChange"
      >
        <ElFormItem
          label="当前密码"
          prop="currentPassword"
        >
          <ElInput
            v-model="passwordFields.currentPassword"
            type="password"
            show-password
            autocomplete="current-password"
          />
        </ElFormItem>
        <ElFormItem
          label="新密码"
          prop="newPassword"
        >
          <ElInput
            v-model="passwordFields.newPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="8-72 位，含字母和数字"
          />
        </ElFormItem>
        <ElFormItem
          label="确认新密码"
          prop="confirmPassword"
        >
          <ElInput
            v-model="passwordFields.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton
          :disabled="securitySubmitting"
          @click="passwordDialogVisible = false"
        >
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="passwordSubmitting"
          :disabled="securitySubmitting"
          @click="submitPasswordChange"
        >
          确认修改
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="reissueDialogVisible"
      title="重签恢复码"
      width="min(var(--ui-dialog-width), calc(100vw - var(--ui-space-8)))"
      :close-on-click-modal="!securitySubmitting"
      :close-on-press-escape="!securitySubmitting"
      :show-close="!securitySubmitting"
    >
      <p
        v-if="reissueError"
        class="account-page__dialog-error"
        role="alert"
      >
        {{ reissueError }}
      </p>
      <ElForm
        ref="reissueFormRef"
        :model="reissueFields"
        :rules="reissueRules"
        :disabled="securitySubmitting"
        label-position="top"
        @submit.prevent="submitReissue"
      >
        <ElFormItem
          label="当前密码"
          prop="currentPassword"
        >
          <ElInput
            v-model="reissueFields.currentPassword"
            type="password"
            show-password
            autocomplete="current-password"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton
          :disabled="securitySubmitting"
          @click="reissueDialogVisible = false"
        >
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="reissueSubmitting"
          :disabled="securitySubmitting"
          @click="submitReissue"
        >
          重签
        </ElButton>
      </template>
    </ElDialog>

    <RecoveryCodeDialog
      v-model="codeDialogVisible"
      :code="issuedRecoveryCode"
      :submitting="securitySubmitting"
      confirm-target="新恢复码"
    />

    <CreateOrganizationDialog
      v-model="createDialogVisible"
      @created="onOrganizationCreated"
    />

    <JoinByCodeDialog
      v-model="joinDialogVisible"
      @joined="onOrganizationJoined"
    />
  </main>
</template>

<style scoped>
.account-page {
  display: grid;
  gap: var(--ui-layout-grid-gap);
  align-content: start;
  max-width: var(--ui-layout-content-max);
  grid-template-columns: var(--ui-account-columns);
  margin: 0 auto;
  padding: var(--ui-layout-page-padding);
}

/* 页头排版与工作台、表格页同口径；数值全部取自 token。 */
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

.page-description {
  margin-top: var(--ui-space-3);
  color: var(--ui-color-text-secondary);
  line-height: var(--ui-line-height-body);
}

.page-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-3);
}

.account-page__card {
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
  box-shadow: var(--ui-shadow-glass);
}

.account-page__head-actions {
  display: flex;
  gap: var(--ui-space-3);
}

.account-page__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  margin-bottom: var(--ui-space-4);
}

.account-page__card-head h2 {
  font-size: var(--ui-font-size-section);
  font-weight: var(--ui-font-weight-semibold);
}

.account-page__orgs {
  display: grid;
  gap: var(--ui-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.account-page__orgs li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  padding: var(--ui-space-2) 0;
}

.account-page__orgs li > div {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  min-width: 0;
}

.account-page__org-name {
  color: var(--ui-color-text);
  font-weight: var(--ui-font-weight-semibold);
  overflow-wrap: anywhere;
}

.account-page__reg-main {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
  min-width: 0;
}

.account-page__reg-head {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  flex-wrap: wrap;
}

.account-page__reg-actions {
  display: flex;
  gap: var(--ui-space-2);
  flex-wrap: wrap;
}

.account-page__hint {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}

.account-page__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-3);
}

.account-page__dialog-error {
  margin-bottom: var(--ui-space-4);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.page-heading, .account-page__organizations, .account-page__security { grid-column: 1 / -1; }
.page-heading { padding: var(--ui-space-8); border-radius: var(--ui-radius-card); background: var(--ui-color-text); color: var(--ui-color-on-dark); align-items: center; }
.page-heading .page-eyebrow { color: var(--ui-color-on-dark-accent); }
.page-heading .page-description { color: var(--ui-color-on-dark-muted); }
.account-page__card { min-width: 0; }
.account-page__card-head { flex-wrap: wrap; }
.account-section-title { display: flex; align-items: center; gap: var(--ui-space-3); }
.account-section-title > svg { width: var(--ui-icon); height: var(--ui-icon); color: var(--ui-color-action); flex-shrink: 0; }
.account-section-title .account-page__hint { margin-top: var(--ui-space-1); }
.account-page__directory { grid-template-columns: repeat(auto-fit, minmax(min(100%, var(--ui-org-card-min)), 1fr)); gap: var(--ui-space-4); }
.account-page__directory li { flex-wrap: wrap; padding: var(--ui-space-5); background: var(--ui-color-canvas); border: var(--ui-border-width) solid var(--ui-color-border); border-radius: var(--ui-radius-control); }
.account-page__org-avatar { display: grid; place-items: center; width: var(--ui-space-12); height: var(--ui-space-12); flex-shrink: 0; background: var(--ui-color-selected-bg); color: var(--ui-color-selected-text); border-radius: var(--ui-radius-control); font-size: var(--ui-font-size-section); font-weight: var(--ui-font-weight-semibold); }
.account-page__org-copy { display: grid; gap: var(--ui-space-2); justify-items: start; }
.account-action-icon { width: var(--ui-icon-small); height: var(--ui-icon-small); margin-left: var(--ui-space-2); }
.account-page__security { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--ui-space-4); }
.account-page__security .account-page__card-head { margin-bottom: 0; }
.account-page__orgs li { flex-wrap: wrap; }
.account-page__reg-main { align-items: flex-start; }
.account-page__actions :deep(.el-button + .el-button), .page-actions :deep(.el-button + .el-button) { margin-left: 0; }
</style>
