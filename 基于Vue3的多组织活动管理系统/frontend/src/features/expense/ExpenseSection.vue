<script setup lang="ts">
import { readUiLength } from '../../shared/utils/readUiToken'
import { useLoadingSkeleton } from '../../shared/utils/useLoadingSkeleton'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, ElMessage, ElMessageBox, ElOption,
  ElPagination, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag,
} from 'element-plus'
import { ApiError, readErrorMessage } from '../../shared/http/error'
import { newIdempotencyKey } from '../../shared/http/idempotency'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { downloadAttachment, fetchAttachmentByRequestKey, resolveMyMemberId } from '../activity/p3'
import {
  confirmExpense, createExpense, createExpenseCorrection, expenseCategoryLabel, expenseStatusLabel,
  fetchExpenseSummary, fetchExpenses, fetchVouchers, rejectExpense, submitExpense, updateExpense,
  uploadVoucher, voidExpense,
  type ExpenseCategory, type ExpenseItem, type ExpenseStatus, type ExpenseSummary, type SaveExpenseInput, type VoucherItem,
} from './expense'

// P5 经费区块：预算与统计、支出明细与状态动作、凭证上传，按角色展示。
// 权限仍由后端逐请求判定：登记＝组织负责人或本活动负责人；确认／驳回／作废＝仅组织负责人。
const props = defineProps<{
  orgDetail: {
    id: string
    status?: string
    ownerMemberId?: string
    budget?: string
  } | null
  orgRole?: 'OWNER' | 'MEMBER' | null
}>()
const emit = defineEmits<{ changed: [] }>()

const route = useRoute()
const session = useSessionStore()
const orgId = computed(() => String(route.params.orgId ?? ''))
const activityId = computed(() => String(route.params.activityId ?? ''))

const PAGE_SIZE = 20
const items = ref<ExpenseItem[]>([])
const summary = ref<ExpenseSummary | null>(null)
const loading = ref(false)
/** 是否已完成一次成功读取；读取失败时不渲染空表格。 */
const loaded = ref(false)
/** 骨架只在首次进入时延时出现（leading／trailing 来自集中 token）。 */
const skeleton = useLoadingSkeleton()
const errorText = ref('')
const actionError = ref('')
const acting = ref('')
const myMemberId = ref<string | null>(null)
const statusFilter = ref('')
const page = ref(1)
const total = ref(0)

const dialogVisible = ref(false)
const dialogSubmitting = ref(false)
const editing = ref<ExpenseItem | null>(null)
const form = ref({
  purpose: '',
  amount: 0,
  incurredOn: '',
  category: 'MATERIAL' as ExpenseCategory,
  handledNote: '',
  noReceiptNote: '',
})
const formInitial = ref({ ...form.value })
const pendingCreate = ref<SaveExpenseInput | null>(null)
const createUnverifiable = ref(false)
watch(pendingCreate, value => { if (!value) createUnverifiable.value = false })
let createKey = ''

const vouchers = ref<VoucherItem[]>([])
const voucherUploading = ref(false)
const voucherViewerVisible = ref(false)
const voucherInput = ref<HTMLInputElement | null>(null)
const pendingVoucher = ref<{ file: File; key: string; expenseId: string } | null>(null)
const pendingCorrection = ref<{ item: ExpenseItem; input: SaveExpenseInput; key: string } | null>(null)
const correctionSubmitting = ref(false)
const formDirty = computed(() => dialogVisible.value
  && (pendingCreate.value !== null || pendingVoucher.value !== null
    || JSON.stringify(form.value) !== JSON.stringify(formInitial.value)))

const rejectVisible = ref(false)
const rejectSubmitting = ref(false)
const rejectTarget = ref<ExpenseItem | null>(null)
const rejectReason = ref('')
const overBudgetVisible = ref(false)
const overBudgetSubmitting = ref(false)
const overBudgetTarget = ref<ExpenseItem | null>(null)
const overBudgetNote = ref('')
const overBudgetDetail = ref('')

/** 登记与查看：组织负责人或本活动负责人；前端只决定展示。 */
const canRegister = computed(() => props.orgRole === 'OWNER'
  || (myMemberId.value !== null && myMemberId.value === props.orgDetail?.ownerMemberId))
/** 确认、驳回与作废：仅组织负责人（活动负责人兼任时同样通过）。 */
const canDecide = computed(() => props.orgRole === 'OWNER')
const canWrite = computed(() => canRegister.value
  && ['APPROVED', 'EXECUTING', 'REVIEWING', 'CANCELLED'].includes(props.orgDetail?.status ?? ''))

