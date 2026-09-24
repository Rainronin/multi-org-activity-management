<script setup lang="ts">
import { readUiLength } from '../../shared/utils/readUiToken'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElRadioButton, ElRadioGroup, ElTable, ElTableColumn, ElTag } from 'element-plus'
import ListPage from '../../shared/components/ListPage.vue'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { advanceTask, fetchMyTasks, type TaskItem } from './p3'

// 我的任务（P3.3）：当前组织内分配给我的未完成任务，可推进状态。
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const orgId = computed(() => String(route.params.orgId ?? ''))

/** P3.3：从本人任务回到所属活动详情（任务只带 activityId，按活动 id 跳转）。 */
function openActivity(task: TaskItem) {
  void router.push(`/org/${orgId.value}/activities/${task.activityId}?tab=tasks`)
}

const tasks = ref<TaskItem[]>([])
const loading = ref(false)
const refreshing = ref(false)
const loadedOnce = ref(false)
const errorText = ref('')
const actionError = ref('')
const actingId = ref('')

const statusLabel: Record<string, string> = {
  PENDING: '待处理',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}

const priorityLabel: Record<string, string> = { HIGH: '高', MEDIUM: '中', LOW: '低' }

// R03 模式：请求序号 + 会话代次双守卫。
let requestSeq = 0

async function loadTasks(): Promise<void> {
  if (!orgId.value) {
    return
  }
  const seq = ++requestSeq
  const gen = session.capture()
  // 已有数据时保留内容，只提示正在更新；空态与骨架互斥（UI 规范第 10 节）。
  if (tasks.value.length > 0) {
    refreshing.value = true
  } else {
    loading.value = true
  }
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
      refreshing.value = false
      loadedOnce.value = true
    }
  }
}

watch(orgId, () => {
  tasks.value = []
  loadedOnce.value = false
  errorText.value = ''
  actionError.value = ''
  statusFilter.value = ''
  void loadTasks()
})
onMounted(loadTasks)

/** P3.3：本人任务按状态筛选（服务端返回全部本人任务，前端筛选不改变权限口径）。 */
const statusFilter = ref('')
const visibleTasks = computed(() => (statusFilter.value
  ? tasks.value.filter((task) => task.status === statusFilter.value)
  : tasks.value))

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

function formatDue(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'short' }).format(new Date(value))
}

const emptyText = computed(() => (statusFilter.value
  ? '当前筛选下没有任务，可切回“全部”查看。'
  : '暂无分配给你的未完成任务；活动负责人分派后会出现在这里。'))
</script>

<template>
  <ListPage
    title="我的任务"
    panel-title="任务明细"
    eyebrow="个人协作 / 我的任务"
    description="当前组织内分配给你的未完成任务；完成后由组织负责人汇总查看。"
    context="本人被分派 · 未完成"
    :error="errorText"
    :loading="loading"
    :refreshing="refreshing"
    :loaded="loadedOnce"
    :row-count="visibleTasks.length"
    :total="visibleTasks.length"
    :empty-text="emptyText"
    :skeleton-columns="5"
    retry-text="重新加载"
    @retry="loadTasks"
  >
    <template #filters>
      <ElRadioGroup
        v-model="statusFilter"
        class="my-tasks__filter"
        aria-label="按任务状态筛选"
      >
        <ElRadioButton value="">
          全部
        </ElRadioButton>
        <ElRadioButton value="PENDING">
          待处理
        </ElRadioButton>
        <ElRadioButton value="IN_PROGRESS">
          进行中
        </ElRadioButton>
      </ElRadioGroup>
    </template>

    <p
      v-if="actionError"
      class="my-tasks__error"
      role="alert"
    >
      {{ actionError }}
    </p>

    <ElTable
      class="list-table"
      :data="visibleTasks"
    >
      <ElTableColumn
        label="任务"
        :min-width="readUiLength('--ui-table-col-200')"
      >
        <template #default="{ row }">
          <span>{{ row.title }}</span>
          <p
            v-if="row.description"
            class="my-tasks__description"
          >
            {{ row.description }}
          </p>
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="优先级"
        :min-width="readUiLength('--ui-table-col-90')"
      >
        <template #default="{ row }">
          <ElTag
            size="small"
            :type="priorityTag(row.priority)"
          >
            {{ priorityLabel[row.priority] ?? row.priority }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="截止时间"
        :min-width="readUiLength('--ui-table-col-150')"
      >
        <template #default="{ row }">
          <span
            class="list-table__figure"
            :class="{ 'my-tasks__overdue': row.overdue }"
          >
            {{ row.dueAt ? formatDue(row.dueAt) : '—' }}{{ row.overdue ? '（已逾期）' : '' }}
          </span>
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="状态"
        :min-width="readUiLength('--ui-table-col-100')"
      >
        <template #default="{ row }">
          <ElTag
            size="small"
            :type="row.status === 'IN_PROGRESS' ? 'warning' : 'info'"
          >
            {{ statusLabel[row.status] ?? row.status }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="操作"
        :min-width="readUiLength('--ui-table-col-160')"
      >
        <template #default="{ row }">
          <ElButton
            v-if="nextStatus(row as TaskItem)"
            size="small"
            type="primary"
            plain
            :loading="actingId === row.taskId"
            @click="advance(row as TaskItem)"
          >
            {{ nextStatus(row as TaskItem) === 'IN_PROGRESS' ? '开始处理' : '标记完成' }}
          </ElButton>
          <ElButton
            size="small"
            @click="openActivity(row as TaskItem)"
          >
            查看活动
          </ElButton>
        </template>
      </ElTableColumn>
    </ElTable>
  </ListPage>
</template>

<style scoped>
/* 页头、面板与表格排版统一由 base.css 的公共类提供。 */
.my-tasks__filter {
  flex-wrap: wrap;
  margin-left: auto;
}

.my-tasks__error {
  margin: 0 0 var(--ui-space-4);
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.my-tasks__overdue {
  color: var(--ui-color-danger);
}
.my-tasks__description {
  margin-top: var(--ui-space-2);
  color: var(--ui-color-text-secondary);
  font-size: var(--ui-font-size-small);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
