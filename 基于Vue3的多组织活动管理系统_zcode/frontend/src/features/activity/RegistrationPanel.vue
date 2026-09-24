<script setup lang="ts">
import { readUiLength } from '../../shared/utils/readUiToken'
import { useLoadingSkeleton } from '../../shared/utils/useLoadingSkeleton'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessageBox, ElOption, ElPagination, ElSelect,
  ElSkeleton, ElSwitch, ElTable, ElTableColumn, ElTag,
} from 'element-plus'
import { ApiError } from '../../shared/http/error'
import type { FormInstance, FormRules } from 'element-plus'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { resolveMyMemberId } from './p3'
import {
  applyRegistration, approveRegistration, cancelMyRegistration, cancelRegistrationByManager,
  exportRegistrationRoster, fetchActivityRegistrations, fetchMyRegistrations,
  registrationStatusLabel, rejectRegistration, saveRosterCsv, setRegistrationOpen,
} from './registrations'
import type { RegistrationItem } from './registrations'

// 活动详情的报名面板（P4.1—P4.3）：本人申请／取消，管理者开关报名、审核与导出名单。
// 后端逐请求鉴权；这里按角色决定展示，并把服务端错误原样反馈。
const props = defineProps<{
  orgDetail: {
    id: string
    status?: string
    ownerMemberId?: string
    registrationOpen?: boolean
    startTime?: string | null
  } | null
  orgRole?: 'OWNER' | 'MEMBER' | null
}>()
const emit = defineEmits<{ changed: [] }>()

const route = useRoute()
const session = useSessionStore()
const orgId = computed(() => String(route.params.orgId ?? ''))
const activityId = computed(() => String(route.params.activityId ?? ''))

const mine = ref<RegistrationItem | null>(null)
const roster = ref<RegistrationItem[]>([])
const myMemberId = ref<string | null>(null)
const loading = ref(false)
/** 是否已完成一次成功读取；未完成时的空名册只能显示“正在加载”，不能显示“暂无”。 */
const loaded = ref(false)
/** 骨架只在首次进入时延时出现（leading／trailing 来自集中 token）。 */
const skeleton = useLoadingSkeleton()
const errorText = ref('')
const acting = ref('')

const applyVisible = ref(false)
const applySubmitting = ref(false)
const applyForm = ref({ displayName: '', note: '' })
/** F10：报名表单失焦即校验，提交前再整体校验一次。 */
const applyFormRef = ref<FormInstance | null>(null)
const applyRules: FormRules = {
  displayName: [
    { required: true, message: '请填写展示姓名', trigger: 'blur' },
    { min: 1, max: 64, message: '展示姓名 1-64 字符', trigger: 'blur' },
  ],
  note: [{ max: 500, message: '备注不超过 500 字符', trigger: 'blur' }],
}

const rejectVisible = ref(false)
const rejectTarget = ref<RegistrationItem | null>(null)
const rejectReason = ref('')
const rejectSubmitting = ref(false)

const toggleSaving = ref(false)
const rosterFilter = ref('')
/** F09：管理名单分页（服务端分页，默认每页 20 条），total 决定页码控件。 */
const ROSTER_PAGE_SIZE = 20
const rosterPage = ref(1)
const rosterTotal = ref(0)

/** 组织负责人或本活动负责人可审核、开关报名与导出；前端只决定展示，权限仍由后端判定。 */
const canManage = computed(() => props.orgRole === 'OWNER'
  || (myMemberId.value !== null && myMemberId.value === props.orgDetail?.ownerMemberId))

const registrationOpen = ref(props.orgDetail?.registrationOpen === true)
// 父组件重新加载活动后同步开关显示（不直接改写 props）
watch(() => props.orgDetail?.registrationOpen, (value) => {
  registrationOpen.value = value === true
})
/** 已确认且未开始才允许开关报名，与后端 setRegistrationOpen 的校验保持一致。 */
const canToggleOpen = computed(() => canManage.value
  && props.orgDetail?.status === 'APPROVED'
  && (!props.orgDetail?.startTime || new Date(props.orgDetail.startTime) > new Date()))

