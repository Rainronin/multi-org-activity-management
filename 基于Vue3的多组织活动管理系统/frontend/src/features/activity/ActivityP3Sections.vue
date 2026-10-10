<script setup lang="ts">
import { readUiLength } from '../../shared/utils/readUiToken'
import { useLoadingSkeleton } from '../../shared/utils/useLoadingSkeleton'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessageBox, ElOption, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag, type FormInstance, type FormRules } from 'element-plus'
import { formatDateTime } from './types'
import { ApiError, readErrorMessage } from '../../shared/http/error'
import { newIdempotencyKey } from '../../shared/http/idempotency'
import { request } from '../../shared/http/client'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { fetchMembers } from '../organization/api'
import {
  advanceTask, changeMaterialStatus, createMaterial, createTask, deleteAttachment,
  downloadAttachment, fetchAttachments, fetchMaterials, fetchTasks, resolveMyMemberId,
  transitionTask, updateMaterial, updateTask, uploadAttachment, fetchAttachmentByRequestKey,
} from './p3'
import type { AttachmentItem, AttachmentScope, MaterialItem, SaveMaterialInput, SaveTaskInput, TaskItem } from './p3'

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
/** 是否已完成一次成功读取；读取失败时不渲染空表格。 */
const loaded = ref(false)
/** 骨架只在首次进入时延时出现（leading／trailing 来自集中 token）。 */
const skeleton = useLoadingSkeleton()
const acting = ref('')

const taskDialogVisible = ref(false)
const taskSubmitting = ref(false)
const editingTask = ref<TaskItem | null>(null)
const taskForm = ref({
  title: '', description: '', assigneeMemberId: '', priority: 'MEDIUM', dueAt: '',
})

const taskFormRef = ref<FormInstance>()
const pendingTaskCreate = ref<{ input: SaveTaskInput; key: string } | null>(null)
let initialTaskForm = ''
const taskDirty = computed(() => taskDialogVisible.value
  && (pendingTaskCreate.value !== null || JSON.stringify(taskForm.value) !== initialTaskForm))
const taskRules: FormRules = {
  title: [
    { required: true, whitespace: true, message: '请输入任务标题', trigger: 'blur' },
    { max: 128, message: '任务标题不超过 128 字符', trigger: 'blur' },
  ],
  assigneeMemberId: [{ required: true, message: '请选择任务负责人', trigger: 'change' }],
  description: [{ max: 2000, message: '任务说明不超过 2000 字符', trigger: 'blur' }],
}
async function closeTaskDialog() {
  if (taskSubmitting.value) return
  if (taskDirty.value) {
    try {
      await ElMessageBox.confirm(pendingTaskCreate.value
        ? '创建结果尚未确认。离开后请先核对任务列表，避免重复创建。确定离开？'
        : '任务内容还未保存，确定放弃吗？', '未保存的任务', {
        confirmButtonText: '放弃填写', cancelButtonText: '继续填写', type: 'warning',
      })
    } catch { return }
  }
  const needsRecheck = pendingTaskCreate.value !== null
  pendingTaskCreate.value = null
  taskDialogVisible.value = false
  if (needsRecheck) void loadAll()
}

const materialDialogVisible = ref(false)
const materialSubmitting = ref(false)
const pendingMaterialCreate = ref<{ input: SaveMaterialInput; key: string } | null>(null)
const editingMaterial = ref<MaterialItem | null>(null)
const materialForm = ref({ name: '', quantity: 1, unit: '个', assigneeMemberId: '', note: '' })
let initialMaterialForm = ''
const materialDirty = computed(() => materialDialogVisible.value
  && (pendingMaterialCreate.value !== null || JSON.stringify(materialForm.value) !== initialMaterialForm))
async function closeMaterialDialog(done: () => void = () => { materialDialogVisible.value = false }) {
  if (materialSubmitting.value) return
  if (materialDirty.value) {
    try {
      await ElMessageBox.confirm(pendingMaterialCreate.value
        ? '创建结果尚未确认。离开后请先核对物资列表，避免重复创建。确定离开？'
        : '物资内容还未保存，确定放弃吗？', '未保存的物资', {
        confirmButtonText: '放弃填写', cancelButtonText: '继续填写', type: 'warning',
      })
    } catch { return }
  }
  const needsRecheck = pendingMaterialCreate.value !== null
  pendingMaterialCreate.value = null
  done()
  if (needsRecheck) void loadAll()
}

