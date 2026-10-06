<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
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
const session = useSessionStore()
const pendingCode = ref<string | null>(null)
let alive = true
let dialogGeneration = 0

function resetDraft(): void {
  fields.code = ''
  pendingCode.value = null
  errorMessage.value = ''
}

async function confirmLeave(): Promise<boolean> {
  if (submitting.value) return false
  if (!pendingCode.value && !fields.code.trim()) return true
  try {
    await ElMessageBox.confirm(pendingCode.value
      ? '加入结果尚未确认。离开后请先核对组织目录，确定放弃核对？'
      : '邀请码尚未提交，确定放弃填写？', '未保存的修改', {
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
  if (submitting.value || pendingCode.value || fields.code.trim()) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => {
  alive = false
  window.removeEventListener('beforeunload', beforeUnload)
})

const rules: FormRules = {
  code: [{ required: true, whitespace: true, message: '请输入负责人分享的邀请码', trigger: 'blur' }],
}

watch(visible, (value) => {
  dialogGeneration++
  if (value && !pendingCode.value) resetDraft()
}, { flush: 'sync' })

async function submit() {
  if (submitting.value) return
  const gen = session.capture()
  const scope = dialogGeneration
  const isCurrent = () => alive && visible.value && gen === session.generation && scope === dialogGeneration
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || !isCurrent()) {
    return
  }
  submitting.value = true
  errorMessage.value = ''
  if (!pendingCode.value) pendingCode.value = fields.code.trim()
  try {
    const result = await acceptInvitation(pendingCode.value)
    if (!isCurrent()) return
    resetDraft()
    submitting.value = false
    visible.value = false
    emit('joined', result)
  } catch (error) {
    if (session.observeUnauthorized(error, gen) || !isCurrent()) return
    if (error instanceof ApiError && !error.isTransportError) {
      if ([400, 403, 404, 422, 429].includes(error.status)) pendingCode.value = null
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
    if (scope === dialogGeneration) submitting.value = false
  }
}
</script>

<template>
  <ElDialog
    v-model="visible"
    title="使用邀请码加入组织"
    width="min(var(--ui-dialog-width), calc(100vw - var(--ui-space-8)))"
    :before-close="close"
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
      :disabled="submitting || pendingCode !== null"
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
      {{ pendingCode ? '加入结果尚未确认，请用原邀请码核对；也可查看组织目录确认成员身份。' : '接受邀请将以普通成员身份加入组织；重复提交不会重复加入。' }}
    </p>
    <template #footer>
      <ElButton
        :disabled="submitting"
        @click="close()"
      >
        取消
      </ElButton>
      <ElButton
        type="primary"
        :loading="submitting"
        @click="submit"
      >
        {{ pendingCode ? '核对加入结果' : '加入组织' }}
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
