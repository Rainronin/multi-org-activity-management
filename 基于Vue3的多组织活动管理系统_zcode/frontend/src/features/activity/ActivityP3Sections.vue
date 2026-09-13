<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessageBox, ElOption, ElSelect, ElTable, ElTableColumn, ElTag } from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { newIdempotencyKey } from '../../shared/http/idempotency'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { fetchMembers } from '../organization/api'
import {
  advanceTask, changeMaterialStatus, createMaterial, createTask, deleteAttachment,
  downloadAttachment, fetchAttachments, fetchMaterials, fetchTasks, resolveMyMemberId,
  transitionTask, updateMaterial, updateTask, uploadAttachment,
} from './p3'
import type { AttachmentItem, MaterialItem, TaskItem } from './p3'

// 活动详情的 P3 区块：任务 / 物资 / 资料（附件）。后端逐请求鉴权，这里只决定按钮展示。
const props = defineProps<{
  orgDetail: { id: string; ownerMemberId?: string; status?: string } | null
  orgRole?: 'OWNER' | 'MEMBER' | null
}>()
const emit = defineEmits<{ changed: [] }>()

const route = useRoute()
const session = useSessionStore()
const orgId = computed(() => String(route.params.orgId ?? ''))
const activityId = computed(() => String(route.params.activityId ?? ''))

const tasks = ref<TaskItem[]>([])
const materials = ref<MaterialItem[]>([])
const attachments = ref<AttachmentItem[]>([])
const members = ref<{ memberId: string; username: string }[]>([])
const myMemberId = ref<string | null>(null)
const errorText = ref('')
const loading = ref(false)
const acting = ref('')

const taskDialogVisible = ref(false)
const taskSubmitting = ref(false)
const editingTask = ref<TaskItem | null>(null)
const taskForm = ref({
  title: '', description: '', assigneeMemberId: '', priority: 'MEDIUM', dueAt: '',
})

const materialDialogVisible = ref(false)
const materialSubmitting = ref(false)
const editingMaterial = ref<MaterialItem | null>(null)
const materialForm = ref({ name: '', quantity: 1, unit: '个', assigneeMemberId: '', note: '' })

const uploadInput = ref<HTMLInputElement>()
const uploading = ref(false)

// S10：任务资料弹窗（任务承担者也可上传本人任务的资料）
const taskAttachmentVisible = ref(false)
const taskAttachmentTarget = ref<TaskItem | null>(null)
const taskAttachments = ref<AttachmentItem[]>([])
const taskAttachmentLoading = ref(false)
const taskUploading = ref(false)
/** F06：当前上传意图的防重键；失败重试沿用，成功后清空。 */
let uploadKey = ''
const taskUploadInput = ref<HTMLInputElement>()

const statusLabel: Record<string, string> = {
  PENDING: '待处理', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELLED: '已取消',
  READY: '已就绪',
}
const materialStatusLabel: Record<string, string> = {
  PENDING: '待准备', IN_PROGRESS: '准备中', READY: '已就绪', CANCELLED: '已取消',
}
const priorityLabel: Record<string, string> = { HIGH: '高', MEDIUM: '中', LOW: '低' }

/** 组织负责人或本活动负责人可管理任务与物资；普通成员只能推进本人任务。 */
const canManage = computed(() => props.orgRole === 'OWNER'
  || (myMemberId.value !== null && myMemberId.value === props.orgDetail?.ownerMemberId))

/** 调整期待确认期间后端对筹备数据只读（S07-A），前端同步隐藏写操作。 */
const frozen = computed(() => props.orgDetail?.status === 'DRAFT')

