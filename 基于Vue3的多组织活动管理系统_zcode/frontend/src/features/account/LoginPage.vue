<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElForm, ElFormItem, ElInput, type FormInstance, type FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import AuthLayout from '../../layouts/AuthLayout.vue'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMessage = ref('')
const fields = reactive({ username: '', password: '' })

const rules: FormRules = {
  username: [{ required: true, whitespace: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/** 登录回跳仅允许站内相对路径，拒绝外部地址与协议相对地址（开发规范 2.4）。 */
function safeNext(): string {
  const next = typeof route.query.next === 'string' ? route.query.next : ''
  if (next.startsWith('/') && !next.startsWith('//')) {
    return next
  }
  return '/'
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await session.login(fields.username, fields.password)
    await router.replace(safeNext())
  } catch (error) {
    if (error instanceof ApiError && !error.isTransportError) {
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        errorMessage.value = '安全校验已过期，请重新提交。'
      } else if (error.code === 'RATE_LIMITED') {
        errorMessage.value = '登录尝试过于频繁，请稍后再试。'
      } else {
        errorMessage.value = '账号或密码不正确。'
      }
    } else {
      errorMessage.value = '网络连接失败，请检查网络后重试。'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthLayout>
    <p class="auth-eyebrow">
      欢迎回来
    </p>
    <h1 class="auth-heading">
      继续，把事情做好。
    </h1>
    <p class="auth-intro">
      登录你的工作空间，与组织保持同步。
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
          :prefix-icon="User"
          autocomplete="username"
          placeholder="注册时设置的用户名"
        />
      </ElFormItem>
      <ElFormItem
        label="密码"
        prop="password"
      >
        <ElInput
          v-model="fields.password"
          :prefix-icon="Lock"
          type="password"
          show-password
          autocomplete="current-password"
          placeholder="请输入密码"
        />
      </ElFormItem>
      <ElButton
        type="primary"
        native-type="submit"
        class="auth-submit"
        :loading="submitting"
      >
        进入工作区
      </ElButton>
    </ElForm>

    <nav class="auth-links">
      <RouterLink to="/recover">
        忘记密码或丢失恢复码登录？
      </RouterLink>
      <RouterLink to="/register">
        注册新账号
      </RouterLink>
    </nav>
  </AuthLayout>
</template>
