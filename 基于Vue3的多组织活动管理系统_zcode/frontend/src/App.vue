<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElButton, ElDialog, ElSkeleton, ElSkeletonItem, ElTag } from 'element-plus'
import { request } from './shared/http/client'
import { ApiError } from './shared/http/error'
import { describeHealth, type HealthReport } from './shared/utils/health'

const report = ref<HealthReport | null>(null)
const errorText = ref('')
const loading = ref(false)
const detailVisible = ref(false)

// P0.1 联通检查页；P0.2 起兼作首批 Element Plus 组件与弹层主题验证载体。
// 正式业务页面按开发流程从 P1 起逐页实现，本页不承载业务功能。
async function loadHealth(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const response = await request<HealthReport>('/api/health')
    report.value = response.data
  } catch (error) {
    if (error instanceof ApiError && !error.isTransportError) {
      errorText.value = `后端返回错误：${error.detail}（${error.code}）`
    } else {
      errorText.value = '无法连接后端服务，请确认 backend 已启动。'
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadHealth)
</script>

<template>
  <main class="connect-page">
    <h1>多组织活动管理系统</h1>
    <p class="connect-page__context">
      当前为 P0 前后端联通检查页；正式页面按开发流程从 P1 起逐页实现。
    </p>

    <ElSkeleton
      class="connect-page__skeleton"
      :loading="loading"
      animated
      :count="2"
    >
      <template #template>
        <ElSkeletonItem
          variant="rect"
          class="connect-page__skeleton-item"
        />
      </template>
      <template #default>
        <div class="connect-page__status">
          <ElTag
            v-if="report"
            :type="report.status === 'UP' && report.database === 'UP' ? 'success' : 'warning'"
            size="large"
          >
            {{ describeHealth(report) }}
          </ElTag>
          <p
            v-else-if="errorText"
            class="connect-page__error"
            role="alert"
          >
            {{ errorText }}
          </p>
          <ElButton
            type="primary"
            @click="loadHealth"
          >
            重新检查
          </ElButton>
          <ElButton
            v-if="report"
            plain
            @click="detailVisible = true"
          >
            查看详情
          </ElButton>
        </div>
      </template>
    </ElSkeleton>

    <ElDialog
      v-model="detailVisible"
      title="健康检查详情"
      width="var(--ui-dialog-width)"
    >
      <dl
        v-if="report"
        class="connect-page__detail"
      >
        <dt>服务状态</dt>
        <dd>{{ report.status }}</dd>
        <dt>数据库状态</dt>
        <dd>{{ report.database }}</dd>
        <dt>检查时间</dt>
        <dd>{{ report.checkedAt }}</dd>
      </dl>
      <template #footer>
        <ElButton @click="detailVisible = false">
          关闭
        </ElButton>
      </template>
    </ElDialog>
  </main>
</template>

<style scoped>
.connect-page {
  display: grid;
  gap: var(--ui-layout-grid-gap);
  max-width: var(--ui-form-max-width);
  padding: var(--ui-layout-page-padding);
}

.connect-page__context {
  color: var(--ui-color-text-secondary);
}

.connect-page__status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ui-space-3);
}

.connect-page__error {
  color: var(--ui-color-danger);
}

.connect-page__skeleton {
  display: block;
  min-height: var(--ui-control-height-large);
}

.connect-page__skeleton-item {
  width: 100%;
  height: var(--ui-control-height-large);
}

.connect-page__detail {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 2fr);
  gap: var(--ui-space-2) var(--ui-space-4);
  margin: 0;
}

.connect-page__detail dt {
  color: var(--ui-color-text-muted);
}

.connect-page__detail dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
</style>
