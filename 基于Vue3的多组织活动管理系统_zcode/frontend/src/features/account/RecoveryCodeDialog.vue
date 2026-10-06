<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElButton, ElCheckbox, ElDialog } from 'element-plus'

const props = defineProps<{
  modelValue: boolean
  code: string
  confirmTarget: string
  submitting?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirmed: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const acknowledged = ref(false)

watch(visible, (value) => {
  if (value) {
    acknowledged.value = false
  }
})

// 发放中及尚未确认保存时保留当前页面，恢复码不进入持久存储。
const canLeave = () => !props.submitting && !visible.value
onBeforeRouteLeave(canLeave)
function beforeUnload(event: BeforeUnloadEvent): void {
  if (!canLeave()) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

async function confirm() {
  if (!acknowledged.value) return
  visible.value = false
  await nextTick()
  emit('confirmed')
}
</script>

<template>
  <!-- 恢复码仅展示一次（接口说明消费约定）；确认已保存后才能离开。 -->
  <ElDialog
    v-model="visible"
    title="请保存你的恢复码"
    width="min(var(--ui-dialog-width), calc(100vw - var(--ui-space-8)))"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
  >
    <p
      class="recovery-code-dialog__warning"
      role="alert"
    >
      这是{{ confirmTarget }}唯一一次展示机会。恢复码用于找回账号，系统不提供短信或邮件重发。
    </p>
    <p class="recovery-code-dialog__code">
      {{ code }}
    </p>
    <ElCheckbox v-model="acknowledged">
      我已将恢复码保存在安全的地方
    </ElCheckbox>
    <template #footer>
      <ElButton
        type="primary"
        :disabled="!acknowledged"
        @click="confirm"
      >
        确认已保存
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.recovery-code-dialog__warning {
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-prose);
}

.recovery-code-dialog__code {
  margin: var(--ui-space-4) 0;
  padding: var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-surface-muted);
  color: var(--ui-color-text);
  font-family: var(--ui-font-family-data);
  font-size: var(--ui-font-size-section);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
  text-align: center;
  overflow-wrap: anywhere;
}
</style>