const statusType: Record<ExpenseStatus, 'info' | 'warning' | 'success' | 'danger'> = {
  DRAFT: 'info',
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  VOIDED: 'info',
}

let requestSeq = 0
let alive = true
let actionGeneration = 0
watch([orgId, activityId], () => { actionGeneration++ }, { flush: 'sync' })
onBeforeUnmount(() => { alive = false; requestSeq++ })

async function loadAll(): Promise<void> {
  if (!orgId.value || !activityId.value) {
    return
  }
  const seq = ++requestSeq
  const gen = session.capture()
  loading.value = true
  // 已有内容时是后台刷新（含翻页与筛选），不重放骨架
  if (items.value.length > 0) {
    skeleton.hide()
  } else {
    skeleton.begin()
  }
  errorText.value = ''
  try {
    const memberId = await resolveMyMemberId(orgId.value, session.account?.username ?? '')
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    myMemberId.value = memberId
    if (!canRegister.value) {
      items.value = []
      summary.value = null
      total.value = 0
      loaded.value = true
      return
    }
    const [pageResult, summaryResult] = await Promise.all([
      fetchExpenses(orgId.value, activityId.value, statusFilter.value || undefined, page.value, PAGE_SIZE),
      fetchExpenseSummary(orgId.value, activityId.value),
    ])
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    items.value = pageResult.items
    total.value = pageResult.total
    summary.value = summaryResult
    loaded.value = true
    if (pageResult.items.length === 0 && pageResult.total > 0 && page.value > 1) {
      page.value = Math.max(1, Math.ceil(pageResult.total / PAGE_SIZE))
      await loadAll()
    }
  } catch (error) {
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    items.value = []
    summary.value = null
    // 失败仅展示错误与重试，不继续显示加载占位或空表格。
    loaded.value = false
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = readErrorMessage(error, '经费信息加载失败，请重试。')
  } finally {
    if (seq === requestSeq) {
      loading.value = false
      skeleton.end()
    }
  }
}

watch([orgId, activityId], () => {
  pendingCreate.value = null
  createKey = ''
  pendingVoucher.value = null
  pendingCorrection.value = null
  voucherViewerVisible.value = false
  dialogVisible.value = false
  vouchers.value = []
  page.value = 1
  items.value = []
  summary.value = null
  loaded.value = false
  skeleton.hide()
  void loadAll()
})
watch(statusFilter, () => {
  page.value = 1
  void loadAll()
})
watch(page, () => {
  void loadAll()
})
onMounted(loadAll)

function captureAction() {
  return { orgId: orgId.value, activityId: activityId.value, generation: session.capture(), scope: actionGeneration }
}

function isActionCurrent(context: ReturnType<typeof captureAction>): boolean {
  return alive && context.orgId === orgId.value && context.activityId === activityId.value
    && context.generation === session.generation && context.scope === actionGeneration
}

