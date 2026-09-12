<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, type FormInstance, type FormRules } from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential } from '../../stores/session'
import { acceptInvitation } from './api'
import type { AcceptInvitationResult } from './types'

const visible = defineModel<boolean>({ required: true })

const emit = defineEmits<{
  joined: [organization: AcceptInvitationResult]
}>()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMessage = ref('')
const fields = reactive({ code: '' })

const rules: FormRules = {
  code: [{ required: true, whitespace: true, message: '请输入负责人分享的邀请码', trigger: 'blur' }],
}

watch(visible, (value) => {
  if (value) {
    errorMessage.value = ''
    fields.code = ''
  }
})

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    const result = await acceptInvitation(fields.code)
    visible.value = false
    emit('joined', result)
  } catch (error) {
    if (error instanceof ApiError && !error.isTransportError) {
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        errorMessage.value = '安全校验已过期，请重新提交。'
      } else if (error.code === 'INVITATION_INVALID') {
        errorMessage.value = '邀请码无效、已过期或已被用完，请向组织负责人确认。'
      } else {
        errorMessage.value = error.detail || '加入失败，请稍后重试。'
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
  <ElDialog
    v-model="visible"
    title="使用邀请码加入组织"
    width="var(--ui-dialog-width)"
  >
    <p
      v-if="errorMessage"
      class="join-org__error"
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
        label="邀请码"
        prop="code"
      >
        <ElInput
          v-model="fields.code"
          placeholder="负责人分享的分段邀请码"
          autocomplete="off"
        />
      </ElFormItem>
    </ElForm>
    <p class="join-org__note">
      接受邀请将以普通成员身份加入组织；重复提交不会重复加入。
    </p>
    <template #footer>
      <ElButton @click="visible = false">
        取消
      </ElButton>
      <ElButton
        type="primary"
        :loading="submitting"
        @click="submit"
      >
        加入组织
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.join-org__error {
  margin-bottom: var(--ui-space-4);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.join-org__note {
  margin-top: var(--ui-space-2);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}
</style>
