<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { describeHealth, type HealthReport } from './shared/utils/health'

const report = ref<HealthReport | null>(null)
const errorText = ref('')
const loading = ref(true)

// P0.1 临时联通检查；统一 Fetch client 与错误结构随 P0.3 建立。
async function loadHealth(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const response = await fetch('/api/health')
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    report.value = (await response.json()) as HealthReport
  } catch {
    errorText.value = '无法连接后端服务，请确认 backend 已启动。'
  } finally {
    loading.value = false
  }
}

onMounted(loadHealth)
</script>

<template>
  <main>
    <h1>多组织活动管理系统</h1>
    <p>当前为 P0.1 前后端联通检查页；正式页面按开发流程从 P1 起逐页实现。</p>
    <p v-if="loading">
      正在检查后端连接……
    </p>
    <p v-else-if="errorText">
      {{ errorText }}
    </p>
    <p v-else-if="report">
      {{ describeHealth(report) }}
    </p>
    <button
      type="button"
      @click="loadHealth"
    >
      重新检查
    </button>
  </main>
</template>
