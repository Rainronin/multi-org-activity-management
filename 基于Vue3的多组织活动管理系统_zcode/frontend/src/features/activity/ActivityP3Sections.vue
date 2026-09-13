<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessageBox, ElOption, ElSelect, ElTable, ElTableColumn, ElTag } from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import {
  changeMaterialStatus, createMaterial, createTask, deleteAttachment,
  downloadAttachment, fetchAttachments, fetchMaterials, fetchTasks,
  transitionTask,
} from './p3'
import type { AttachmentItem, MaterialItem, TaskItem } from './p3'

// 活动详情的 P3 区块：任务 / 物资 / 资料（附件）。后端逐请求鉴权。
defineProps<{ orgDetail: { id: string } | null }>()
const emit = defineEmits<{ changed: [] }>()

const route = useRoute()
const session = useSessionStore()
const orgId = computed(() => String(route.params.orgId ?? ''))
const activityId = computed(() => String(route.params.activityId ?? ''))

const tasks = ref<TaskItem[]>([])
const materials = ref<MaterialItem[]>([])
const attachments = ref<AttachmentItem[]>([])
const members = ref<{ memberId: string; username: string }[]>([])
const errorText = ref('')
const loading = ref(false)
const acting = ref('')

const taskDialogVisible = ref(false)
const taskSubmitting = ref(false)
const taskForm = ref({ title: '', description: '', assigneeMemberId: '', priority: 'MEDIUM', dueAt: '' })

const materialDialogVisible = ref(false)
const materialSubmitting = ref(false)
const materialForm = ref({ name: '', quantity: 1, unit: '个', assigneeMemberId: '', note: '' })

const uploadInput = ref<HTMLInputElement>()
const uploading = ref(false)

const statusLabel: Record<string, string> = {
  PENDING: '待处理', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELLED: '已取消',
  READY: '已就绪',
}
const materialStatusLabel: Record<string, string> = {
  PENDING: '待准备', IN_PROGRESS: '准备中', READY: '已就绪', CANCELLED: '已取消',
}

let requestSeq = 0