function canAdvance(task: TaskItem): boolean {
  return !canManage.value && !frozen.value && task.assigneeMemberId === myMemberId.value
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
    const [t, m, a, mine] = await Promise.all([
      fetchTasks(orgId.value, activityId.value),
      fetchMaterials(orgId.value, activityId.value),
      fetchAttachments(orgId.value, activityId.value),
      resolveMyMemberId(orgId.value, session.account?.username ?? ''),
    ])
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    tasks.value = t
    materials.value = m
    attachments.value = a
    myMemberId.value = mine
    if (canManage.value && members.value.length === 0) {
      const page = await fetchMembers(orgId.value, { pageSize: 100 })
      if (seq === requestSeq && gen === session.generation) {
        members.value = page.items.map((item) => ({ memberId: item.memberId, username: item.username }))
      }
    }
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
    ?? members.value.find((m) => m.memberId === memberId)?.username
    ?? '—'
  )
}

function toLocalInput(value: string | null): string {
  if (!value) {
    return ''
  }
  // 后端返回 UTC 偏移时间；datetime-local 需要本地无偏移写法
  const date = new Date(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function openCreateTask() {
  editingTask.value = null
  taskForm.value = { title: '', description: '', assigneeMemberId: '', priority: 'MEDIUM', dueAt: '' }
  taskDialogVisible.value = true
}

function openEditTask(task: TaskItem) {
  editingTask.value = task
  taskForm.value = {
    title: task.title,
    description: task.description ?? '',
    assigneeMemberId: task.assigneeMemberId,
    priority: task.priority,
    dueAt: toLocalInput(task.dueAt),
  }
  taskDialogVisible.value = true
}

async function submitTask() {
  taskSubmitting.value = true
  const target = editingTask.value
  await guard(async () => {
    const payload = {
      title: taskForm.value.title.trim(),
      description: taskForm.value.description.trim() || undefined,
      assigneeMemberId: Number(taskForm.value.assigneeMemberId),
      priority: taskForm.value.priority,
      dueAt: taskForm.value.dueAt ? `${taskForm.value.dueAt}:00+08:00` : undefined,
      ...(target ? { version: target.version } : {}),
    }
    if (target) {
      await updateTask(orgId.value, activityId.value, target.taskId, payload)
    } else {
      await createTask(orgId.value, activityId.value, payload)
    }
    taskDialogVisible.value = false
    editingTask.value = null
    taskForm.value = { title: '', description: '', assigneeMemberId: '', priority: 'MEDIUM', dueAt: '' }
    await loadAll()
    emit('changed')
  })
  taskSubmitting.value = false
}

function advanceOwnTask(task: TaskItem, toStatus: 'IN_PROGRESS' | 'COMPLETED') {
  acting.value = task.taskId
  void guard(async () => {
    await advanceTask(orgId.value, activityId.value, task.taskId, toStatus)
    await loadAll()
    emit('changed')
  }).finally(() => {
    acting.value = ''
  })
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

function cancelTask(task: TaskItem) {
  acting.value = task.taskId
  void ElMessageBox.confirm(`确定取消任务「${task.title}」？`, '取消任务', {
    confirmButtonText: '取消任务', cancelButtonText: '返回', type: 'warning',
  })
    .then(() => guard(async () => {
      await transitionTask(orgId.value, activityId.value, task.taskId, 'CANCELLED')
      await loadAll()
    }))
    .catch(() => undefined)
    .finally(() => {
      acting.value = ''
    })
}

function openCreateMaterial() {
  editingMaterial.value = null
  materialForm.value = { name: '', quantity: 1, unit: '个', assigneeMemberId: '', note: '' }
  materialDialogVisible.value = true
}

function openEditMaterial(material: MaterialItem) {
  editingMaterial.value = material
  materialForm.value = {
    name: material.name,
    quantity: material.quantity,
    unit: material.unit,
    assigneeMemberId: material.assigneeMemberId ?? '',
    note: material.note ?? '',
  }
  materialDialogVisible.value = true
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
    // F06：活动资料上传同样携带防重键（一次上传意图一个键，重试沿用）
    if (!uploadKey) {
      uploadKey = newIdempotencyKey()
    }
    const csrf = await import('../../shared/http/client').then((mod) =>
      mod.request<{ token: string; headerName: string }>('/api/auth/csrf'))
    const form = new FormData()
    form.append('file', file)
    await import('../../shared/http/client').then((mod) =>
      mod.request(
        `/api/organizations/${orgId.value}/activities/${activityId.value}/attachments`,
        {
          method: 'POST',
          form,
          headers: { [csrf.data.headerName]: csrf.data.token, 'Idempotency-Key': uploadKey },
          response: 'none',
        },
      ))
    uploadKey = ''
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
  const target = editingMaterial.value
  await guard(async () => {
    const payload = {
      name: materialForm.value.name.trim(),
      quantity: Number(materialForm.value.quantity),
      unit: materialForm.value.unit.trim(),
      assigneeMemberId: materialForm.value.assigneeMemberId
        ? Number(materialForm.value.assigneeMemberId) : undefined,
      note: materialForm.value.note.trim() || undefined,
      ...(target ? { version: target.version } : {}),
    }
    if (target) {
      await updateMaterial(orgId.value, activityId.value, target.materialId, payload)
    } else {
      await createMaterial(orgId.value, activityId.value, payload)
    }
    materialDialogVisible.value = false
    editingMaterial.value = null
    await loadAll()
  })
  materialSubmitting.value = false
}

function download(attachment: AttachmentItem) {
  downloadAttachment(orgId.value, activityId.value, attachment.attachmentId, attachment.originalName)
}

// P3.1：允许类型预览——图片与 PDF 在弹窗内直接查看（走同一受控端点，带会话 Cookie），
// 其余类型（文本、Office、ZIP）没有可靠的浏览器内置查看器，保留下载。
const previewVisible = ref(false)
const previewTarget = ref<AttachmentItem | null>(null)
const previewUrl = ref('')

const PREVIEW_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']

function isPreviewable(attachment: AttachmentItem): boolean {
  return PREVIEW_IMAGE_TYPES.includes(attachment.contentType)
    || attachment.contentType === 'application/pdf'
}

function previewKindOf(attachment: AttachmentItem): 'image' | 'pdf' | 'none' {
  if (PREVIEW_IMAGE_TYPES.includes(attachment.contentType)) {
    return 'image'
  }
  return attachment.contentType === 'application/pdf' ? 'pdf' : 'none'
}

const previewKind = computed(() => (previewTarget.value ? previewKindOf(previewTarget.value) : 'none'))

function attachmentUrl(attachment: AttachmentItem): string {
  return `/api/organizations/${encodeURIComponent(orgId.value)}/activities/${encodeURIComponent(activityId.value)}`
    + `/attachments/${encodeURIComponent(attachment.attachmentId)}/download`
}

function openPreview(attachment: AttachmentItem) {
  previewTarget.value = attachment
  previewUrl.value = attachmentUrl(attachment)
  previewVisible.value = true
}

async function openTaskAttachments(task: TaskItem) {
  taskAttachmentTarget.value = task
  taskAttachmentVisible.value = true
  await loadTaskAttachments()
}

async function loadTaskAttachments(): Promise<void> {
  const task = taskAttachmentTarget.value
  if (!task || !orgId.value || !activityId.value) {
    return
  }
  taskAttachmentLoading.value = true
  await guard(async () => {
    taskAttachments.value = await fetchAttachments(orgId.value, activityId.value,
      { kind: 'task', taskId: task.taskId })
  })
  taskAttachmentLoading.value = false
}

function triggerTaskUpload() {
  taskUploadInput.value?.click()
}

async function onTaskFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  const task = taskAttachmentTarget.value
  if (!file || !task) {
    return
  }
  taskUploading.value = true
  await guard(async () => {
    // F06：一次上传意图一个防重键，重试沿用同一键（失败时保留在 uploadKey 中）
    if (!uploadKey) {
      uploadKey = newIdempotencyKey()
    }
    await uploadAttachment(orgId.value, activityId.value, file, uploadKey, { kind: 'task', taskId: task.taskId })
    uploadKey = ''
    await loadTaskAttachments()
    await loadAll()
  })
  taskUploading.value = false
  input.value = ''
}

function confirmDeleteTaskAttachment(attachment: AttachmentItem) {
  const task = taskAttachmentTarget.value
  if (!task) {
    return
  }
  acting.value = attachment.attachmentId
  void ElMessageBox.confirm(`确定删除「${attachment.originalName}」？`, '删除任务资料', {
    confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
  })
    .then(() => guard(async () => {
      await deleteAttachment(orgId.value, activityId.value, attachment.attachmentId)
      await loadTaskAttachments()
      await loadAll()
    }))
    .catch(() => undefined)
    .finally(() => {
      acting.value = ''
    })
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
    <p
      v-if="frozen"
      class="p3-note"
    >
      活动安排调整中：任务与物资在重新确认前为只读。
    </p>

    <section class="p3-block">
      <div class="p3-block__head">
        <h2>任务</h2>
        <ElButton
          v-if="canManage"
          size="small"
          type="primary"
          plain
          @click="openCreateTask"
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
          label="优先级"
          min-width="80"
        >
          <template #default="{ row }">
            {{ priorityLabel[row.priority] ?? row.priority }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="状态"
          min-width="100"
        >
          <template #default="{ row }">
            <ElTag
              size="small"
              :type="row.status === 'COMPLETED' ? 'success' : (row.overdue ? 'danger' : 'info')"
            >
              {{ statusLabel[row.status] ?? row.status }}{{ row.overdue ? '（逾期）' : '' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          min-width="220"
        >
          <template #default="{ row }">
            <ElButton
              size="small"
              @click="openTaskAttachments(row as TaskItem)"
            >
              资料
            </ElButton>
            <template v-if="canManage">
              <ElButton
                v-if="row.status !== 'COMPLETED' && row.status !== 'CANCELLED' && !frozen"
                size="small"
                :loading="acting === row.taskId"
                @click="openEditTask(row as TaskItem)"
              >
                编辑/改派
              </ElButton>
              <ElButton
                v-if="row.status === 'COMPLETED' && !frozen"
                size="small"
                :loading="acting === row.taskId"
                @click="reopenTask(row as TaskItem)"
              >
                重新打开
              </ElButton>
              <ElButton
                v-if="row.status !== 'COMPLETED' && row.status !== 'CANCELLED' && !frozen"
                size="small"
                type="danger"
                plain
                :loading="acting === row.taskId"
                @click="cancelTask(row as TaskItem)"
              >
                取消
              </ElButton>
            </template>
            <template v-else-if="canAdvance(row as TaskItem)">
              <ElButton
                v-if="row.status === 'PENDING'"
                size="small"
                type="primary"
                plain
                :loading="acting === row.taskId"
                @click="advanceOwnTask(row as TaskItem, 'IN_PROGRESS')"
              >
                开始处理
              </ElButton>
              <ElButton
                v-if="row.status === 'PENDING' || row.status === 'IN_PROGRESS'"
                size="small"
                type="primary"
                :loading="acting === row.taskId"
                @click="advanceOwnTask(row as TaskItem, 'COMPLETED')"
              >
                标记完成
              </ElButton>
            </template>
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
          v-if="canManage"
          size="small"
          type="primary"
          plain
          @click="openCreateMaterial"
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
          min-width="260"
        >
          <template #default="{ row }">
            <ElButton
              v-if="canManage && row.status !== 'CANCELLED' && !frozen"
              size="small"
              :loading="acting === row.materialId"
              @click="openEditMaterial(row as MaterialItem)"
            >
              编辑
            </ElButton>
            <template v-if="canManage && !frozen">
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
          v-if="canManage"
          size="small"
          type="primary"
          plain
          :loading="uploading"
          :disabled="frozen"
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
              v-if="isPreviewable(row as AttachmentItem)"
              size="small"
              @click="openPreview(row as AttachmentItem)"
            >
              预览
            </ElButton>
            <ElButton
              size="small"
              @click="download(row as AttachmentItem)"
            >
              下载
            </ElButton>
            <ElButton
              v-if="canManage || row.uploaderUsername === session.account?.username"
              size="small"
              type="danger"
              plain
              :loading="acting === row.attachmentId"
              :disabled="frozen"
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
      v-model="taskAttachmentVisible"
      :title="`任务资料：${taskAttachmentTarget?.title ?? ''}`"
      width="var(--ui-dialog-width)"
    >
      <div class="p3-block__head">
        <span class="p3-note">任务承担者可以上传与删除本人任务的资料。</span>
        <ElButton
          size="small"
          type="primary"
          plain
          :loading="taskUploading"
          :disabled="frozen"
          @click="triggerTaskUpload"
        >
          上传资料
        </ElButton>
        <input
          ref="taskUploadInput"
          type="file"
          class="p3-upload-input"
          accept=".png,.jpg,.jpeg,.gif,.webp,.pdf,.txt,.docx,.xlsx,.pptx,.zip"
          @change="onTaskFileChange"
        >
      </div>
      <ElTable
        v-loading="taskAttachmentLoading"
        :data="taskAttachments"
      >
        <ElTableColumn
          prop="originalName"
          label="文件名"
          min-width="160"
        />
        <ElTableColumn
          label="大小"
          min-width="90"
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
              v-if="isPreviewable(row as AttachmentItem)"
              size="small"
              @click="openPreview(row as AttachmentItem)"
            >
              预览
            </ElButton>
            <ElButton
              size="small"
              @click="download(row as AttachmentItem)"
            >
              下载
            </ElButton>
            <ElButton
              v-if="canManage || row.uploaderUsername === session.account?.username"
              size="small"
              type="danger"
              plain
              :loading="acting === row.attachmentId"
              :disabled="frozen"
              @click="confirmDeleteTaskAttachment(row as AttachmentItem)"
            >
              删除
            </ElButton>
          </template>
        </ElTableColumn>
        <template #empty>
          <span class="p3-note">该任务暂无资料。</span>
        </template>
      </ElTable>
      <template #footer>
        <ElButton @click="taskAttachmentVisible = false">
          关闭
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="previewVisible"
      :title="previewTarget?.originalName ?? '资料预览'"
      width="min(100%, var(--ui-drawer-width))"
    >
      <div class="p3-preview">
        <img
          v-if="previewKind === 'image' && previewUrl"
          :src="previewUrl"
          :alt="previewTarget?.originalName ?? '资料预览'"
          class="p3-preview__image"
        >
        <iframe
          v-else-if="previewKind === 'pdf' && previewUrl"
          :src="previewUrl"
          :title="previewTarget?.originalName ?? '资料预览'"
          class="p3-preview__frame"
        />
        <p
          v-else
          class="p3-note"
        >
          该类型不支持在线预览，请下载后查看。
        </p>
      </div>
      <template #footer>
        <ElButton @click="previewVisible = false">
          关闭
        </ElButton>
        <ElButton
          v-if="previewTarget"
          type="primary"
          @click="download(previewTarget)"
        >
          下载
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="taskDialogVisible"
      :title="editingTask ? '编辑任务' : '新建任务'"
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
        <ElFormItem label="优先级">
          <ElSelect v-model="taskForm.priority">
            <ElOption
              label="高"
              value="HIGH"
            />
            <ElOption
              label="中"
              value="MEDIUM"
            />
            <ElOption
              label="低"
              value="LOW"
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
          {{ editingTask ? '保存' : '创建' }}
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="materialDialogVisible"
      :title="editingMaterial ? '编辑物资' : '添加物资'"
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
          {{ editingMaterial ? '保存' : '添加' }}
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

/* P3.1：预览区按内容自适应高度，窄屏不横向溢出 */
.p3-preview {
  display: flex;
  justify-content: center;
  max-height: 70vh;
  overflow: auto;
}

.p3-preview__image {
  max-width: 100%;
  height: auto;
  border-radius: var(--ui-radius-control);
  border: var(--ui-border-width) solid var(--ui-color-border);
}

.p3-preview__frame {
  width: 100%;
  height: 70vh;
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-control);
}

.p3-material-qty {
  display: flex;
  gap: var(--ui-space-3);
}
</style>