async function guard(fn: (context: ReturnType<typeof captureAction>) => Promise<void>, context = captureAction()): Promise<unknown | null> {
  if (!isActionCurrent(context)) return null
  actionError.value = ''
  try {
    await fn(context)
    return null
  } catch (error) {
    if (session.observeUnauthorized(error, context.generation) || !isActionCurrent(context)) {
      return error
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
    return error
  }
}

function openCreate() {
  editing.value = null
  pendingCreate.value = null
  createKey = ''
  pendingVoucher.value = null
  actionError.value = ''
  form.value = {
    purpose: '',
    amount: 0,
    incurredOn: new Date().toISOString().slice(0, 10),
    category: 'MATERIAL',
    handledNote: '',
    noReceiptNote: '',
  }
  formInitial.value = { ...form.value }
  vouchers.value = []
  dialogVisible.value = true
}

async function closeDialog(done?: () => void): Promise<void> {
  if (dialogSubmitting.value || voucherUploading.value) return
  const needsRecheck = pendingCreate.value !== null || pendingVoucher.value !== null
  if (formDirty.value) {
    try {
      await ElMessageBox.confirm(pendingCreate.value
        ? '上次保存结果尚未确认。离开后请先核对支出列表，避免重复登记。确定离开？'
        : pendingVoucher.value
          ? '上次上传结果尚未确认。离开后请先核对凭证列表，避免重复上传。确定离开？'
          : '支出信息还未保存，确定放弃修改？', '未保存的修改', {
        confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning',
      })
    } catch { return }
  }
  pendingCreate.value = null
  createKey = ''
  pendingVoucher.value = null
  if (done) done()
  else dialogVisible.value = false
  if (needsRecheck) void loadAll()
}

/** 票据查看不依赖支出是否仍可编辑，确认及归档后也可核对原件。 */
async function openVouchers(item: ExpenseItem) {
  voucherViewerTarget.value = item
  voucherViewerVisible.value = true
  await loadVoucherViewer()
}

const voucherViewerTarget = ref<ExpenseItem | null>(null)
const voucherViewerError = ref('')
const voucherViewerLoading = ref(false)
const voucherSkeleton = useLoadingSkeleton()
async function loadVoucherViewer() {
  const item = voucherViewerTarget.value
  if (!item || voucherViewerLoading.value) return
  const context = captureAction()
  acting.value = item.expenseId
  vouchers.value = []
  voucherViewerError.value = ''
  voucherViewerLoading.value = true
  voucherSkeleton.begin()
  try {
    const result = await fetchVouchers(context.orgId, context.activityId, item.expenseId)
    if (!isActionCurrent(context)) return
    vouchers.value = result
  } catch (error) {
    if (isActionCurrent(context) && !session.observeUnauthorized(error, context.generation)) voucherViewerError.value = readErrorMessage(error, '凭证加载失败，请重试。')
  } finally {
    voucherViewerLoading.value = false
    voucherSkeleton.end()
    acting.value = ''
  }
}

async function openEdit(item: ExpenseItem) {
  editing.value = item
  pendingVoucher.value = null
  actionError.value = ''
  form.value = {
    purpose: item.purpose,
    amount: Number(item.amount),
    incurredOn: item.incurredOn,
    category: item.category,
    handledNote: item.handledNote ?? '',
    noReceiptNote: item.noReceiptNote ?? '',
  }
  formInitial.value = { ...form.value }
  vouchers.value = []
  dialogVisible.value = true
  await guard(async (context) => {
    const result = await fetchVouchers(context.orgId, context.activityId, item.expenseId)
    if (isActionCurrent(context) && editing.value?.expenseId === item.expenseId) vouchers.value = result
  })
}

function inputOf() {
  return {
    purpose: form.value.purpose.trim(),
    amount: Number(form.value.amount).toFixed(2),
    incurredOn: form.value.incurredOn,
    category: form.value.category,
    handledNote: form.value.handledNote.trim() || undefined,
    noReceiptNote: form.value.noReceiptNote.trim() || undefined,
  }
}

/** 表单前置校验：与后端同一口径，避免把明显无效的请求发出去。 */
function validateForm(): string | null {
  if (!form.value.purpose.trim()) return '请填写支出用途'
  if (!(Number(form.value.amount) > 0)) return '支出金额必须大于零'
  if (!form.value.incurredOn) return '请填写费用发生日期'
  return null
}

async function submitForm() {
  if (createUnverifiable.value) return
  if (dialogSubmitting.value || voucherUploading.value || pendingVoucher.value) return
  const invalid = pendingCreate.value ? null : validateForm()
  if (invalid) {
    actionError.value = invalid
    return
  }
  dialogSubmitting.value = true
  if (!editing.value && !pendingCreate.value) {
    pendingCreate.value = inputOf()
    createKey = newIdempotencyKey()
  }
  const failure = await guard(async (context) => {
    if (editing.value) {
      await updateExpense(context.orgId, context.activityId, editing.value.expenseId,
        inputOf(), editing.value.version)
    } else {
      await createExpense(context.orgId, context.activityId, pendingCreate.value!, createKey)
    }
    if (!isActionCurrent(context)) return
    pendingCreate.value = null
    createKey = ''
    dialogVisible.value = false
    await loadAll()
    if (isActionCurrent(context)) emit('changed')
  })
  if (failure instanceof ApiError && !failure.isTransportError
    && [400, 403, 404, 422, 429].includes(failure.status)) {
    pendingCreate.value = null
    createKey = ''
  }
  if (pendingCreate.value && failure instanceof ApiError && failure.code === 'IDEMPOTENCY_RESULT_UNVERIFIABLE') createUnverifiable.value = true
  dialogSubmitting.value = false
}

async function uploadVoucherFile(file?: File) {
  const checking = pendingVoucher.value !== null
  const target = editing.value
  if (!target || voucherUploading.value) return
  if (!pendingVoucher.value) {
    if (!file) return
    pendingVoucher.value = { file, key: newIdempotencyKey(), expenseId: target.expenseId }
  }
  const pending = pendingVoucher.value
  voucherUploading.value = true
  const failure = await guard(async (context) => {
    if (checking) await fetchAttachmentByRequestKey(context.orgId, context.activityId, pending.key)
    else await uploadVoucher(context.orgId, context.activityId, pending.expenseId, pending.file, pending.key)
    if (!isActionCurrent(context)) return
    const result = await fetchVouchers(context.orgId, context.activityId, target.expenseId)
    if (!isActionCurrent(context)) return
    vouchers.value = result
    pendingVoucher.value = null
    await loadAll()
  })
  if (failure instanceof ApiError && !failure.isTransportError
    && ((!checking && [400, 403, 404, 413, 415, 422, 429].includes(failure.status)) || failure.status === 410)) pendingVoucher.value = null
  if (checking && failure instanceof ApiError && failure.status === 404) actionError.value = '尚未找到上传结果，请稍后继续核对；原文件与请求仍保留。'
  voucherUploading.value = false
}

function onVoucherChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) {
    void uploadVoucherFile(file)
  }
  input.value = ''
}