const cancellable = computed(() => mine.value !== null
  && (mine.value.status === 'PENDING' || mine.value.status === 'APPROVED'))

const statusType: Record<string, 'success' | 'info' | 'warning' | 'danger'> = {
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  CANCELLED_SELF: 'info',
  CANCELLED_ORG: 'info',
}

let requestSeq = 0

async function loadAll(): Promise<void> {
  if (!orgId.value || !activityId.value) {
    return
  }
  const seq = ++requestSeq
  const gen = session.capture()
  loading.value = true
  // 已有名册时是后台刷新，不重放骨架
  if (roster.value.length > 0) {
    skeleton.hide()
  } else {
    skeleton.begin()
  }
  errorText.value = ''
  try {
    const [mineItems, memberId] = await Promise.all([
      fetchMyRegistrations(),
      resolveMyMemberId(orgId.value, session.account?.username ?? ''),
    ])
    const current = mineItems.find((item) => item.activityId === activityId.value) ?? null
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    mine.value = current
    myMemberId.value = memberId
    if (canManage.value) {
      const page = await fetchActivityRegistrations(
        orgId.value, activityId.value, rosterFilter.value || undefined, rosterPage.value, ROSTER_PAGE_SIZE,
      )
      if (seq !== requestSeq || gen !== session.generation) {
        return
      }
      roster.value = page.items
      rosterTotal.value = page.total
      // 当前页被处理为空时回退到最后一个有效页，避免停在空页
      if (page.items.length === 0 && page.total > 0 && rosterPage.value > 1) {
        rosterPage.value = Math.max(1, Math.ceil(page.total / ROSTER_PAGE_SIZE))
        await loadAll()
        return
      }
    } else {
      roster.value = []
      rosterTotal.value = 0
    }
    loaded.value = true
  } catch (error) {
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    roster.value = []
    // 失败不冒充空态：名册继续显示“正在加载…”而不是“暂无”
    loaded.value = false
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '报名信息加载失败，请重试。'
  } finally {
    if (seq === requestSeq) {
      loading.value = false
      skeleton.end()
    }
  }
}

watch([orgId, activityId], () => {
  roster.value = []
  loaded.value = false
  skeleton.hide()
  return loadAll()
})
watch(rosterFilter, () => {
  rosterPage.value = 1
  if (canManage.value) {
    void loadAll()
  }
})
watch(rosterPage, () => {
  if (canManage.value) {
    void loadAll()
  }
})
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

function openApply() {
  applyForm.value = {
    displayName: mine.value?.displayName ?? session.account?.username ?? '',
    note: mine.value?.note ?? '',
  }
  applyVisible.value = true
}

async function submitApply() {
  const form = applyFormRef.value
  if (form) {
    const valid = await form.validate().catch(() => false)
    if (!valid) {
      return
    }
  }
  applySubmitting.value = true
  await guard(async () => {
    await applyRegistration(activityId.value, {
      displayName: applyForm.value.displayName.trim(),
      note: applyForm.value.note.trim() || undefined,
    })
    applyVisible.value = false
    await loadAll()
    emit('changed')
  })
  applySubmitting.value = false
}

function cancelMine() {
  acting.value = 'mine'
  void ElMessageBox.confirm('确定取消本人报名？名额将立即释放。', '取消报名', {
    confirmButtonText: '取消报名', cancelButtonText: '返回', type: 'warning',
  })
    .then(() => guard(async () => {
      await cancelMyRegistration(activityId.value)
      await loadAll()
      emit('changed')
    }))
    .catch(() => undefined)
    .finally(() => {
      acting.value = ''
    })
}

async function toggleOpen(value: boolean) {
  toggleSaving.value = true
  await guard(async () => {
    registrationOpen.value = await setRegistrationOpen(orgId.value, activityId.value, value)
    emit('changed')
  })
  toggleSaving.value = false
}

function approve(item: RegistrationItem) {
  acting.value = item.registrationId
  void guard(async () => {
    await approveRegistration(orgId.value, activityId.value, item.registrationId)
    await loadAll()
    emit('changed')
  }).finally(() => {
    acting.value = ''
  })
}

