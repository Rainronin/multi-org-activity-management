<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElButton, ElTable, ElTag } from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { advanceTask, fetchMyTasks, type TaskItem } from './p3'

// 我的任务（P3.3）：当前组织内分配给我的未完成任务，可推进状态。
const route = useRoute()
const session = useSessionStore()

const orgId = computed(() => String(route.params.orgId ?? ''))

const tasks = ref<TaskItem[]>([])
const loading = ref(false)
const errorText = ref('')
const actionError = ref('')
const actingId = ref('')

const statusLabel: Record<string, string> = {
  PENDING: '待处理',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}

let requestSeq = 0

async function loadTasks(): Promise<void> {
  if (!orgId.value) {
    return
  }
  const seq = ++requestSeq
  const gen = session.capture()
  loading.value = true
  errorText.value = ''
  try {
    const items = await fetchMyTasks(orgId.value)
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    tasks.value = items
  } catch (error) {
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    tasks.value = []
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '任务列表加载失败，请重试。'
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

watch(orgId, loadTasks)
onMounted(loadTasks)

async function guard(fn: () => Promise<void>): Promise<void> {
  actionError.value = ''
  try {
    await fn()
  } catch (error) {
    if (session.observeUnauthorized(error, session.capture())) {
      return
    }
    if (error instanceof ApiError && !error.isTransportError) {
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        actionError.value = '安全校验已过期，请重试。'
      } else {
        actionError.value = error.detail || '操作失败，请重试。'
      }
    } else {
      actionError.value = '网络连接失败，请检查网络后重试。'
    }
  }
}

function nextStatus(task: TaskItem): string | null {
  if (task.status === 'PENDING') return 'IN_PROGRESS'
  if (task.status === 'IN_PROGRESS') return 'COMPLETED'
  return null
}

function advance(task: TaskItem) {
  const next = nextStatus(task)
  if (!next) {
    return
  }
  actingId.value = task.taskId
  void guard(async () => {
    await advanceTask(orgId.value, task.activityId.toString(), task.taskId, next)
    await loadTasks()
  }).finally(() => {
    actingId.value = ''
  })
}

function priorityTag(priority: string): 'danger' | 'info' | 'warning' {
  return priority === 'HIGH' ? 'danger' : priority === 'LOW' ? 'info' : 'warning'
}

function priorityLabel(priority: string): string {
  return priority === 'HIGH' ? '高' : priority === 'LOW' ? '低' : '中'
}
</script>

<template>
  <div class="my-tasks">
    <div class="page-heading">
      <div>
        <p class="page-eyebrow">
          个人协作 / 我的任务
        </p>
        <h1>我的任务</h1>
        <p class="page-description">
          当前组织内分配给你的未完成任务；完成后由组织负责人汇总查看。
        </p>
      </div>
    </div>

    <p
      v-if="errorText"
      class="my-tasks__error"
      role="alert"
    >
      {{ errorText }}
    </p>
    <p
      v-if="actionError"
      class="my-tasks__error"
      role="alert"
    >
      {{ actionError }}
    </p>

    <div
      v-loading="loading"
      class="my-tasks__table"
    >
      <ElTable :data="tasks">
        <ElTableColumn
          prop="title"
          label="任务"
          min-width="180"
        />
        <ElTableColumn
          label="优先级"
          min-width="90"
        >
          <template #default="{ row }">
            <ElTag
              :type="priorityTag(row.priority)"
              size="small"
            >
              {{ priorityLabel(row.priority) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="截止时间"
          min-width="150"
        >
          <template #default="{ row }">
            <span :class="{ 'my-tasks__overdue': row.overdue }">
              {{ row.dueAt ? formatDue(row.dueAt) : '—' }}{{ row.overdue ? '（已逾期）' : '' }}
            </span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="状态"
          min-width="100"
        >
          <template #default="{ row }">
            <ElTag
              :type="row.status === 'IN_PROGRESS' ? 'warning' : 'info'"
              size="small"
            >
              {{ statusLabel[row.status] ?? row.status }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          min-width="120"
        >
          <template #default="{ row }">
            <ElButton
              v-if="nextStatus(row)"
              size="small"
              type="primary"
              plain
              :loading="actingId === row.taskId"
              @click="advance(row)"
            >
              {{ nextStatus(row) === 'IN_PROGRESS' ? '开始处理' : '标记完成' }}
            </ElButton>
          </template>
        </ElTableColumn>
        <template #empty>
          <span class="my-tasks__note">暂无分配给你的未完成任务。</span>
        </template>
      </ElTable>
    </div>
  </div>
</template>

<script lang="ts">
function formatDue(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value))
}
</script>

<style scoped>
.my-tasks {
  min-width: 0;
}

.page-eyebrow {
  margin-bottom: var(--ui-space-3);
  color: var(--ui-color-selected-text);
  font-size: var(--ui-font-size-caption);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
}

.page-heading h1 {
  font-size: var(--ui-font-size-page-title);
  font-weight: var(--ui-font-weight-bold);
  letter-spacing: var(--ui-letter-spacing-title);
}

.page-description {
  margin-top: var(--ui-space-3);
  color: var(--ui-color-text-secondary);
  line-height: var(--ui-line-height-body);
}

.my-tasks__error {
  margin-top: var(--ui-space-4);
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.my-tasks__table {
  margin-top: var(--ui-space-8);
  width: 100%;
}

.my-tasks__overdue {
  color: var(--ui-color-danger);
}

.my-tasks__note {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}
</style>