function submit(item: ExpenseItem) {
  acting.value = item.expenseId
  void guard(async (context) => {
    await submitExpense(context.orgId, context.activityId, item.expenseId, item.version)
    if (!isActionCurrent(context)) return
    await loadAll()
    if (isActionCurrent(context)) emit('changed')
  }).finally(() => {
    acting.value = ''
  })
}

/** 确认：超支时后端要求说明，界面弹框收集后再次确认（不静默失败）。 */
function confirm(item: ExpenseItem) {
  acting.value = item.expenseId
  void guard(async (context) => {
    try {
      await confirmExpense(context.orgId, context.activityId, item.expenseId, item.version)
      if (!isActionCurrent(context)) return
      await loadAll()
      if (isActionCurrent(context)) emit('changed')
    } catch (error) {
      if (error instanceof ApiError && error.code === 'EXPENSE_OVER_BUDGET') {
        if (!isActionCurrent(context)) return
        overBudgetTarget.value = item
        overBudgetNote.value = ''
        overBudgetDetail.value = error.detail
        overBudgetVisible.value = true
        return
      }
      throw error
    }
  }).finally(() => {
    acting.value = ''
  })
}

async function confirmOverBudget() {
  if (overBudgetSubmitting.value) return
  const target = overBudgetTarget.value
  if (!target) {
    return
  }
  if (!overBudgetNote.value.trim()) {
    actionError.value = '超出预算时必须填写超支说明'
    return
  }
  overBudgetSubmitting.value = true
  await guard(async (context) => {
    await confirmExpense(context.orgId, context.activityId, target.expenseId, target.version,
      overBudgetNote.value.trim())
    if (!isActionCurrent(context)) return
    overBudgetVisible.value = false
    await loadAll()
    if (isActionCurrent(context)) emit('changed')
  })
  overBudgetSubmitting.value = false
}

function openReject(item: ExpenseItem) {
  rejectTarget.value = item
  rejectReason.value = ''
  rejectVisible.value = true
}

async function confirmReject() {
  if (rejectSubmitting.value) return
  const target = rejectTarget.value
  if (!target || !rejectReason.value.trim()) {
    actionError.value = '驳回需要说明原因'
    return
  }
  rejectSubmitting.value = true
  await guard(async (context) => {
    await rejectExpense(context.orgId, context.activityId, target.expenseId, target.version,
      rejectReason.value.trim())
    if (!isActionCurrent(context)) return
    rejectVisible.value = false
    await loadAll()
    if (isActionCurrent(context)) emit('changed')
  })
  rejectSubmitting.value = false
}

function voidConfirmed(item: ExpenseItem) {
  const context = captureAction()
  acting.value = item.expenseId
  void ElMessageBox.prompt('作废后不再计入统计；如需更正，请对原记录建立更正登记。', '作废支出', {
    confirmButtonText: '作废',
    cancelButtonText: '返回',
    inputPlaceholder: '作废原因',
    type: 'warning',
    inputValidator: value => Boolean(value?.trim()) || '请填写作废原因',
    beforeClose: async (action, instance, done) => {
      if (instance.confirmButtonLoading) return
      if (action !== 'confirm') { done(); return }
      instance.confirmButtonLoading = true
      const failure = await guard(async () => {
        await voidExpense(context.orgId, context.activityId, item.expenseId, item.version, instance.inputValue.trim())
        if (!isActionCurrent(context)) return
        await loadAll()
        if (isActionCurrent(context)) emit('changed')
      }, context)
      instance.confirmButtonLoading = false
      if (!failure || !isActionCurrent(context)) done()
      else instance.editorErrorMessage = actionError.value
    },
  })
    .catch(() => undefined)
    .finally(() => {
      acting.value = ''
    })
}

