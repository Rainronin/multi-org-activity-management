<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton, ElForm, ElFormItem, ElInput, type FormInstance, type FormRules } from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential } from '../../stores/session'
import { register } from './api'
import AuthLayout from '../../layouts/AuthLayout.vue'
import RecoveryCodeDialog from './RecoveryCodeDialog.vue'

const router = useRouter()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMessage = ref('')
const fields = reactive({ username: '', password: '', confirmPassword: '' })
const codeDialogVisible = ref(false)
let alive = true
onBeforeUnmount(() => { alive = false })
const issuedRecoveryCode = ref('')

const rules: FormRules = {
  username: [
    { required: true, whitespace: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{3,32}$/, message: '用户名需 3-32 位字母、数字或下划线', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,72}$/, message: '密码需 8-72 位且同时包含字母和数字', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (value !== fields.password) {
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
  if (submitting.value || codeDialogVisible.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid || !alive) return
    const result = await register({ username: fields.username, password: fields.password })
    if (!alive) return
    issuedRecoveryCode.value = result.recoveryCode
    codeDialogVisible.value = true
  } catch (error) {
    if (!alive) return
    if (error instanceof ApiError && !error.isTransportError && (error.status ?? 0) < 500) {
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        errorMessage.value = '安全校验已过期，请重新提交。'
      } else if (error.code === 'USERNAME_TAKEN') {
        errorMessage.value = '该用户名已被注册。若刚才注册结果尚未确认，请先用本次账号和密码登录核对；否则请换一个。'
      } else if (error.code === 'RATE_LIMITED') {
        errorMessage.value = '注册尝试过于频繁，请稍后再试。'
      } else {
        errorMessage.value = error.detail || '注册失败，请检查输入后重试。'
      }
    } else {
      errorMessage.value = '注册结果尚未确认。请先用本次账号和密码登录核对；登录成功后可重签恢复码。'
    }
  } finally {
    submitting.value = false
  }
}

function finishRegistration() {
  void router.replace('/login')
}
</script>

<template>
  <AuthLayout>
    <p class="auth-eyebrow">
      创建账号
    </p>
    <h1 class="auth-heading">
      开始，一起做活动。
    </h1>
    <p class="auth-intro">
      注册后你将获得一个工作区账号，可创建或加入组织。
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
      :disabled="submitting || codeDialogVisible"
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
          placeholder="3-32 位字母、数字或下划线"
        />
      </ElFormItem>
      <ElFormItem
        label="密码"
        prop="password"
      >
        <ElInput
          v-model="fields.password"
          type="password"
          show-password
          autocomplete="new-password"
          placeholder="8-72 位，含字母和数字"
        />
      </ElFormItem>
      <ElFormItem
        label="确认密码"
        prop="confirmPassword"
      >
        <ElInput
          v-model="fields.confirmPassword"
          type="password"
          show-password
          autocomplete="new-password"
          placeholder="再次输入密码"
        />
      </ElFormItem>
      <ElButton
        type="primary"
        native-type="submit"
        class="auth-submit"
        :loading="submitting"
      >
        注册
      </ElButton>
    </ElForm>

    <nav class="auth-links">
      <RouterLink to="/login">
        已有账号？去登录
      </RouterLink>
    </nav>

    <RecoveryCodeDialog
      v-model="codeDialogVisible"
      :submitting="submitting"
      :code="issuedRecoveryCode"
      confirm-target="恢复码"
      @confirmed="finishRegistration"
    />
  </AuthLayout>
</template>
