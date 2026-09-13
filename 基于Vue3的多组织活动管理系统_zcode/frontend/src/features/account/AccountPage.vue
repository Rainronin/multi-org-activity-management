<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessageBox, ElTag, type FormInstance, type FormRules } from 'element-plus'
import { ApiError } from '../../shared/http/error'
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

const createDialogVisible = ref(false)
const joinDialogVisible = ref(false)
const directoryError = ref('')

// P4.3 我的报名（跨组织）：入口放在账号页，外部参与者不需要进入组织工作区。
const registrations = ref<RegistrationItem[]>([])
const registrationsError = ref('')
const cancellingId = ref('')
const cancellingError = ref('')

const registrationTagType = (status: RegistrationStatus): 'success' | 'info' | 'warning' | 'danger' =>
  status === 'APPROVED' ? 'success' : status === 'PENDING' ? 'warning'
    : status === 'REJECTED' ? 'danger' : 'info'

/** F01：本人取消后立即刷新状态；名额释放与幂等由后端保证。 */
function cancelRegistration(item: RegistrationItem) {
  cancellingError.value = ''
  cancellingId.value = item.registrationId
  void ElMessageBox.confirm('确定取消本人报名？名额将立即释放。', '取消报名', {
    confirmButtonText: '取消报名', cancelButtonText: '返回', type: 'warning',
  })
    .then(async () => {
      const gen = session.capture()
      try {
        await cancelMyRegistration(item.activityId)
      } catch (error) {
        if (session.observeUnauthorized(error, gen)) {
          return
        }
        cancellingError.value = error instanceof ApiError && !error.isTransportError
          ? error.detail || '取消失败，请重试。'
          : '网络连接失败，请检查网络后重试。'
        return
      }
      await loadRegistrations()
    })
    .catch(() => undefined)
    .finally(() => {
      cancellingId.value = ''
    })
}

async function loadRegistrations(): Promise<void> {
  registrationsError.value = ''
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
    registrationsError.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '报名记录加载失败，请重试。'
  }
}

async function loadDirectory(): Promise<void> {
  directoryError.value = ''
  const gen = session.capture()
  try {
    await directoryStore.fetchDirectory(true)
  } catch (error) {
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    directoryError.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '组织目录加载失败，请重试。'
  }
}

onMounted(() => {
  void loadDirectory()
  void loadRegistrations()
})

function enterOrganization(org: OrganizationDirectoryItem): void {
  void router.push(`/org/${org.id}`)
}

function onOrganizationCreated(org: OrganizationDirectoryItem): void {
  directoryStore.fetchDirectory(true).finally(() => {
    void router.push(`/org/${org.id}`)
  })
}

function onOrganizationJoined(org: { orgId: string; name: string }): void {
  directoryStore.fetchDirectory(true).finally(() => {
    void router.push(`/org/${org.orgId}`)
  })
}

async function submitPasswordChange() {
  const valid = await passwordFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
    const gen = session.capture()
passwordSubmitting.value = true
  passwordError.value = ''
  try {
    await session.changePassword(passwordFields.currentPassword, passwordFields.newPassword)
    passwordDialogVisible.value = false
    passwordFields.currentPassword = ''
    passwordFields.newPassword = ''
    passwordFields.confirmPassword = ''
  } catch (error) {
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    if (error instanceof ApiError && !error.isTransportError) {
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        passwordError.value = '安全校验已过期，请重新提交。'
      } else if (error.code === 'AUTH_INVALID_CREDENTIALS') {
        passwordError.value = '当前密码不正确。'
      } else {
        passwordError.value = error.detail || '修改密码失败，请稍后重试。'
      }
    } else {
      passwordError.value = '网络连接失败，请检查网络后重试。'
    }
  } finally {
    passwordSubmitting.value = false
  }
}