/** 更正登记：结果未知时沿用原请求核对，避免同一原记录出现两笔更正。 */
async function correct(item: ExpenseItem) {
  const context = captureAction()
  if (acting.value || (pendingCorrection.value && pendingCorrection.value.item.expenseId !== item.expenseId)) return
  if (!pendingCorrection.value) {
    try {
      await ElMessageBox.confirm('将按原记录新建一条关联的更正登记，重新提交并确认；原记录不再计入统计。', '更正登记', {
        confirmButtonText: '建立更正', cancelButtonText: '返回', type: 'info',
      })
    } catch { return }
    if (!isActionCurrent(context)) return
    pendingCorrection.value = {
      item,
      key: newIdempotencyKey(),
      input: {
        purpose: `${item.purpose}（更正）`,
        amount: item.amount,
        incurredOn: item.incurredOn,
        category: item.category,
        handledNote: item.handledNote ?? undefined,
        noReceiptNote: item.noReceiptNote ?? undefined,
      },
    }
  }
  const pending = pendingCorrection.value
  acting.value = item.expenseId
  correctionSubmitting.value = true
  const failure = await guard(async () => {
    await createExpenseCorrection(context.orgId, context.activityId, item.expenseId, pending.input, pending.key)
    if (!isActionCurrent(context)) return
    pendingCorrection.value = null
    ElMessage.success('已建立更正登记，请提交并确认')
    await loadAll()
    if (isActionCurrent(context)) emit('changed')
  }, context)
  if (failure instanceof ApiError && !failure.isTransportError
    && [400, 403, 404, 422, 429].includes(failure.status)) pendingCorrection.value = null
  correctionSubmitting.value = false
  acting.value = ''
}

const downloadingVoucherId = ref('')
const voucherDownloadError = ref('')
async function downloadVoucher(voucher: VoucherItem) {
  if (downloadingVoucherId.value) return
  const context = captureAction()
  downloadingVoucherId.value = voucher.attachmentId
  voucherDownloadError.value = ''
  try {
    await downloadAttachment(context.orgId, context.activityId, voucher.attachmentId, voucher.originalName)
  } catch (error) {
    if (!isActionCurrent(context) || session.observeUnauthorized(error, context.generation)) return
    voucherDownloadError.value = readErrorMessage(error, '凭证下载失败，请重试。')
  } finally {
    downloadingVoucherId.value = ''
  }
}

defineExpose({ reload: loadAll, hasUnsavedForm: () => formDirty.value || pendingCorrection.value !== null,
  isSubmitting: () => dialogSubmitting.value || voucherUploading.value || correctionSubmitting.value })
</script>

