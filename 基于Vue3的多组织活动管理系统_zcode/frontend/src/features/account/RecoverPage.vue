<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton, ElForm, ElFormItem, ElInput, type FormInstance, type FormRules } from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential } from '../../stores/session'
import { recover } from './api'
import AuthLayout from '../../layouts/AuthLayout.vue'
import RecoveryCodeDialog from './RecoveryCodeDialog.vue'

const router = useRouter()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMessage = ref('')
const fields = reactive({ username: '', recoveryCode: '', newPassword: '', confirmPassword: '' })
const codeDialogVisible = ref(false)
const newRecoveryCode = ref('')

const rules: FormRules = {
  username: [{ required: true, whitespace: true, message: '请输入用户名', trigger: 'blur' }],
  recoveryCode: [{ required: true, whitespace: true, message: '请输入注册时保存的恢复码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,72}$/, message: '密码需 8-72 位且同时包含字母和数字', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (value !== fields.newPassword) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    const result = await recover({
      username: fields.username,
      recoveryCode: fields.recoveryCode,
      newPassword: fields.newPassword,
    })
    newRecoveryCode.value = result.recoveryCode
    codeDialogVisible.value = true
  } catch (error) {
    if (error instanceof ApiError && !error.isTransportError) {
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        errorMessage.value = '安全校验已过期，请重新提交。'
      } else if (error.code === 'RATE_LIMITED') {
        errorMessage.value = '恢复尝试过于频繁，请稍后再试。'
      } else {
        // 统一失败提示，不区分账号不存在与恢复码错误。
        errorMessage.value = '账号或恢复码不正确，请检查后重试。'
      }
    } else {
      errorMessage.value = '网络连接失败，请检查网络后重试。'
    }
  } finally {
    submitting.value = false
  }
}

function finishRecovery() {
  void router.replace('/login')
}
</script>

<template>
  <AuthLayout>
    <p class="auth-eyebrow">
      找回账号
    </p>
    <h2 class="auth-heading">
      用恢复码重新取得控制权。
    </h2>
    <p class="auth-intro">
      恢复成功后旧密码与旧恢复码立即失效，系统会发放新的恢复码。
    </p>

    <p
      v-if="errorMessage"
      class="auth-error"
      role="alert"
    >
      {{ errorMessage }}
    </p>

    <ElForm
      ref="formRef"
      :model="fields"
      :rules="rules"
      label-position="top"
      @submit.prevent="submit"
    >
      <ElFormItem
        label="用户名"
        prop="username"
      >
        <ElInput
          v-model="fields.username"
          autocomplete="username"
          placeholder="注册时设置的用户名"
        />
      </ElFormItem>
      <ElFormItem
        label="恢复码"
        prop="recoveryCode"
      >
        <ElInput
          v-model="fields.recoveryCode"
          autocomplete="off"
          placeholder="注册时保存的分段恢复码"
        />
      </ElFormItem>
      <ElFormItem
        label="新密码"
        prop="newPassword"
      >
        <ElInput
          v-model="fields.newPassword"
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
          v-model="fields.confirmPassword"
          type="password"
          show-password
          autocomplete="new-password"
          placeholder="再次输入新密码"
        />
      </ElFormItem>
      <ElButton
        type="primary"
        native-type="submit"
        class="auth-submit"
        :loading="submitting"
      >
        恢复账号
      </ElButton>
    </ElForm>

    <nav class="auth-links">
      <RouterLink to="/login">
        返回登录
      </RouterLink>
    </nav>

    <RecoveryCodeDialog
      v-model="codeDialogVisible"
      :code="newRecoveryCode"
      confirm-target="新恢复码"
      @confirmed="finishRecovery"
    />
  </AuthLayout>
</template>