async function submitReissue() {
  const valid = await reissueFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
    const gen = session.capture()
reissueSubmitting.value = true
  reissueError.value = ''
  try {
    const result = await reissueRecoveryCode(reissueFields.currentPassword)
    reissueDialogVisible.value = false
    reissueFields.currentPassword = ''
    issuedRecoveryCode.value = result.recoveryCode
    codeDialogVisible.value = true
  } catch (error) {
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    if (error instanceof ApiError && !error.isTransportError) {
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        reissueError.value = '安全校验已过期，请重新提交。'
      } else if (error.code === 'AUTH_INVALID_CREDENTIALS') {
        reissueError.value = '当前密码不正确。'
      } else {
        reissueError.value = error.detail || '重签失败，请稍后重试。'
      }
    } else {
      reissueError.value = '网络连接失败，请检查网络后重试。'
    }
  } finally {
    reissueSubmitting.value = false
  }
}

async function handleLogout() {
  try {
    await session.logout()
  } finally {
    await router.replace('/login')
  }
}
</script>

<template>
  <main class="account-page">
    <header class="account-page__head">
      <h1>账号</h1>
      <p class="account-page__identity">
        当前身份：<strong>{{ session.account?.username }}</strong>
      </p>
    </header>

    <section class="account-page__card">
      <div class="account-page__card-head">
        <h2>我的组织</h2>
        <ElButton
          type="primary"
          plain
          @click="createDialogVisible = true"
        >
          创建组织
        </ElButton>
      </div>
      <p
        v-if="directoryError"
        class="account-page__hint"
        role="alert"
      >
        {{ directoryError }}
      </p>
      <ul
        v-else-if="directoryStore.items.length > 0"
        class="account-page__orgs"
      >
        <li
          v-for="org in directoryStore.items"
          :key="org.id"
        >
          <div>
            <span class="account-page__org-name">{{ org.name }}</span>
            <ElTag
              :type="org.role === 'OWNER' ? 'success' : 'info'"
              size="small"
            >
              {{ roleLabel(org.role) }}
            </ElTag>
          </div>
          <ElButton
            size="small"
            @click="enterOrganization(org)"
          >
            进入工作区
          </ElButton>
        </li>
      </ul>
      <p
        v-else
        class="account-page__hint"
      >
        尚未创建或加入任何组织。创建组织后即可开始协作。
      </p>
    </section>

    <section class="account-page__card">
      <div class="account-page__card-head">
        <h2>我的报名</h2>
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
      </p>
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
        v-else
        class="account-page__hint"
      >
        暂无报名记录。登录后可在公开活动页报名，并在活动开始前取消。
      </p>
      <p
        v-if="cancellingError"
        class="account-page__hint"
        role="alert"
      >
        {{ cancellingError }}
      </p>
    </section>

    <section class="account-page__card">
      <div class="account-page__card-head">
        <h2>账号安全</h2>
      </div>
      <div class="account-page__actions">
        <ElButton @click="passwordDialogVisible = true">
          修改密码
        </ElButton>
        <ElButton @click="reissueDialogVisible = true">
          重签恢复码
        </ElButton>
        <ElButton
          type="danger"
          plain
          @click="handleLogout"
        >
          退出登录
        </ElButton>
      </div>
    </section>

    <ElDialog
      v-model="passwordDialogVisible"
      title="修改密码"
      width="var(--ui-dialog-width)"
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
        <ElButton @click="passwordDialogVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="passwordSubmitting"
          @click="submitPasswordChange"
        >
          确认修改
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="reissueDialogVisible"
      title="重签恢复码"
      width="var(--ui-dialog-width)"
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
        <ElButton @click="reissueDialogVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="reissueSubmitting"
          @click="submitReissue"
        >
          重签
        </ElButton>
      </template>
    </ElDialog>

    <RecoveryCodeDialog
      v-model="codeDialogVisible"
      :code="issuedRecoveryCode"
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
  max-width: var(--ui-form-max-width);
  margin: 0 auto;
  padding: var(--ui-layout-page-padding);
}

.account-page__head h1 {
  font-size: var(--ui-font-size-page-title);
  font-weight: var(--ui-font-weight-bold);
  letter-spacing: var(--ui-letter-spacing-title);
}

.account-page__identity {
  margin-top: var(--ui-space-2);
  color: var(--ui-color-text-secondary);
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
</style>