const uploadInput = ref<HTMLInputElement>()
const uploading = ref(false)
const pendingUpload = ref<{ file: File; key: string; orgId: string; activityId: string; scope: AttachmentScope } | null>(null)
const pendingActivityUpload = computed(() => pendingUpload.value?.scope.kind === 'activity')

// S10：任务资料弹窗（任务承担者也可上传本人任务的资料）
const taskAttachmentVisible = ref(false)
const taskAttachmentTarget = ref<TaskItem | null>(null)
const taskAttachments = ref<AttachmentItem[]>([])
const taskAttachmentLoading = ref(false)
const taskAttachmentLoaded = ref(false)
const taskUploading = ref(false)
const taskUploadInput = ref<HTMLInputElement>()
const pendingTaskUpload = computed(() => pendingUpload.value?.scope.kind === 'task'
  && pendingUpload.value.scope.taskId === taskAttachmentTarget.value?.taskId)

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
const canUploadTaskAttachment = computed(() => canManage.value
  || (myMemberId.value !== null && myMemberId.value === taskAttachmentTarget.value?.assigneeMemberId))

/** 与后端筹备写入状态一致：草稿、待确认、取消及归档只读。 */
const frozen = computed(() => !['APPROVED', 'EXECUTING', 'REVIEWING'].includes(props.orgDetail?.status ?? 'DRAFT'))

function canAdvance(task: TaskItem): boolean {
  return !canManage.value && !frozen.value && task.assigneeMemberId === myMemberId.value
}

let requestSeq = 0
let alive = true
let actionGeneration = 0
watch([orgId, activityId], () => { actionGeneration++ }, { flush: 'sync' })

async function loadAll(): Promise<void> {
  if (!orgId.value || !activityId.value) {
    return
  }
  const seq = ++requestSeq
  const gen = session.capture()
  loading.value = true
  // 已有内容时是后台刷新，不重放骨架；首次进入才走 leading／trailing 延迟
  if (tasks.value.length > 0) {
    skeleton.hide()
  } else {
    skeleton.begin()
  }
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
    if (taskAttachmentTarget.value) {
      taskAttachmentTarget.value = t.find(task => task.taskId === taskAttachmentTarget.value?.taskId) ?? null
    }
    materials.value = m
    attachments.value = a
    myMemberId.value = mine
    loaded.value = true
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
    // 失败仅展示错误与重试，不继续显示加载占位或空表格。
    loaded.value = false
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = readErrorMessage(error, '任务与资料加载失败，请重试。')
  } finally {
    if (seq === requestSeq) {
      loading.value = false
      skeleton.end()
    }
  }
}

watch([orgId, activityId], () => {
  pendingUpload.value = null
  pendingTaskCreate.value = null
  pendingMaterialCreate.value = null
  taskAttachmentVisible.value = false
  tasks.value = []
  materials.value = []
  attachments.value = []
  loaded.value = false
  errorText.value = ''
  skeleton.hide()
  return loadAll()
})
onMounted(loadAll)
function checkAssignments(): void {
  if (!document.hidden && session.status === 'authenticated') void loadAll()
}
onMounted(() => document.addEventListener('visibilitychange', checkAssignments))
onBeforeUnmount(() => {
  alive = false
  document.removeEventListener('visibilitychange', checkAssignments)
  requestSeq++
})

function captureAction() {
  return { orgId: orgId.value, activityId: activityId.value, generation: session.capture(), scope: actionGeneration }
}

function isActionCurrent(context: ReturnType<typeof captureAction>): boolean {
  return alive && context.orgId === orgId.value && context.activityId === activityId.value
    && context.generation === session.generation && context.scope === actionGeneration
}

