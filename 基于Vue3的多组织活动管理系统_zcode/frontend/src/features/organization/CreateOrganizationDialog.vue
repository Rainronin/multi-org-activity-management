<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { newIdempotencyKey } from '../../shared/http/idempotency'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { createOrganization } from './api'
import type { CreateOrganizationInput, OrganizationDirectoryItem } from './types'

const visible = defineModel<boolean>({ required: true })

const emit = defineEmits<{
  created: [organization: OrganizationDirectoryItem]
}>()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMessage = ref('')
const fields = reactive({ name: '', description: '' })
const session = useSessionStore()
const pendingCreate = ref<{ input: CreateOrganizationInput; key: string } | null>(null)

function resetDraft(): void {
  fields.name = ''
  fields.description = ''
  pendingCreate.value = null
  errorMessage.value = ''
}

async function confirmLeave(): Promise<boolean> {
  if (submitting.value) return false
  if (!pendingCreate.value && !fields.name.trim() && !fields.description.trim()) return true
  try {
    await ElMessageBox.confirm(pendingCreate.value
      ? '创建结果尚未确认。离开后请先核对组织目录，避免重复创建。确定放弃核对？'
      : '组织信息尚未保存，确定放弃填写？', '未保存的修改', {
      confirmButtonText: '放弃', cancelButtonText: '继续填写', type: 'warning',
    })
    return true
  } catch { return false }
}

async function close(done: () => void = () => { visible.value = false }): Promise<void> {
  if (!(await confirmLeave())) return
  resetDraft()
  done()
}

onBeforeRouteLeave(async () => {
  if (!(await confirmLeave())) return false
  resetDraft()
  return true
})
function beforeUnload(event: BeforeUnloadEvent): void {
  if (submitting.value || pendingCreate.value || fields.name.trim() || fields.description.trim()) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

const rules: FormRules = {
  name: [
    { required: true, whitespace: true, message: '请输入组织名称', trigger: 'blur' },
    { max: 64, message: '组织名称不超过 64 字符', trigger: 'blur' },
  ],
  description: [{ max: 512, message: '组织简介不超过 512 字符', trigger: 'blur' }],
}

async function submit() {
  if (submitting.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  errorMessage.value = ''
  const gen = session.capture()
  if (!pendingCreate.value) pendingCreate.value = {
    input: { name: fields.name.trim(), description: fields.description.trim() || undefined }, key: newIdempotencyKey(),
  }
  const intent = pendingCreate.value
  try {
    const organization = await createOrganization(intent.input, intent.key)
    if (gen !== session.generation) return
    resetDraft()
    visible.value = false
    emit('created', organization)
  } catch (error) {
    if (gen !== session.generation || session.observeUnauthorized(error, gen)) return
    if (error instanceof ApiError && !error.isTransportError) {
      if ([400, 403, 404, 422, 429].includes(error.status)) pendingCreate.value = null
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
    width="min(var(--ui-dialog-width), calc(100vw - var(--ui-space-8)))"
    :before-close="close"
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
      :disabled="submitting || Boolean(pendingCreate)"
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
      {{ pendingCreate ? '创建结果尚未确认，请用原内容核对，避免重复创建。' : '创建者将自动成为本组织的负责人。' }}
    </p>
    <template #footer>
      <ElButton @click="close()">
        取消
      </ElButton>
      <ElButton
        type="primary"
        :loading="submitting"
        @click="submit"
      >
        {{ pendingCreate && !submitting ? '核对创建结果' : '创建' }}
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