function openReject(item: RegistrationItem) {
  rejectTarget.value = item
  rejectReason.value = ''
  rejectVisible.value = true
}

async function submitReject() {
  const target = rejectTarget.value
  if (!target) {
    return
  }
  rejectSubmitting.value = true
  await guard(async () => {
    await rejectRegistration(orgId.value, activityId.value, target.registrationId, rejectReason.value.trim())
    rejectVisible.value = false
    rejectTarget.value = null
    await loadAll()
  })
  rejectSubmitting.value = false
}

function cancelByManager(item: RegistrationItem) {
  acting.value = item.registrationId
  void ElMessageBox.prompt('请填写取消原因', '管理取消报名', {
    confirmButtonText: '取消报名', cancelButtonText: '返回',
    inputPlaceholder: '例如：报名者请假',
    inputValidator: (value) => (value && value.trim() ? true : '必须填写原因'),
  })
    .then(({ value }) => guard(async () => {
      await cancelRegistrationByManager(orgId.value, activityId.value, item.registrationId, value.trim())
      await loadAll()
    }))
    .catch(() => undefined)
    .finally(() => {
      acting.value = ''
    })
}

function exportRoster() {
  void guard(async () => {
    // F09：超限时后端 409 EXPORT_TOO_LARGE，按业务错误提示而不是下载出错页
    const blob = await exportRegistrationRoster(orgId.value, activityId.value, rosterFilter.value || undefined)
    saveRosterCsv(blob, activityId.value, rosterFilter.value || undefined)
  })
}

defineExpose({ reload: loadAll })
</script>