async function guard(fn: (context: ReturnType<typeof captureAction>) => Promise<void>, context = captureAction()): Promise<unknown | null> {
  if (!isActionCurrent(context)) return null
  errorText.value = ''
  try {
    await fn(context)
    return null
  } catch (error) {
    if (session.observeUnauthorized(error, context.generation)) {
      return error
    }
    if (!isActionCurrent(context)) return error
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
    return error
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
  pendingTaskCreate.value = null
  taskForm.value = { title: '', description: '', assigneeMemberId: '', priority: 'MEDIUM', dueAt: '' }
  initialTaskForm = JSON.stringify(taskForm.value)
  errorText.value = ''
  taskDialogVisible.value = true
  void nextTick(() => taskFormRef.value?.clearValidate())
}

function openEditTask(task: TaskItem) {
  editingTask.value = task
  pendingTaskCreate.value = null
  taskForm.value = {
    title: task.title,
    description: task.description ?? '',
    assigneeMemberId: task.assigneeMemberId,
    priority: task.priority,
    dueAt: toLocalInput(task.dueAt),
  }
  initialTaskForm = JSON.stringify(taskForm.value)
  errorText.value = ''
  taskDialogVisible.value = true
  void nextTick(() => taskFormRef.value?.clearValidate())
}

async function submitTask() {
  if (taskSubmitting.value) return
  const context = captureAction()
  taskSubmitting.value = true
  if (!pendingTaskCreate.value && !(await taskFormRef.value?.validate().catch(() => false))) {
    taskSubmitting.value = false
    return
  }
  if (!isActionCurrent(context)) return
  const target = editingTask.value
  const failure = await guard(async () => {
    const payload: SaveTaskInput = pendingTaskCreate.value?.input ?? {
      title: taskForm.value.title.trim(),
      description: taskForm.value.description.trim() || undefined,
      assigneeMemberId: Number(taskForm.value.assigneeMemberId),
      priority: taskForm.value.priority,
      dueAt: taskForm.value.dueAt ? `${taskForm.value.dueAt}:00+08:00` : undefined,
      ...(target ? { version: target.version } : {}),
    }
    if (target) {
      await updateTask(context.orgId, context.activityId, target.taskId, payload)
    } else {
      if (!pendingTaskCreate.value) pendingTaskCreate.value = { input: payload, key: newIdempotencyKey() }
      await createTask(context.orgId, context.activityId, pendingTaskCreate.value.input, pendingTaskCreate.value.key)
    }
    if (!isActionCurrent(context)) return
    pendingTaskCreate.value = null
    taskDialogVisible.value = false
    editingTask.value = null
    taskForm.value = { title: '', description: '', assigneeMemberId: '', priority: 'MEDIUM', dueAt: '' }
    await loadAll()
    if (isActionCurrent(context)) emit('changed')
  }, context)
  if (failure instanceof ApiError && !failure.isTransportError
    && [400, 403, 404, 422, 429].includes(failure.status)) pendingTaskCreate.value = null
  taskSubmitting.value = false
}

function advanceOwnTask(task: TaskItem, toStatus: 'IN_PROGRESS' | 'COMPLETED') {
  acting.value = task.taskId
  void guard(async (context) => {
    await advanceTask(context.orgId, context.activityId, task.taskId, toStatus)
    if (!isActionCurrent(context)) return
    await loadAll()
    if (isActionCurrent(context)) emit('changed')
  }).finally(() => {
    acting.value = ''
  })
}

function reopenTask(task: TaskItem) {
  acting.value = task.taskId
  void guard(async (context) => {
    await transitionTask(context.orgId, context.activityId, task.taskId, 'PENDING')
    if (!isActionCurrent(context)) return
    await loadAll()
  }).finally(() => {
    acting.value = ''
  })
}

function cancelTask(task: TaskItem) {
  const context = captureAction()
  acting.value = task.taskId
  void ElMessageBox.confirm(`确定取消任务「${task.title}」？`, '取消任务', {
    confirmButtonText: '取消任务', cancelButtonText: '返回', type: 'warning',
  })
    .then(() => guard(async () => {
      await transitionTask(context.orgId, context.activityId, task.taskId, 'CANCELLED')
      if (!isActionCurrent(context)) return
      await loadAll()
    }, context))
    .catch(() => undefined)
    .finally(() => {
      acting.value = ''
    })
}

function openCreateMaterial() {
  editingMaterial.value = null
  pendingMaterialCreate.value = null
  errorText.value = ''
  materialForm.value = { name: '', quantity: 1, unit: '个', assigneeMemberId: '', note: '' }
  initialMaterialForm = JSON.stringify(materialForm.value)
  materialDialogVisible.value = true
}

function openEditMaterial(material: MaterialItem) {
  editingMaterial.value = material
  pendingMaterialCreate.value = null
  errorText.value = ''
  materialForm.value = {
    name: material.name,
    quantity: material.quantity,
    unit: material.unit,
    assigneeMemberId: material.assigneeMemberId ?? '',
    note: material.note ?? '',
  }
  initialMaterialForm = JSON.stringify(materialForm.value)
  materialDialogVisible.value = true
}

async function setMaterialStatus(material: MaterialItem, toStatus: string) {
  acting.value = material.materialId
  void guard(async (context) => {
    await changeMaterialStatus(context.orgId, context.activityId, material.materialId, toStatus)
    if (!isActionCurrent(context)) return
    await loadAll()
  }).finally(() => {
    acting.value = ''
  })
}

function triggerUpload() {
  if (pendingActivityUpload.value) void sendUpload({ kind: 'activity' })
  else if (!pendingUpload.value) uploadInput.value?.click()
}

async function sendUpload(scope: AttachmentScope, file?: File) {
  const checking = pendingUpload.value !== null
  if (uploading.value || taskUploading.value) return
  if (!pendingUpload.value) {
    if (!file) return
    pendingUpload.value = { file, key: newIdempotencyKey(), orgId: orgId.value, activityId: activityId.value, scope }
  }
  const pending = pendingUpload.value
  if (pending.scope.kind !== scope.kind
    || (scope.kind === 'task' && pending.scope.kind === 'task' && pending.scope.taskId !== scope.taskId)) return
  if (scope.kind === 'task') taskUploading.value = true
  else uploading.value = true
  const failure = await guard(async (context) => {
    if (checking) await fetchAttachmentByRequestKey(pending.orgId, pending.activityId, pending.key)
    else await uploadAttachment(pending.orgId, pending.activityId, pending.file, pending.key, pending.scope)
    if (!isActionCurrent(context)) return
    pendingUpload.value = null
    if (scope.kind === 'task') await loadTaskAttachments()
    await loadAll()
  })
  if (failure instanceof ApiError && !failure.isTransportError
    && ((!checking && [400, 403, 404, 413, 415, 422, 429].includes(failure.status)) || failure.status === 410)) pendingUpload.value = null
  if (checking && failure instanceof ApiError && failure.status === 404) errorText.value = '尚未找到上传结果，请稍后继续核对；原文件与请求仍保留。'
  if (scope.kind === 'task') taskUploading.value = false
  else uploading.value = false
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files?.[0]) void sendUpload({ kind: 'activity' }, input.files[0])
  input.value = ''
}