async function loadAll(): Promise<void> {
  if (!orgId.value || !activityId.value) {
    return
  }
  const seq = ++requestSeq
  const gen = session.capture()
  loading.value = true
  errorText.value = ''
  try {
    const [t, m, a] = await Promise.all([
      fetchTasks(orgId.value, activityId.value),
      fetchMaterials(orgId.value, activityId.value),
      fetchAttachments(orgId.value, activityId.value),
    ])
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    tasks.value = t
    materials.value = m
    attachments.value = a
  } catch (error) {
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    tasks.value = []
    materials.value = []
    attachments.value = []
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '任务与资料加载失败，请重试。'
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

watch([orgId, activityId], loadAll)
onMounted(loadAll)

async function guard(fn: () => Promise<void>): Promise<void> {
  errorText.value = ''
  try {
    await fn()
  } catch (error) {
    if (session.observeUnauthorized(error, session.capture())) {
      return
    }
    if (error instanceof ApiError && !error.isTransportError) {
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        errorText.value = '安全校验已过期，请重试。'
      } else {
        errorText.value = error.detail || '操作失败，请重试。'
      }
    } else {
      errorText.value = '网络连接失败，请检查网络后重试。'
    }
  }
}

function usernameOf(memberId: string): string {
  return (
    tasks.value.find((t) => t.assigneeMemberId === memberId)?.assigneeUsername
    ?? materials.value.find((m) => m.assigneeMemberId === memberId)?.assigneeUsername
    ?? ''
  )
}

async function submitTask() {
  taskSubmitting.value = true
  await guard(async () => {
    await createTask(orgId.value, activityId.value, {
      title: taskForm.value.title.trim(),
      description: taskForm.value.description.trim() || undefined,
      assigneeMemberId: Number(taskForm.value.assigneeMemberId),
      priority: taskForm.value.priority,
      dueAt: taskForm.value.dueAt ? `${taskForm.value.dueAt}:00+08:00` : undefined,
    })
    taskDialogVisible.value = false
    taskForm.value = { title: '', description: '', assigneeMemberId: '', priority: 'MEDIUM', dueAt: '' }
    await loadAll()
    emit('changed')
  })
  taskSubmitting.value = false
}

function reopenTask(task: TaskItem) {
  acting.value = task.taskId
  void guard(async () => {
    await transitionTask(orgId.value, activityId.value, task.taskId, 'PENDING')
    await loadAll()
  }).finally(() => {
    acting.value = ''
  })
}

async function setMaterialStatus(material: MaterialItem, toStatus: string) {
  acting.value = material.materialId
  void guard(async () => {
    await changeMaterialStatus(orgId.value, activityId.value, material.materialId, toStatus)
    await loadAll()
  }).finally(() => {
    acting.value = ''
  })
}

function triggerUpload() {
  uploadInput.value?.click()
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  uploading.value = true
  await guard(async () => {
    const csrf = await import('../../shared/http/client').then((mod) =>
      mod.request<{ token: string; headerName: string }>('/api/auth/csrf'))
    const form = new FormData()
    form.append('file', file)
    await import('../../shared/http/client').then((mod) =>
      mod.request(
        `/api/organizations/${orgId.value}/activities/${activityId.value}/attachments`,
        { method: 'POST', form, headers: { [csrf.data.headerName]: csrf.data.token }, response: 'none' },
      ))
    await loadAll()
  })
  uploading.value = false
  input.value = ''
}

function confirmDeleteAttachment(attachment: AttachmentItem) {
  acting.value = attachment.attachmentId
  void ElMessageBox.confirm(`确定删除「${attachment.originalName}」？`, '删除附件', {
    confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
  })
    .then(() => guard(async () => {
      await deleteAttachment(orgId.value, activityId.value, attachment.attachmentId)
      await loadAll()
    }))
    .catch(() => undefined)
    .finally(() => {
      acting.value = ''
    })
}

async function submitMaterial() {
  materialSubmitting.value = true
  await guard(async () => {
    await createMaterial(orgId.value, activityId.value, {
      name: materialForm.value.name.trim(),
      quantity: Number(materialForm.value.quantity),
      unit: materialForm.value.unit.trim(),
      assigneeMemberId: materialForm.value.assigneeMemberId ? Number(materialForm.value.assigneeMemberId) : undefined,
      note: materialForm.value.note.trim() || undefined,
    })
    materialDialogVisible.value = false
    await loadAll()
  })
  materialSubmitting.value = false
}

function download(attachment: AttachmentItem) {
  downloadAttachment(orgId.value, activityId.value, attachment.attachmentId, attachment.originalName)
}

defineExpose({ reload: loadAll })
</script>

<template>
  <div class="p3-sections">
    <p
      v-if="errorText"
      class="p3-error"
      role="alert"
    >
      {{ errorText }}
    </p>

    <section class="p3-block">
      <div class="p3-block__head">
        <h2>任务</h2>
        <ElButton
          size="small"
          type="primary"
          plain
          @click="taskDialogVisible = true"
        >
          新建任务
        </ElButton>
      </div>
      <ElTable
        v-loading="loading"
        :data="tasks"
      >
        <ElTableColumn
          prop="title"
          label="任务"
          min-width="160"
        />
        <ElTableColumn
          label="负责人"
          min-width="110"
        >
          <template #default="{ row }">
            {{ usernameOf(row.assigneeMemberId) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="状态"
          min-width="100"
        >
          <template #default="{ row }">
            <ElTag
              size="small"
              :type="row.status === 'COMPLETED' ? 'success' : 'info'"
            >
              {{ statusLabel[row.status] ?? row.status }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          min-width="110"
        >
          <template #default="{ row }">
            <ElButton
              v-if="row.status === 'COMPLETED'"
              size="small"
              :loading="acting === row.taskId"
              @click="reopenTask(row as TaskItem)"
            >
              重新打开
            </ElButton>
          </template>
        </ElTableColumn>
        <template #empty>
          <span class="p3-note">暂无任务。</span>
        </template>
      </ElTable>
    </section>

    <section class="p3-block">
      <div class="p3-block__head">
        <h2>物资</h2>
        <ElButton
          size="small"
          type="primary"
          plain
          @click="materialDialogVisible = true"
        >
          添加物资
        </ElButton>
      </div>
      <ElTable
        v-loading="loading"
        :data="materials"
      >
        <ElTableColumn
          prop="name"
          label="物资"
          min-width="140"
        />
        <ElTableColumn
          label="数量"
          min-width="90"
        >
          <template #default="{ row }">
            {{ row.quantity }} {{ row.unit }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="负责人"
          min-width="110"
        >
          <template #default="{ row }">
            {{ row.assigneeUsername || '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="状态"
          min-width="100"
        >
          <template #default="{ row }">
            <ElTag
              size="small"
              :type="row.status === 'READY' ? 'success' : 'info'"
            >
              {{ materialStatusLabel[row.status] ?? row.status }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          min-width="180"
        >
          <template #default="{ row }">
            <ElButton
              v-if="row.status === 'PENDING'"
              size="small"
              :loading="acting === row.materialId"
              @click="setMaterialStatus(row as MaterialItem, 'IN_PROGRESS')"
            >
              开始准备
            </ElButton>
            <ElButton
              v-if="row.status === 'IN_PROGRESS'"
              size="small"
              type="primary"
              plain
              :loading="acting === row.materialId"
              @click="setMaterialStatus(row as MaterialItem, 'READY')"
            >
              标记就绪
            </ElButton>
            <ElButton
              v-if="row.status !== 'CANCELLED' && row.status !== 'READY'"
              size="small"
              type="danger"
              plain
              :loading="acting === row.materialId"
              @click="setMaterialStatus(row as MaterialItem, 'CANCELLED')"
            >
              取消
            </ElButton>
          </template>
        </ElTableColumn>
        <template #empty>
          <span class="p3-note">暂无物资。</span>
        </template>
      </ElTable>
    </section>

    <section class="p3-block">
      <div class="p3-block__head">
        <h2>资料</h2>
        <ElButton
          size="small"
          type="primary"
          plain
          :loading="uploading"
          @click="triggerUpload"
        >
          上传资料
        </ElButton>
        <input
          ref="uploadInput"
          type="file"
          class="p3-upload-input"
          accept=".png,.jpg,.jpeg,.gif,.webp,.pdf,.txt,.docx,.xlsx,.pptx,.zip"
          @change="onFileChange"
        >
      </div>
      <ElTable
        v-loading="loading"
        :data="attachments"
      >
        <ElTableColumn
          prop="originalName"
          label="文件名"
          min-width="180"
        />
        <ElTableColumn
          label="大小"
          min-width="100"
        >
          <template #default="{ row }">
            {{ Math.max(1, Math.round(row.sizeBytes / 1024)) }} KB
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="上传者"
          min-width="110"
        >
          <template #default="{ row }">
            {{ row.uploaderUsername }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          min-width="140"
        >
          <template #default="{ row }">
            <ElButton
              size="small"
              @click="download(row as AttachmentItem)"
            >
              下载
            </ElButton>
            <ElButton
              size="small"
              type="danger"
              plain
              :loading="acting === row.attachmentId"
              @click="confirmDeleteAttachment(row as AttachmentItem)"
            >
              删除
            </ElButton>
          </template>
        </ElTableColumn>
        <template #empty>
          <span class="p3-note">暂无资料。</span>
        </template>
      </ElTable>
    </section>

    <ElDialog
      v-model="taskDialogVisible"
      title="新建任务"
      width="var(--ui-dialog-width)"
    >
      <ElForm
        :model="taskForm"
        label-position="top"
      >
        <ElFormItem
          label="任务标题"
          required
        >
          <ElInput
            v-model="taskForm.title"
            placeholder="1-128 字符"
          />
        </ElFormItem>
        <ElFormItem
          label="任务负责人"
          required
        >
          <ElSelect
            v-model="taskForm.assigneeMemberId"
            placeholder="选择本组织有效成员"
            filterable
          >
            <ElOption
              v-for="m in members"
              :key="m.memberId"
              :label="m.username"
              :value="m.memberId"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="说明">
          <ElInput
            v-model="taskForm.description"
            type="textarea"
            :rows="2"
            placeholder="选填"
          />
        </ElFormItem>
        <ElFormItem label="截止时间">
          <ElInput
            v-model="taskForm.dueAt"
            type="datetime-local"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="taskDialogVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="taskSubmitting"
          @click="submitTask"
        >
          创建
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="materialDialogVisible"
      title="添加物资"
      width="var(--ui-dialog-width)"
    >
      <ElForm
        :model="materialForm"
        label-position="top"
      >
        <ElFormItem
          label="物资名称"
          required
        >
          <ElInput
            v-model="materialForm.name"
            placeholder="1-128 字符"
          />
        </ElFormItem>
        <ElFormItem
          label="数量与单位"
          required
        >
          <div class="p3-material-qty">
            <ElInput
              v-model="materialForm.quantity"
              type="number"
              :min="1"
            />
            <ElInput
              v-model="materialForm.unit"
              placeholder="个"
            />
          </div>
        </ElFormItem>
        <ElFormItem label="负责人">
          <ElSelect
            v-model="materialForm.assigneeMemberId"
            placeholder="选填"
            filterable
            clearable
          >
            <ElOption
              v-for="m in members"
              :key="m.memberId"
              :label="m.username"
              :value="m.memberId"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput
            v-model="materialForm.note"
            type="textarea"
            :rows="2"
            placeholder="选填"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="materialDialogVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="materialSubmitting"
          @click="submitMaterial"
        >
          添加
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.p3-blocks {
  min-width: 0;
}

.p3-error {
  margin-bottom: var(--ui-space-4);
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.p3-block {
  margin-top: var(--ui-space-10);
}

.p3-block__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  margin-bottom: var(--ui-space-4);
}

.p3-block__head h2 {
  font-size: var(--ui-font-size-section);
  font-weight: var(--ui-font-weight-semibold);
}

.p3-upload-input {
  display: none;
}

.p3-note {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}

.p3-material-qty {
  display: flex;
  gap: var(--ui-space-3);
}
</style>
