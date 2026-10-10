<script setup lang="ts">
import { computed, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import AiConfigSection from '../ai/AiConfigSection.vue'

// 组织设置（P8.1）：当前承载 AI 模型配置。配置数据归 modules/ai，
// organization 模块不保存第二份密钥；权限由后端逐请求判定。
const route = useRoute()
const orgId = computed(() => String(route.params.orgId ?? ''))
const aiConfig = ref<InstanceType<typeof AiConfigSection> | null>(null)
async function confirmLeave(): Promise<boolean> {
  return aiConfig.value ? aiConfig.value.confirmLeave() : true
}
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate((to, from) => to.path === from.path ? true : confirmLeave())
</script>

<template>
  <div
    class="settings-page"
    data-page="org-settings"
  >
    <div class="page-heading">
      <div class="page-heading__title">
        <p class="page-eyebrow">
          组织协作 / 设置
        </p>
        <h1>组织设置</h1>
        <p class="page-description">
          组织级配置对全部成员生效；密钥只提交到本系统后端并加密保存，读取不回显。
        </p>
      </div>
    </div>
    <AiConfigSection
      ref="aiConfig"
      :org-id="orgId"
    />
  </div>
</template>

<style scoped>
.settings-page {
  display: flex;
  flex-direction: column;
  gap: var(--ui-layout-grid-gap);
}

/* 页头排版与工作台、表格页同口径；数值全部取自 token。 */
.page-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ui-space-6);
  flex-wrap: wrap;
}

.page-heading__title {
  min-width: 0;
}

.page-eyebrow {
  margin-bottom: var(--ui-space-3);
  color: var(--ui-color-selected-text);
  font-size: var(--ui-font-size-caption);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
}

.page-description {
  margin-top: var(--ui-space-3);
  color: var(--ui-color-text-secondary);
  line-height: var(--ui-line-height-body);
}
</style>
