<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, type FormInstance, type FormRules } from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential } from '../../stores/session'
import { createOrganization } from './api'
import type { OrganizationDirectoryItem } from './types'

const visible = defineModel<boolean>({ required: true })

const emit = defineEmits<{
  created: [organization: OrganizationDirectoryItem]
}>()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMessage = ref('')
const fields = reactive({ name: '', description: '' })

const rules: FormRules = {
  name: [
    { required: true, whitespace: true, message: '请输入组织名称', trigger: 'blur' },
    { max: 64, message: '组织名称不超过 64 字符', trigger: 'blur' },
  ],
  description: [{ max: 512, message: '组织简介不超过 512 字符', trigger: 'blur' }],
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    const organization = await createOrganization({
      name: fields.name.trim(),
      description: fields.description.trim() || undefined,
    })
    visible.value = false
    fields.name = ''
    fields.description = ''
    emit('created', organization)
  } catch (error) {
    if (error instanceof ApiError && !error.isTransportError) {
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        errorMessage.value = '安全校验已过期，请重新提交。'
      } else {
        errorMessage.value = error.detail || '创建失败，请稍后重试。'
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
    title="创建组织"
    width="var(--ui-dialog-width)"
  >
    <p
      v-if="errorMessage"
      class="create-org__error"
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
        label="组织名称"
        prop="name"
      >
        <ElInput
          v-model="fields.name"
          placeholder="1-64 字符，例如：计算机协会"
        />
      </ElFormItem>
      <ElFormItem
        label="组织简介"
        prop="description"
      >
        <ElInput
          v-model="fields.description"
          type="textarea"
          :rows="3"
          placeholder="选填，简单介绍组织的定位与活动方向"
        />
      </ElFormItem>
    </ElForm>
    <p class="create-org__note">
      创建者将自动成为本组织的负责人。
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
        创建
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.create-org__error {
  margin-bottom: var(--ui-space-4);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.create-org__note {
  margin-top: var(--ui-space-2);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}
</style>