function confirmDeleteAttachment(attachment: AttachmentItem) {
  const context = captureAction()
  acting.value = attachment.attachmentId
  void ElMessageBox.confirm(`确定删除「${attachment.originalName}」？`, '删除附件', {
    confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
  })
    .then(() => guard(async () => {
      await deleteAttachment(context.orgId, context.activityId, attachment.attachmentId)
      if (!isActionCurrent(context)) return
      await loadAll()
    }, context))
    .catch(() => undefined)
    .finally(() => {
      acting.value = ''
    })
}

async function submitMaterial() {
  if (materialSubmitting.value) return
  materialSubmitting.value = true
  const target = editingMaterial.value
  const failure = await guard(async (context) => {
    const payload: SaveMaterialInput = pendingMaterialCreate.value?.input ?? {
      name: materialForm.value.name.trim(),
      quantity: Number(materialForm.value.quantity),
      unit: materialForm.value.unit.trim(),
      assigneeMemberId: materialForm.value.assigneeMemberId
        ? Number(materialForm.value.assigneeMemberId) : undefined,
      note: materialForm.value.note.trim() || undefined,
      ...(target ? { version: target.version } : {}),
    }
    if (target) {
      await updateMaterial(context.orgId, context.activityId, target.materialId, payload)
    } else {
      if (!pendingMaterialCreate.value) pendingMaterialCreate.value = { input: payload, key: newIdempotencyKey() }
      await createMaterial(context.orgId, context.activityId, pendingMaterialCreate.value.input, pendingMaterialCreate.value.key)
    }
    if (!isActionCurrent(context)) return
    pendingMaterialCreate.value = null
    materialDialogVisible.value = false
    editingMaterial.value = null
    await loadAll()
  })
  if (failure instanceof ApiError && !failure.isTransportError
    && [400, 403, 404, 422, 429].includes(failure.status)) pendingMaterialCreate.value = null
  materialSubmitting.value = false
}