<template>
  <section
    v-if="canRegister"
    class="expense-section"
  >
    <div class="expense-section__head">
      <h2>经费</h2>
      <div class="expense-section__tools">
        <ElSelect
          v-model="statusFilter"
          size="small"
          placeholder="全部状态"
          class="expense-section__filter"
        >
          <ElOption
            label="全部状态"
            value=""
          />
          <ElOption
            v-for="(label, value) in expenseStatusLabel"
            :key="value"
            :label="label"
            :value="value"
          />
        </ElSelect>
        <ElButton
          v-if="canWrite"
          size="small"
          type="primary"
          plain
          @click="openCreate"
        >
          登记支出
        </ElButton>
      </div>
    </div>

    <p
      v-if="errorText"
      class="expense-section__error"
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
      v-if="actionError && !dialogVisible"
      class="expense-section__error"
      role="alert"
    >
      {{ actionError }}
    </p>
    <p
      v-if="pendingCorrection"
      class="expense-note"
    >
      更正登记结果尚未确认，请用原请求核对，避免重复建立更正记录。
      <ElButton
        size="small"
        link
        :loading="acting === pendingCorrection.item.expenseId"
        @click="correct(pendingCorrection.item)"
      >
        核对更正结果
      </ElButton>
    </p>

    <dl
      v-if="summary"
      class="expense-summary"
    >
      <div><dt>活动预算（元）</dt><dd>{{ summary.budget }}</dd></div>
      <div><dt>待确认支出（元）</dt><dd>{{ summary.pendingAmount }}</dd></div>
      <div><dt>已确认支出（元）</dt><dd>{{ summary.approvedAmount }}</dd></div>
      <div>
        <dt>预算余额（元）</dt>
        <dd :class="{ 'expense-summary__negative': summary.balance.startsWith('-') }">
          {{ summary.balance }}
        </dd>
      </div>
    </dl>
    <p
      v-if="summary"
      class="expense-note"
    >
      预算调整走活动「编辑信息」；已确认活动需先「撤回确认」再修改（计划书 6.1）。
      金额只按已确认明细扣减，余额可以为负；已确认支出不代表付款完成。
    </p>

    <ElSkeleton
      v-if="(loading || loaded) && skeleton.loading.value && items.length === 0"
      :rows="4"
      animated
      data-expense-skeleton
      aria-busy="true"
      aria-label="正在加载经费明细"
    />
    <ElTable
      v-else-if="loading || loaded"
      :data="items"
      class="expense-table"
    >
      <template #empty>
        <span>{{ loaded ? '暂无支出记录。' : '正在加载经费明细…' }}</span>
      </template>
      <ElTableColumn
        prop="purpose"
        label="用途"
        :min-width="readUiLength('--ui-table-col-150')"
      />
      <ElTableColumn
        label="金额（元）"
        :min-width="readUiLength('--ui-table-col-110')"
      >
        <template #default="{ row }">
          {{ (row as ExpenseItem).amount }}
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="分类"
        :min-width="readUiLength('--ui-table-col-100')"
      >
        <template #default="{ row }">
          {{ expenseCategoryLabel[(row as ExpenseItem).category] ?? (row as ExpenseItem).category }}
        </template>
      </ElTableColumn>
      <ElTableColumn
        prop="incurredOn"
        label="发生日期"
        :min-width="readUiLength('--ui-table-col-120')"
      />
      <ElTableColumn
        label="凭证"
        :min-width="readUiLength('--ui-table-col-80')"
      >
        <template #default="{ row }">
          <ElButton
            v-if="Number((row as ExpenseItem).voucherCount) > 0"
            size="small"
            link
            :loading="acting === (row as ExpenseItem).expenseId"
            @click="openVouchers(row as ExpenseItem)"
          >
            查看凭证
          </ElButton>
          <span v-else>—</span>
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="状态"
        :min-width="readUiLength('--ui-table-col-100')"
      >
        <template #default="{ row }">
          <ElTag
            :type="statusType[(row as ExpenseItem).status]"
            size="small"
          >
            {{ expenseStatusLabel[(row as ExpenseItem).status] }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="说明"
        :min-width="readUiLength('--ui-table-col-160')"
      >
        <template #default="{ row }">
          <span class="expense-note">
            {{ (row as ExpenseItem).rejectReason
              || (row as ExpenseItem).overBudgetNote
              || (row as ExpenseItem).voidReason
              || (row as ExpenseItem).noReceiptNote
              || '—' }}
          </span>
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="操作"
        :min-width="readUiLength('--ui-table-col-260')"
      >
        <template #default="{ row }">
          <span
            v-if="!canWrite"
            class="expense-note"
          >只读</span>
          <template v-else-if="(row as ExpenseItem).status === 'DRAFT' || (row as ExpenseItem).status === 'REJECTED'">
            <ElButton
              size="small"
              @click="openEdit(row as ExpenseItem)"
            >
              编辑
            </ElButton>
            <ElButton
              size="small"
              type="primary"
              plain
              :loading="acting === (row as ExpenseItem).expenseId"
              @click="submit(row as ExpenseItem)"
            >
              提交
            </ElButton>
          </template>
          <template v-else-if="(row as ExpenseItem).status === 'PENDING'">
            <ElButton
              v-if="canDecide"
              size="small"
              type="primary"
              plain
              :loading="acting === (row as ExpenseItem).expenseId"
              @click="confirm(row as ExpenseItem)"
            >
              确认
            </ElButton>
            <ElButton
              v-if="canDecide"
              size="small"
              type="danger"
              plain
              @click="openReject(row as ExpenseItem)"
            >
              驳回
            </ElButton>
          </template>
          <template v-else-if="(row as ExpenseItem).status === 'APPROVED'">
            <ElButton
              v-if="canDecide"
              size="small"
              type="danger"
              plain
              :loading="acting === (row as ExpenseItem).expenseId"
              @click="voidConfirmed(row as ExpenseItem)"
            >
              作废
            </ElButton>
          </template>
          <template v-else-if="(row as ExpenseItem).status === 'VOIDED'">
            <!-- 计划书 6.3：更正已确认费用时“先作废原记录，再建立关联新登记”，因此作废后仍可发起更正 -->
            <ElButton
              size="small"
              :loading="acting === (row as ExpenseItem).expenseId"
              :disabled="Boolean(pendingCorrection) && pendingCorrection?.item.expenseId !== (row as ExpenseItem).expenseId"
              @click="correct(row as ExpenseItem)"
            >
              {{ pendingCorrection?.item.expenseId === (row as ExpenseItem).expenseId ? '核对更正结果' : '更正登记' }}
            </ElButton>
          </template>
          <span
            v-else
            class="expense-note"
          >—</span>
        </template>
      </ElTableColumn>
    </ElTable>

    <ElPagination
      v-if="total > PAGE_SIZE"
      class="expense-section__pager"
      layout="prev, pager, next, total"
      :total="total"
      :page-size="PAGE_SIZE"
      :current-page="page"
      @current-change="(value: number) => { page = value }"
    />

    <ElDialog
      v-model="voucherViewerVisible"
      title="支出凭证"
      width="min(var(--ui-dialog-width), calc(100vw - 2 * var(--ui-space-4)))"
    >
      <p
        v-if="voucherDownloadError"
        class="expense-section__error"
        role="alert"
      >
        {{ voucherDownloadError }}
      </p>
      <ElSkeleton
        v-if="voucherSkeleton.loading.value"
        :rows="3"
        animated
        aria-busy="true"
        aria-label="正在加载支出凭证"
      />
      <p
        v-else-if="voucherViewerError"
        class="expense-section__error"
        role="alert"
      >
        {{ voucherViewerError }} <ElButton @click="loadVoucherViewer">
          重试
        </ElButton>
      </p>
      <ul
        v-else-if="vouchers.length"
        class="expense-vouchers__list"
      >
        <li
          v-for="voucher in vouchers"
          :key="voucher.attachmentId"
        >
          <ElButton
            link
            :loading="downloadingVoucherId === voucher.attachmentId"
            @click="downloadVoucher(voucher)"
          >
            {{ voucher.originalName }}
          </ElButton>
        </li>
      </ul>
      <p
        v-else-if="!voucherViewerLoading"
        class="expense-note"
      >
        暂无凭证。
      </p>
    </ElDialog>

    <ElDialog
      v-model="dialogVisible"
      :title="editing ? '编辑支出' : '登记支出'"
      width="min(var(--ui-dialog-width), calc(100vw - 2 * var(--ui-space-4)))"
      :before-close="closeDialog"
    >
      <p
        v-if="actionError"
        class="expense-section__error"
        role="alert"
      >
        {{ actionError }}
      </p>
      <p
        v-if="pendingCreate"
        class="expense-note"
      >
        {{ createUnverifiable ? '旧请求结果无法确认，请先核对支出列表，不要更换请求键再次创建。' : '保存结果尚未确认。当前内容暂不可修改；用原请求核对结果，或先查看支出列表。' }}
      </p>
      <p
        v-if="pendingVoucher"
        class="expense-note"
      >
        上传结果尚未确认。请用原文件核对结果，或先查看凭证列表。
      </p>
      <ElForm
        :model="form"
        label-position="top"
        :disabled="dialogSubmitting || voucherUploading || Boolean(pendingCreate)"
      >
        <ElFormItem
          label="支出用途"
          required
        >
          <ElInput
            v-model="form.purpose"
            placeholder="例如：横幅制作"
          />
        </ElFormItem>
        <ElFormItem
          label="金额（元）"
          required
        >
          <ElInputNumber
            v-model="form.amount"
            :min="0.01"
            :precision="2"
            :step="1"
          />
        </ElFormItem>
        <ElFormItem
          label="发生日期"
          required
        >
          <ElInput
            v-model="form.incurredOn"
            type="date"
          />
        </ElFormItem>
        <ElFormItem
          label="分类"
          required
        >
          <ElSelect v-model="form.category">
            <ElOption
              v-for="(label, value) in expenseCategoryLabel"
              :key="value"
              :label="label"
              :value="value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="经手说明">
          <ElInput
            v-model="form.handledNote"
            placeholder="选填"
          />
        </ElFormItem>
        <ElFormItem label="无票说明">
          <ElInput
            v-model="form.noReceiptNote"
            placeholder="没有票据时必填"
          />
        </ElFormItem>
        <ElFormItem
          v-if="editing"
          label="凭证"
        >
          <div class="expense-vouchers">
            <p
              v-if="voucherDownloadError"
              class="expense-section__error"
              role="alert"
            >
              {{ voucherDownloadError }}
            </p>
            <ul
              v-if="vouchers.length > 0"
              class="expense-vouchers__list"
            >
              <li
                v-for="voucher in vouchers"
                :key="voucher.attachmentId"
              >
                <ElButton
                  size="small"
                  link
                  :loading="downloadingVoucherId === voucher.attachmentId"
                  @click="downloadVoucher(voucher)"
                >
                  {{ voucher.originalName }}
                </ElButton>
              </li>
            </ul>
            <span
              v-else
              class="expense-note"
            >尚未上传凭证；没有票据时请填写无票说明。</span>
            <input
              ref="voucherInput"
              type="file"
              class="expense-vouchers__input"
              accept=".png,.jpg,.jpeg,.gif,.webp,.pdf,.txt,.docx,.xlsx,.pptx,.zip"
              :disabled="Boolean(pendingVoucher)"
              @change="onVoucherChange"
            >
            <ElButton
              size="small"
              :loading="voucherUploading"
              @click="pendingVoucher ? uploadVoucherFile() : voucherInput?.click()"
            >
              {{ pendingVoucher && !voucherUploading ? '核对上传结果' : '上传凭证' }}
            </ElButton>
          </div>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton
          :disabled="dialogSubmitting || voucherUploading"
          @click="closeDialog()"
        >
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="dialogSubmitting"
          :disabled="voucherUploading || Boolean(pendingVoucher) || createUnverifiable"
          @click="submitForm"
        >
          {{ createUnverifiable ? '请先核对支出列表' : pendingCreate && !dialogSubmitting ? '核对保存结果' : '保存' }}
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="rejectVisible"
      title="驳回支出"
      width="min(var(--ui-dialog-width), calc(100vw - 2 * var(--ui-space-4)))"
    >
      <p
        v-if="actionError"
        class="expense-section__error"
        role="alert"
      >
        {{ actionError }}
      </p>
      <ElInput
        v-model="rejectReason"
        :disabled="rejectSubmitting"
        type="textarea"
        :rows="2"
        placeholder="请说明驳回原因"
      />
      <template #footer>
        <ElButton
          :disabled="rejectSubmitting"
          @click="rejectVisible = false"
        >
          取消
        </ElButton>
        <ElButton
          type="danger"
          :loading="rejectSubmitting"
          @click="confirmReject"
        >
          驳回
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="overBudgetVisible"
      title="超出预算，需要说明"
      width="min(var(--ui-dialog-width), calc(100vw - 2 * var(--ui-space-4)))"
    >
      <p
        v-if="actionError"
        class="expense-section__error"
        role="alert"
      >
        {{ actionError }}
      </p>
      <p class="expense-note">
        {{ overBudgetDetail }}
      </p>
      <ElInput
        v-model="overBudgetNote"
        :disabled="overBudgetSubmitting"
        type="textarea"
        :rows="2"
        placeholder="请填写超支说明（必填）"
      />
      <template #footer>
        <ElButton
          :disabled="overBudgetSubmitting"
          @click="overBudgetVisible = false"
        >
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="overBudgetSubmitting"
          @click="confirmOverBudget"
        >
          确认支出
        </ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.expense-section {
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
}

.expense-section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-4);
  flex-wrap: wrap;
}

.expense-section__head h2 {
  font-size: var(--ui-font-size-section);
}

.expense-section__tools {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
}

.expense-section__filter {
  width: var(--ui-filter-width);
}

.expense-section__error {
  margin-top: var(--ui-space-3);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.expense-summary {
  display: grid;
  gap: var(--ui-layout-grid-gap);
  grid-template-columns: repeat(auto-fill, minmax(min(100%, var(--ui-layout-card-min-width)), 1fr));
  margin-top: var(--ui-space-4);
}

.expense-summary dt {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.expense-summary dd {
  margin-top: var(--ui-space-1);
  font-size: var(--ui-font-size-emphasis);
  font-variant-numeric: tabular-nums;
}

.expense-summary__negative {
  color: var(--ui-color-danger);
}

.expense-table {
  margin-top: var(--ui-space-5);
  width: 100%;
}

.expense-section__pager {
  margin-top: var(--ui-space-4);
}

.expense-note {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}

.expense-vouchers {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.expense-vouchers__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.expense-vouchers__input {
  display: none;
}
</style>
