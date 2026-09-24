<script setup lang="ts">
import { readUiLength } from '../../shared/utils/readUiToken'
import { useLoadingSkeleton } from '../../shared/utils/useLoadingSkeleton'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, ElMessage, ElMessageBox, ElOption,
  ElPagination, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag,
} from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { resolveMyMemberId } from '../activity/p3'
import {
  confirmExpense, createExpense, createExpenseCorrection, expenseCategoryLabel, expenseStatusLabel,
  fetchExpenseSummary, fetchExpenses, fetchVouchers, rejectExpense, submitExpense, updateExpense,
  uploadVoucher, voidExpense, voucherDownloadUrl,
  type ExpenseCategory, type ExpenseItem, type ExpenseStatus, type ExpenseSummary, type VoucherItem,
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
/** 是否已完成一次成功读取；未完成时的空表格只能显示“正在加载”，不能显示“暂无”。 */
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

const vouchers = ref<VoucherItem[]>([])
const voucherUploading = ref(false)
const voucherInput = ref<HTMLInputElement | null>(null)

const rejectVisible = ref(false)
const rejectTarget = ref<ExpenseItem | null>(null)
const rejectReason = ref('')
const overBudgetVisible = ref(false)
const overBudgetTarget = ref<ExpenseItem | null>(null)
const overBudgetNote = ref('')
const overBudgetDetail = ref('')

/** 登记与查看：组织负责人或本活动负责人；前端只决定展示。 */
const canRegister = computed(() => props.orgRole === 'OWNER'
  || (myMemberId.value !== null && myMemberId.value === props.orgDetail?.ownerMemberId))
/** 确认、驳回与作废：仅组织负责人（活动负责人兼任时同样通过）。 */
const canDecide = computed(() => props.orgRole === 'OWNER')

const statusType: Record<ExpenseStatus, 'info' | 'warning' | 'success' | 'danger'> = {
  DRAFT: 'info',
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  VOIDED: 'info',
}

let requestSeq = 0

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
    // 失败不冒充空态：表格继续显示“正在加载…”而不是“暂无”
    loaded.value = false
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '经费信息加载失败，请重试。'
  } finally {
    if (seq === requestSeq) {
      loading.value = false
      skeleton.end()
    }
  }
}