<template>
  <section class="reg-panel">
    <div class="reg-panel__head">
      <h2>报名</h2>
      <div class="reg-panel__tools">
        <label
          v-if="canManage"
          class="reg-switch"
        >
          <span>报名开关</span>
          <ElSwitch
            :model-value="registrationOpen"
            :disabled="!canToggleOpen || toggleSaving"
            :loading="toggleSaving"
            @update:model-value="(value: string | number | boolean) => toggleOpen(Boolean(value))"
          />
        </label>
        <ElButton
          v-if="canManage"
          size="small"
          @click="exportRoster"
        >
          导出名单
        </ElButton>
      </div>
    </div>

    <p
      v-if="errorText"
      class="reg-error"
      role="alert"
    >
      {{ errorText }}
    </p>

    <p
      v-if="canManage && !canToggleOpen"
      class="reg-note"
    >
      报名开关仅在活动已确认且尚未开始时可以调整。
    </p>

    <div class="reg-mine">
      <div class="reg-mine__row">
        <span class="reg-label">我的报名</span>
        <ElTag
          v-if="mine"
          size="small"
          :type="statusType[mine.status] ?? 'info'"
        >
          {{ registrationStatusLabel[mine.status] ?? mine.status }}
        </ElTag>
        <span
          v-else
          class="reg-note"
        >
          尚未报名
        </span>
      </div>
      <p
        v-if="mine?.cancelReason"
        class="reg-note"
      >
        处理说明：{{ mine.cancelReason }}
      </p>
      <div class="reg-mine__actions">
        <ElButton
          size="small"
          type="primary"
          plain
          @click="openApply"
        >
          {{ mine ? '更新报名信息' : '我要报名' }}
        </ElButton>
        <ElButton
          v-if="cancellable"
          size="small"
          type="danger"
          plain
          :loading="acting === 'mine'"
          @click="cancelMine"
        >
          取消报名
        </ElButton>
      </div>
    </div>

    <template v-if="canManage">
      <div class="reg-roster__head">
        <span class="reg-label">报名名单</span>
        <ElSelect
          v-model="rosterFilter"
          size="small"
          placeholder="全部状态"
          clearable
        >
          <ElOption
            label="待审核"
            value="PENDING"
          />
          <ElOption
            label="已通过"
            value="APPROVED"
          />
          <ElOption
            label="已驳回"
            value="REJECTED"
          />
          <ElOption
            label="本人取消"
            value="CANCELLED_SELF"
          />
          <ElOption
            label="组织取消"
            value="CANCELLED_ORG"
          />
        </ElSelect>
      </div>
      <ElSkeleton
        v-if="skeleton.loading.value && roster.length === 0"
        :rows="4"
        animated
        data-registration-skeleton
        aria-busy="true"
        aria-label="正在加载报名名单"
      />
      <ElTable
        v-else
        :data="roster"
      >
        <template #empty>
          <span>{{ loaded ? '暂无报名记录。' : '正在加载报名名单…' }}</span>
        </template>
        <ElTableColumn
          prop="displayName"
          label="展示姓名"
          :min-width="readUiLength('--ui-table-col-120')"
        />
        <ElTableColumn
          prop="accountUsername"
          label="账号"
          :min-width="readUiLength('--ui-table-col-140')"
        />
        <ElTableColumn
          label="状态"
          :min-width="readUiLength('--ui-table-col-100')"
        >
          <template #default="{ row }">
            <ElTag
              size="small"
              :type="statusType[row.status] ?? 'info'"
            >
              {{ registrationStatusLabel[row.status as RegistrationItem['status']] ?? row.status }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="备注"
          :min-width="readUiLength('--ui-table-col-140')"
        >
          <template #default="{ row }">
            {{ row.note || '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          :min-width="readUiLength('--ui-table-col-200')"
        >
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <ElButton
                size="small"
                type="primary"
                :loading="acting === row.registrationId"
                @click="approve(row as RegistrationItem)"
              >
                通过
              </ElButton>
              <ElButton
                size="small"
                :loading="acting === row.registrationId"
                @click="openReject(row as RegistrationItem)"
              >
                驳回
              </ElButton>
            </template>
            <ElButton
              v-if="row.status === 'PENDING' || row.status === 'APPROVED'"
              size="small"
              type="danger"
              plain
              :loading="acting === row.registrationId"
              @click="cancelByManager(row as RegistrationItem)"
            >
              管理取消
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElPagination
        v-if="rosterTotal > ROSTER_PAGE_SIZE"
        class="reg-panel__pager"
        layout="prev, pager, next, total"
        :total="rosterTotal"
        :page-size="ROSTER_PAGE_SIZE"
        :current-page="rosterPage"
        @current-change="(value: number) => { rosterPage = value }"
      />
    </template>

    <ElDialog
      v-model="applyVisible"
      :title="mine ? '更新报名信息' : '报名'"
      width="var(--ui-dialog-width)"
    >
      <ElForm
        ref="applyFormRef"
        :model="applyForm"
        :rules="applyRules"
        label-position="top"
      >
        <ElFormItem
          label="展示姓名"
          prop="displayName"
          required
        >
          <ElInput
            v-model="applyForm.displayName"
            placeholder="报名姓名（1-64 字符）"
          />
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput
            v-model="applyForm.note"
            type="textarea"
            :rows="2"
            placeholder="选填，例如联系方式用途说明"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="applyVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="applySubmitting"
          @click="submitApply"
        >
          提交
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="rejectVisible"
      title="驳回报名"
      width="var(--ui-dialog-width)"
    >
      <ElForm label-position="top">
        <ElFormItem
          label="驳回原因"
          required
        >
          <ElInput
            v-model="rejectReason"
            type="textarea"
            :rows="2"
            placeholder="不超过 500 字符"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="rejectVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="rejectSubmitting"
          @click="submitReject"
        >
          确认驳回
        </ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.reg-panel {
  margin-top: var(--ui-space-10);
}

.reg-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  margin-bottom: var(--ui-space-4);
  flex-wrap: wrap;
}

.reg-panel__head h2 {
  font-size: var(--ui-font-size-section);
  font-weight: var(--ui-font-weight-semibold);
}

.reg-panel__tools {
  display: flex;
  align-items: center;
  gap: var(--ui-space-4);
}

.reg-switch {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}

.reg-error {
  margin-bottom: var(--ui-space-4);
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.reg-note {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}

.reg-mine {
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
}

.reg-mine__row {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
}

.reg-label {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
}

.reg-mine__actions {
  display: flex;
  gap: var(--ui-space-3);
  margin-top: var(--ui-space-4);
}

.reg-roster__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  margin: var(--ui-space-6) 0 var(--ui-space-3);
}
</style>