const downloadingId = ref('')
async function download(attachment: AttachmentItem) {
  if (downloadingId.value) return
  const context = captureAction()
  downloadingId.value = attachment.attachmentId
  errorText.value = ''
  try {
    await downloadAttachment(context.orgId, context.activityId, attachment.attachmentId, attachment.originalName)
  } catch (error) {
    if (!isActionCurrent(context) || session.observeUnauthorized(error, context.generation)) return
    const message = readErrorMessage(error, '下载失败，请重试。')
    if (previewVisible.value) previewError.value = message
    else errorText.value = message
  } finally {
    downloadingId.value = ''
  }
}

// P3.1：允许类型预览——图片与 PDF 在弹窗内直接查看（走同一受控端点，带会话 Cookie），
// 其余类型（文本、Office、ZIP）没有可靠的浏览器内置查看器，保留下载。
const previewVisible = ref(false)
const previewTarget = ref<AttachmentItem | null>(null)
const previewUrl = ref('')
const previewLoading = ref(false)
const previewError = ref('')
const previewSkeleton = useLoadingSkeleton()
let previewRequestSeq = 0

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

async function openPreview(attachment: AttachmentItem) {
  previewTarget.value = attachment
  previewVisible.value = true
  const seq = ++previewRequestSeq
  const context = captureAction()
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  previewError.value = ''
  previewLoading.value = true
  previewSkeleton.begin()
  try {
    const response = await request<Blob>(attachmentUrl(attachment), { response: 'blob' })
    if (seq === previewRequestSeq && isActionCurrent(context)) previewUrl.value = URL.createObjectURL(response.data)
  } catch (error) {
    if (seq !== previewRequestSeq || !isActionCurrent(context) || session.observeUnauthorized(error, context.generation)) return
    previewError.value = readErrorMessage(error, '资料预览加载失败，请重试。')
  } finally {
    if (seq === previewRequestSeq) {
      previewLoading.value = false
      previewSkeleton.end()
    }
  }
}

function clearPreview() {
  previewRequestSeq++
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  previewSkeleton.hide()
}
watch(previewVisible, value => { if (!value) clearPreview() })
onBeforeUnmount(clearPreview)

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
  const context = captureAction()
  taskAttachmentLoading.value = true
  taskAttachmentLoaded.value = false
  // 当前任务读取成功前不展示上一任务的资料或空表格。
  taskAttachments.value = []
  const failure = await guard(async () => {
    const items = await fetchAttachments(context.orgId, context.activityId,
      { kind: 'task', taskId: task.taskId })
    if (isActionCurrent(context) && taskAttachmentTarget.value?.taskId === task.taskId) taskAttachments.value = items
  }, context)
  if (!isActionCurrent(context) || taskAttachmentTarget.value?.taskId !== task.taskId) return
  taskAttachmentLoaded.value = failure === null
  taskAttachmentLoading.value = false
}

function triggerTaskUpload() {
  if (pendingTaskUpload.value && taskAttachmentTarget.value) {
    void sendUpload({ kind: 'task', taskId: taskAttachmentTarget.value.taskId })
  } else if (!pendingUpload.value) taskUploadInput.value?.click()
}

function onTaskFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  const task = taskAttachmentTarget.value
  if (file && task) void sendUpload({ kind: 'task', taskId: task.taskId }, file)
  input.value = ''
}

async function closeTaskAttachments(done: () => void = () => { taskAttachmentVisible.value = false }) {
  if (taskUploading.value) return
  if (pendingTaskUpload.value) {
    try {
      await ElMessageBox.confirm('上传结果尚未确认。放弃后请先核对任务资料列表，避免重复上传。确定关闭？', '结果待核对', {
        confirmButtonText: '放弃核对', cancelButtonText: '继续核对', type: 'warning',
      })
    } catch { return }
    pendingUpload.value = null
  }
  done()
}

function confirmDeleteTaskAttachment(attachment: AttachmentItem) {
  const context = captureAction()
  const task = taskAttachmentTarget.value
  if (!task) {
    return
  }
  acting.value = attachment.attachmentId
  void ElMessageBox.confirm(`确定删除「${attachment.originalName}」？`, '删除任务资料', {
    confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
  })
    .then(() => guard(async () => {
      await deleteAttachment(context.orgId, context.activityId, attachment.attachmentId)
      if (!isActionCurrent(context)) return
      await loadTaskAttachments()
      if (!isActionCurrent(context)) return
      await loadAll()
    }, context))
    .catch(() => undefined)
    .finally(() => {
      acting.value = ''
    })
}