watch([orgId, activityId], () => {
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

function openCreate() {
  editing.value = null
  form.value = {
    purpose: '',
    amount: 0,
    incurredOn: new Date().toISOString().slice(0, 10),
    category: 'MATERIAL',
    handledNote: '',
    noReceiptNote: '',
  }
  vouchers.value = []
  dialogVisible.value = true
}

async function openEdit(item: ExpenseItem) {
  editing.value = item
  form.value = {
    purpose: item.purpose,
    amount: Number(item.amount),
    incurredOn: item.incurredOn,
    category: item.category,
    handledNote: item.handledNote ?? '',
    noReceiptNote: item.noReceiptNote ?? '',
  }
  vouchers.value = []
  dialogVisible.value = true
  await guard(async () => {
    vouchers.value = await fetchVouchers(orgId.value, activityId.value, item.expenseId)
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
  const invalid = validateForm()
  if (invalid) {
    actionError.value = invalid
    return
  }
  dialogSubmitting.value = true
  await guard(async () => {
    if (editing.value) {
      await updateExpense(orgId.value, activityId.value, editing.value.expenseId,
        inputOf(), editing.value.version)
    } else {
      await createExpense(orgId.value, activityId.value, inputOf())
    }
    dialogVisible.value = false
    await loadAll()
    emit('changed')
  })
  dialogSubmitting.value = false
}

async function uploadVoucherFile(file: File) {
  const target = editing.value
  if (!target) {
    return
  }
  voucherUploading.value = true
  await guard(async () => {
    await uploadVoucher(orgId.value, activityId.value, target.expenseId, file)
    vouchers.value = await fetchVouchers(orgId.value, activityId.value, target.expenseId)
    await loadAll()
  })
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
  void guard(async () => {
    await submitExpense(orgId.value, activityId.value, item.expenseId, item.version)
    await loadAll()
    emit('changed')
  }).finally(() => {
    acting.value = ''
  })
}

/** 确认：超支时后端要求说明，界面弹框收集后再次确认（不静默失败）。 */
function confirm(item: ExpenseItem) {
  acting.value = item.expenseId
  void guard(async () => {
    try {
      await confirmExpense(orgId.value, activityId.value, item.expenseId, item.version)
      await loadAll()
      emit('changed')
    } catch (error) {
      if (error instanceof ApiError && error.code === 'EXPENSE_OVER_BUDGET') {
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
  const target = overBudgetTarget.value
  if (!target) {
    return
  }
  if (!overBudgetNote.value.trim()) {
    actionError.value = '超出预算时必须填写超支说明'
    return
  }
  await guard(async () => {
    await confirmExpense(orgId.value, activityId.value, target.expenseId, target.version,
      overBudgetNote.value.trim())
    overBudgetVisible.value = false
    await loadAll()
    emit('changed')
  })
}

function openReject(item: ExpenseItem) {
  rejectTarget.value = item
  rejectReason.value = ''
  rejectVisible.value = true
}

async function confirmReject() {
  const target = rejectTarget.value
  if (!target || !rejectReason.value.trim()) {
    actionError.value = '驳回需要说明原因'
    return
  }
  await guard(async () => {
    await rejectExpense(orgId.value, activityId.value, target.expenseId, target.version,
      rejectReason.value.trim())
    rejectVisible.value = false
    await loadAll()
    emit('changed')
  })
}

function voidConfirmed(item: ExpenseItem) {
  acting.value = item.expenseId
  void ElMessageBox.prompt('作废后不再计入统计；如需更正，请对原记录建立更正登记。', '作废支出', {
    confirmButtonText: '作废',
    cancelButtonText: '返回',
    inputPlaceholder: '作废原因',
    type: 'warning',
  })
    .then(({ value }) => guard(async () => {
      await voidExpense(orgId.value, activityId.value, item.expenseId, item.version, value?.trim() || '作废')
      await loadAll()
      emit('changed')
    }))
    .catch(() => undefined)
    .finally(() => {
      acting.value = ''
    })
}

/** 更正登记：以原记录为模板新建关联草稿，重新提交与确认。 */
function correct(item: ExpenseItem) {
  acting.value = item.expenseId
  void ElMessageBox.confirm('将按原记录新建一条关联的更正登记，重新提交并确认；原记录不再计入统计。', '更正登记', {
    confirmButtonText: '建立更正',
    cancelButtonText: '返回',
    type: 'info',
  })
    .then(() => guard(async () => {
      await createExpenseCorrection(orgId.value, activityId.value, item.expenseId, {
        purpose: `${item.purpose}（更正）`,
        amount: item.amount,
        incurredOn: item.incurredOn,
        category: item.category,
        handledNote: item.handledNote ?? undefined,
        noReceiptNote: item.noReceiptNote ?? undefined,
      })
      ElMessage.success('已建立更正登记，请提交并确认')
      await loadAll()
      emit('changed')
    }))
    .catch(() => undefined)
    .finally(() => {
      acting.value = ''
    })
}

function downloadVoucher(attachmentId: string) {
  const link = document.createElement('a')
  link.href = voucherDownloadUrl(orgId.value, activityId.value, attachmentId)
  document.body.appendChild(link)
  link.click()
  link.remove()
}

defineExpose({ reload: loadAll })
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
      v-if="actionError"
      class="expense-section__error"
      role="alert"
    >
      {{ actionError }}
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
      v-if="skeleton.loading.value && items.length === 0"
      :rows="4"
      animated
      data-expense-skeleton
      aria-busy="true"
      aria-label="正在加载经费明细"
    />
    <ElTable
      v-else
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
          {{ (row as ExpenseItem).voucherCount }}
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
          <template v-if="(row as ExpenseItem).status === 'DRAFT' || (row as ExpenseItem).status === 'REJECTED'">
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
            <ElButton
              size="small"
              @click="correct(row as ExpenseItem)"
            >
              更正登记
            </ElButton>
          </template>
          <template v-else-if="(row as ExpenseItem).status === 'VOIDED'">
            <!-- 计划书 6.3：更正已确认费用时“先作废原记录，再建立关联新登记”，因此作废后仍可发起更正 -->
            <ElButton
              size="small"
              @click="correct(row as ExpenseItem)"
            >
              更正登记
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
      v-model="dialogVisible"
      :title="editing ? '编辑支出' : '登记支出'"
      width="var(--ui-dialog-width)"
    >
      <ElForm
        :model="form"
        label-position="top"
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
                  @click="downloadVoucher(voucher.attachmentId)"
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
              @change="onVoucherChange"
            >
            <ElButton
              size="small"
              :loading="voucherUploading"
              @click="voucherInput?.click()"
            >
              上传凭证
            </ElButton>
          </div>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="dialogSubmitting"
          @click="submitForm"
        >
          保存
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="rejectVisible"
      title="驳回支出"
      width="var(--ui-dialog-width)"
    >
      <ElInput
        v-model="rejectReason"
        type="textarea"
        :rows="2"
        placeholder="请说明驳回原因"
      />
      <template #footer>
        <ElButton @click="rejectVisible = false">
          取消
        </ElButton>
        <ElButton
          type="danger"
          @click="confirmReject"
        >
          驳回
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="overBudgetVisible"
      title="超出预算，需要说明"
      width="var(--ui-dialog-width)"
    >
      <p class="expense-note">
        {{ overBudgetDetail }}
      </p>
      <ElInput
        v-model="overBudgetNote"
        type="textarea"
        :rows="2"
        placeholder="请填写超支说明（必填）"
      />
      <template #footer>
        <ElButton @click="overBudgetVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
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
