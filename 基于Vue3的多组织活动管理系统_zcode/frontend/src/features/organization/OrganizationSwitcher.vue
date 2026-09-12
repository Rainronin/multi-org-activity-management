<script setup lang="ts">
import { computed } from 'vue'
import { ElOption, ElSelect } from 'element-plus'
import { roleLabel, type OrganizationDirectoryItem } from './types'

const props = defineProps<{
  organizations: OrganizationDirectoryItem[]
  modelValue: string
}>()

const emit = defineEmits<{
  change: [orgId: string]
}>()

const current = computed(() =>
  props.organizations.find((org) => org.id === props.modelValue),
)

function initials(org: OrganizationDirectoryItem | undefined): string {
  return org ? org.name.trim().charAt(0) : ''
}

function onChange(orgId: string): void {
  if (orgId && orgId !== props.modelValue) {
    emit('change', orgId)
  }
}
</script>

<template>
  <!-- 可搜索组织选择（UI 规范 5.1 / 8）：切换只发出事件，导航由布局协调。 -->
  <ElSelect
    class="organization-select"
    :model-value="modelValue"
    filterable
    aria-label="切换组织"
    popper-class="organization-popper"
    :title="current?.name"
    @change="onChange"
  >
    <template #prefix>
      <span class="u-org-initial">{{ initials(current) }}</span>
    </template>
    <ElOption
      v-for="org in organizations"
      :key="org.id"
      :label="org.name"
      :value="org.id"
    >
      <span class="org-option">
        <b>{{ initials(org) }}</b>
        <span>
          {{ org.name }}
          <small>{{ roleLabel(org.role) }} · {{ org.joinedAt.slice(0, 10) }} 加入</small>
        </span>
      </span>
    </ElOption>
  </ElSelect>
</template>

<style>
/* 组织下拉 popper 传送到 body，使用全局命名样式；数值全部来自 token。 */
.organization-popper .el-select-dropdown__item {
  min-height: var(--ui-control-height-large);
  padding: var(--ui-space-2) var(--ui-space-3);
}

.org-option {
  display: flex;
  gap: var(--ui-space-3);
  align-items: center;
  min-height: var(--ui-control-height);
  font-size: var(--ui-font-size-caption);
  line-height: var(--ui-line-height-body);
}

.org-option b {
  width: var(--ui-nav-logo-size);
  height: var(--ui-nav-logo-size);
  display: grid;
  place-items: center;
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-selected-bg);
  color: var(--ui-color-selected-text);
  font-weight: var(--ui-font-weight-semibold);
  flex-shrink: 0;
}

.org-option small {
  display: block;
  font-size: var(--ui-font-size-caption);
  color: var(--ui-color-text-muted);
}
</style>
