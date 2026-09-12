<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, type FormInstance, type FormRules } from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { reissueRecoveryCode } from './api'
import RecoveryCodeDialog from './RecoveryCodeDialog.vue'

const session = useSessionStore()
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

async function submitPasswordChange() {
  const valid = await passwordFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  passwordSubmitting.value = true
  passwordError.value = ''
  try {
    await session.changePassword(passwordFields.currentPassword, passwordFields.newPassword)
    passwordDialogVisible.value = false
    passwordFields.currentPassword = ''
    passwordFields.newPassword = ''
    passwordFields.confirmPassword = ''
  } catch (error) {
    if (session.observeUnauthorized(error)) {
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
  reissueSubmitting.value = true
  reissueError.value = ''
  try {
    const result = await reissueRecoveryCode(reissueFields.currentPassword)
    reissueDialogVisible.value = false
    reissueFields.currentPassword = ''
    issuedRecoveryCode.value = result.recoveryCode
    codeDialogVisible.value = true
  } catch (error) {
    if (session.observeUnauthorized(error)) {
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
      <p class="account-page__hint">
        组织创建、加入与切换将在下一阶段开放；当前可管理本账号的密码与恢复码。
      </p>
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

.account-page__hint {
  margin-bottom: var(--ui-space-4);
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