defineExpose({
  reload: loadAll,
  hasUnsavedForm: () => taskDirty.value || materialDirty.value || pendingUpload.value !== null,
  isSubmitting: () => taskSubmitting.value || materialSubmitting.value || uploading.value || taskUploading.value,
})
</script>

<template>
  <div class="p3-sections">
    <p
      v-if="errorText && !taskAttachmentVisible"
      class="p3-error"
      role="alert"
    >
      {{ errorText }}
      <ElButton
        size="small"
        @click="loadAll"
      >
        重新加载
      </ElButton>
    </p>
    <p
      v-if="frozen"
      class="p3-note"
    >
      活动安排调整中：任务与物资在重新确认前为只读。
    </p>

    <section class="p3-block">
      <div class="p3-block__head">
        <div>
          <h2>任务</h2><p class="p3-note">
            明确分工，跟进每项任务的完成状态
          </p>
        </div>
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
      <ElSkeleton
        v-if="(loading || loaded) && skeleton.loading.value && tasks.length === 0"
        :rows="4"
        animated
        data-p3-skeleton="tasks"
        aria-busy="true"
        aria-label="正在加载任务"
      />
      <ElTable
        v-else-if="loading || loaded"
        :data="tasks"
      >
        <template #empty>
          <span>{{ loaded ? '暂无任务。' : '正在加载任务…' }}</span>
        </template>
        <ElTableColumn
          label="任务"
          :min-width="readUiLength('--ui-table-col-200')"
        >
          <template #default="{ row }">
            <span>{{ row.title }}</span>
            <p
              v-if="row.description"
              class="p3-task-description"
            >
              {{ row.description }}
            </p>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="截止时间"
          :min-width="readUiLength('--ui-table-col-150')"
        >
          <template #default="{ row }">
            {{ row.dueAt ? formatDateTime(row.dueAt) : '未设置' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="负责人"
          :min-width="readUiLength('--ui-table-col-110')"
        >
          <template #default="{ row }">
            {{ usernameOf(row.assigneeMemberId) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="优先级"
          :min-width="readUiLength('--ui-table-col-80')"
        >
          <template #default="{ row }">
            {{ priorityLabel[row.priority] ?? row.priority }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="状态"
          :min-width="readUiLength('--ui-table-col-100')"
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
          :min-width="readUiLength('--ui-table-col-220')"
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
      </ElTable>
    </section>

    <section class="p3-block">
      <div class="p3-block__head">
        <div>
          <h2>物资</h2><p class="p3-note">
            核对数量、负责人和准备进度
          </p>
        </div>
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
      <ElSkeleton
        v-if="(loading || loaded) && skeleton.loading.value && materials.length === 0"
        :rows="3"
        animated
        data-p3-skeleton="materials"
        aria-busy="true"
        aria-label="正在加载物资"
      />
      <ElTable
        v-else-if="loading || loaded"
        :data="materials"
      >
        <template #empty>
          <span>{{ loaded ? '暂无物资。' : '正在加载物资…' }}</span>
        </template>
        <ElTableColumn
          prop="name"
          label="物资"
          :min-width="readUiLength('--ui-table-col-140')"
        />
        <ElTableColumn
          label="数量"
          :min-width="readUiLength('--ui-table-col-90')"
        >
          <template #default="{ row }">
            {{ row.quantity }} {{ row.unit }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="负责人"
          :min-width="readUiLength('--ui-table-col-110')"
        >
          <template #default="{ row }">
            {{ row.assigneeUsername || '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="状态"
          :min-width="readUiLength('--ui-table-col-100')"
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
          :min-width="readUiLength('--ui-table-col-260')"
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
      </ElTable>
    </section>

    <section class="p3-block">
      <div class="p3-block__head">
        <div>
          <h2>资料</h2><p class="p3-note">
            集中查看活动文件与协作资料
          </p>
        </div>
        <ElButton
          v-if="canManage"
          size="small"
          type="primary"
          plain
          :loading="uploading"
          :disabled="frozen || (Boolean(pendingUpload) && !pendingActivityUpload)"
          @click="triggerUpload"
        >
          {{ pendingActivityUpload && !uploading ? '核对上传结果' : '上传资料' }}
        </ElButton>
        <input
          ref="uploadInput"
          type="file"
          class="p3-upload-input"
          accept=".png,.jpg,.jpeg,.gif,.webp,.pdf,.txt,.docx,.xlsx,.pptx,.zip"
          :disabled="Boolean(pendingUpload)"
          @change="onFileChange"
        >
      </div>
      <p
        v-if="pendingActivityUpload"
        class="p3-note"
      >
        上传结果尚未确认，请用原文件核对，避免重复上传。
      </p>
      <ElSkeleton
        v-if="(loading || loaded) && skeleton.loading.value && attachments.length === 0"
        :rows="3"
        animated
        data-p3-skeleton="attachments"
        aria-busy="true"
        aria-label="正在加载资料"
      />
      <ElTable
        v-else-if="loading || loaded"
        :data="attachments"
      >
        <template #empty>
          <span>{{ loaded ? '暂无资料。' : '正在加载资料…' }}</span>
        </template>
        <ElTableColumn
          prop="originalName"
          label="文件名"
          :min-width="readUiLength('--ui-table-col-180')"
        />
        <ElTableColumn
          label="大小"
          :min-width="readUiLength('--ui-table-col-100')"
        >
          <template #default="{ row }">
            {{ Math.max(1, Math.round(row.sizeBytes / 1024)) }} KB
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="上传者"
          :min-width="readUiLength('--ui-table-col-110')"
        >
          <template #default="{ row }">
            {{ row.uploaderUsername }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          :min-width="readUiLength('--ui-table-col-140')"
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
              :loading="downloadingId === row.attachmentId"
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
      </ElTable>
    </section>

    <ElDialog
      v-model="taskAttachmentVisible"
      :title="`任务资料：${taskAttachmentTarget?.title ?? ''}`"
      width="min(var(--ui-dialog-width), calc(100vw - 2 * var(--ui-space-4)))"
      :before-close="closeTaskAttachments"
      :show-close="!taskUploading"
    >
      <p
        v-if="errorText"
        class="p3-error"
        role="alert"
      >
        {{ errorText }}
        <ElButton
          v-if="!taskAttachmentLoading && !taskAttachmentLoaded"
          size="small"
          @click="loadTaskAttachments"
        >
          重新加载
        </ElButton>
      </p>
      <p
        v-if="pendingTaskUpload"
        class="p3-note"
      >
        上传结果尚未确认，请用原文件核对，避免重复上传。
      </p>
      <div class="p3-block__head">
        <span class="p3-note">任务承担者可以上传与删除本人任务的资料。</span>
        <ElButton
          v-if="canUploadTaskAttachment"
          size="small"
          type="primary"
          plain
          :loading="taskUploading"
          :disabled="frozen || (Boolean(pendingUpload) && !pendingTaskUpload)"
          @click="triggerTaskUpload"
        >
          {{ pendingTaskUpload && !taskUploading ? '核对上传结果' : '上传资料' }}
        </ElButton>
        <input
          v-if="canUploadTaskAttachment"
          ref="taskUploadInput"
          type="file"
          class="p3-upload-input"
          accept=".png,.jpg,.jpeg,.gif,.webp,.pdf,.txt,.docx,.xlsx,.pptx,.zip"
          :disabled="Boolean(pendingUpload)"
          @change="onTaskFileChange"
        >
      </div>
      <ElSkeleton
        v-if="taskAttachmentLoading && taskAttachments.length === 0"
        :rows="3"
        animated
        data-p3-skeleton="task-attachments"
        aria-busy="true"
        aria-label="正在加载任务资料"
      />
      <ElTable
        v-else-if="taskAttachmentLoaded"
        :data="taskAttachments"
      >
        <template #empty>
          <span class="p3-note">该任务暂无资料。</span>
        </template>
        <ElTableColumn
          prop="originalName"
          label="文件名"
          :min-width="readUiLength('--ui-table-col-160')"
        />
        <ElTableColumn
          label="大小"
          :min-width="readUiLength('--ui-table-col-90')"
        >
          <template #default="{ row }">
            {{ Math.max(1, Math.round(row.sizeBytes / 1024)) }} KB
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="上传者"
          :min-width="readUiLength('--ui-table-col-110')"
        >
          <template #default="{ row }">
            {{ row.uploaderUsername }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          :min-width="readUiLength('--ui-table-col-140')"
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
              :loading="downloadingId === row.attachmentId"
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
      </ElTable>
      <template #footer>
        <ElButton
          :disabled="taskUploading"
          @click="closeTaskAttachments()"
        >
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
        <ElSkeleton
          v-if="previewSkeleton.loading.value"
          :rows="4"
          animated
          aria-busy="true"
          aria-label="正在加载资料预览"
        />
        <p
          v-else-if="previewError"
          class="p3-error"
          role="alert"
        >
          {{ previewError }} <ElButton
            v-if="previewTarget"
            @click="openPreview(previewTarget)"
          >
            重试
          </ElButton>
        </p>
        <img
          v-else-if="previewKind === 'image' && previewUrl"
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
          v-else-if="!previewLoading"
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
          :loading="downloadingId === previewTarget.attachmentId"
          @click="download(previewTarget)"
        >
          下载
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="taskDialogVisible"
      :title="editingTask ? '编辑任务' : '新建任务'"
      width="min(100%, var(--ui-dialog-width))"
      :before-close="closeTaskDialog"
      :close-on-click-modal="false"
      :show-close="!taskSubmitting"
    >
      <p
        v-if="errorText"
        class="p3-error"
        role="alert"
      >
        {{ errorText }}
      </p>
      <ElForm
        ref="taskFormRef"
        :rules="taskRules"
        :disabled="taskSubmitting || Boolean(pendingTaskCreate)"
        :model="taskForm"
        label-position="top"
      >
        <ElFormItem
          label="任务标题"
          prop="title"
        >
          <ElInput
            v-model="taskForm.title"
            placeholder="例如：联系场地并确认报价"
            :maxlength="128"
          />
        </ElFormItem>
        <ElFormItem
          label="任务负责人"
          prop="assigneeMemberId"
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
        <ElFormItem
          label="说明"
          prop="description"
        >
          <ElInput
            v-model="taskForm.description"
            type="textarea"
            :rows="2"
            placeholder="选填，说明交付要求与注意事项"
            :maxlength="2000"
          />
        </ElFormItem>
        <ElFormItem label="截止时间（北京时间）">
          <ElInput
            v-model="taskForm.dueAt"
            type="datetime-local"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton
          :disabled="taskSubmitting"
          @click="closeTaskDialog"
        >
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="taskSubmitting"
          @click="submitTask"
        >
          {{ editingTask ? '保存' : pendingTaskCreate ? '核对创建结果' : '创建' }}
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="materialDialogVisible"
      :title="editingMaterial ? '编辑物资' : '添加物资'"
      width="var(--ui-dialog-width)"
      :before-close="closeMaterialDialog"
      :show-close="!materialSubmitting"
    >
      <p
        v-if="errorText"
        class="p3-error"
        role="alert"
      >
        {{ errorText }}
      </p>
      <ElForm
        :model="materialForm"
        :disabled="materialSubmitting || Boolean(pendingMaterialCreate)"
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
        <ElButton
          :disabled="materialSubmitting"
          @click="closeMaterialDialog()"
        >
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="materialSubmitting"
          @click="submitMaterial"
        >
          {{ editingMaterial ? '保存' : pendingMaterialCreate ? '核对创建结果' : '添加' }}
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

.p3-sections { display: grid; gap: var(--ui-space-6); min-width: 0; }
.p3-block { min-width: 0; padding: var(--ui-space-5); border: var(--ui-border-width) solid var(--ui-color-border); border-radius: var(--ui-radius-card); background: var(--ui-color-surface); }
.p3-block__head { padding-bottom: var(--ui-space-4); border-bottom: var(--ui-border-width) solid var(--ui-color-border); flex-wrap: wrap; }
.p3-block__head .p3-note { margin-top: var(--ui-space-1); }
.p3-block :deep(.el-table__header th) { background: var(--ui-color-canvas); }
.p3-block :deep(.el-table__cell) { padding-top: var(--ui-space-4); padding-bottom: var(--ui-space-4); }

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
.p3-task-description {
  margin-top: var(--ui-space-2);
  color: var(--ui-color-text-secondary);
  font-size: var(--ui-font-size-small);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
