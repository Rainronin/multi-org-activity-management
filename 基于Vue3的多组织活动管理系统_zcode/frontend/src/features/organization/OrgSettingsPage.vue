<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AiConfigSection from '../ai/AiConfigSection.vue'

// 组织设置（P8.1）：当前承载 AI 模型配置。配置数据归 modules/ai，
// organization 模块不保存第二份密钥；权限由后端逐请求判定。
const route = useRoute()
const orgId = computed(() => String(route.params.orgId ?? ''))
</script>

<template>
  <div
    class="settings-page"
    data-page="org-settings"
  >
    <header class="settings-page__head">
      <h1>组织设置</h1>
      <p class="settings-page__intro">
        组织级配置对全部成员生效；密钥等敏感信息只提交到本系统后端并加密保存，读取不回显。
      </p>
    </header>
    <AiConfigSection :org-id="orgId" />
  </div>
</template>

<style scoped>
.settings-page {
  display: flex;
  flex-direction: column;
  gap: var(--ui-layout-grid-gap);
}

.settings-page__head h1 {
  margin: 0;
  font-size: var(--ui-font-size-page-title);
}

.settings-page__intro {
  margin: var(--ui-space-2) 0 0;
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}
</style>
