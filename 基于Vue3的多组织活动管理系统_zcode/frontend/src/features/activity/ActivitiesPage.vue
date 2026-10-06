<script setup lang="ts">
import { readUiLength } from '../../shared/utils/readUiToken'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessageBox,
  type FormInstance,
  type FormRules,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { Calendar, Tickets } from '@element-plus/icons-vue'
import ListPage from '../../shared/components/ListPage.vue'
import { ApiError, readErrorMessage } from '../../shared/http/error'
import { newIdempotencyKey } from '../../shared/http/idempotency'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { createActivity, fetchActivities, fetchOwnerCandidates } from './api'
import type { MemberItem } from '../organization/types'
import { fetchOrganization } from '../organization/api'
import { scopeLabel, type ActivityItem, type ActivityStatus, type CreateActivityInput } from './types'
// 活动管理（P2.1）：OWNER 创建草稿并指定负责人；读取随路由与代次守卫重建（同成员页模式）。
const PAGE_SIZE = 20

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const orgId = computed(() => String(route.params.orgId ?? ''))

const items = ref<ActivityItem[]>([])
const total = ref(0)
const page = ref(1)
const query = ref('')
const appliedQuery = ref('')
const statusFilter = ref('')
const loading = ref(false)
const refreshing = ref(false)
const loadedOnce = ref(false)
const errorText = ref('')
const amOwner = ref(false)

const createVisible = ref(false)
const createSubmitting = ref(false)
const createError = ref('')
/** F06：当前创建意图的防重键；重试沿用，成功后清空。 */
let createKey = ''
const pendingCreate = ref<CreateActivityInput | null>(null)
const createUnverifiable = ref(false)
watch(pendingCreate, value => { if (!value) createUnverifiable.value = false })
const createdActivity = ref<ActivityItem | null>(null)
const createFormRef = ref<FormInstance>()
let initialForm = ''
const candidates = ref<MemberItem[]>([])
const candidatesLoading = ref(false)
const candidatesError = ref('')
let candidatesSeq = 0
const createForm = reactive({
  name: '',
  ownerMemberId: '',
  description: '',
  startTime: '',
  endTime: '',
  location: '',
  budget: '0.00',
  registrationScope: 'INTERNAL' as 'INTERNAL' | 'PUBLIC',
  capacity: null as number | null,
  registrationStart: '',
  registrationEnd: '',
})

const createDirty = computed(() => createVisible.value && JSON.stringify(createForm) !== initialForm)
const createRules: FormRules = {
  name: [
    { required: true, whitespace: true, message: '请输入活动主题', trigger: 'blur' },
    { max: 128, message: '活动主题不超过 128 字符', trigger: 'blur' },
  ],
  ownerMemberId: [{ required: true, message: '请选择活动负责人', trigger: 'change' }],
  description: [{ max: 2000, message: '活动简介不超过 2000 字符', trigger: 'blur' }],
  location: [{ max: 255, message: '活动地点不超过 255 字符', trigger: 'blur' }],
  budget: [{ pattern: /^\d+(\.\d{1,2})?$/, message: '请输入非负金额，最多两位小数', trigger: 'blur' }],
  endTime: [{ validator: (_rule, value, callback) => {
    if (value && createForm.startTime && value <= createForm.startTime) {
      callback(new Error('结束时间必须晚于开始时间'))
    } else callback()
  }, trigger: 'change' }],
  capacity: [{ validator: (_rule, value, callback) => {
    if (value != null && (!Number.isInteger(value) || value < 1 || value > 2147483647)) {
      callback(new Error('名额须为 1 至 2147483647 的整数'))
    } else callback()
  }, trigger: 'change' }],
}
watch(() => createForm.startTime, () => {
  if (createForm.endTime) void createFormRef.value?.validateField('endTime').catch(() => false)
})

async function confirmDiscard(): Promise<boolean> {
  if (!session.account) return true
  if (createSubmitting.value) return false
  if (!createDirty.value && !pendingCreate.value) return true
  try {
    await ElMessageBox.confirm(
      pendingCreate.value
        ? '上次保存结果尚未确认。离开后请先在活动列表核对，避免重复创建。确定离开？'
        : '填写的内容还未保存，确定放弃吗？',
      '未保存的活动',
      { confirmButtonText: '放弃并离开', cancelButtonText: '继续填写', type: 'warning' },
    )
    return true
  } catch { return false }
}
async function closeCreate() {
  if (await confirmDiscard()) createVisible.value = false
}
onBeforeRouteLeave(confirmDiscard)
onBeforeRouteUpdate((to, from) => to.path === from.path ? true : confirmDiscard())
function beforeUnload(event: BeforeUnloadEvent) {
  if (createDirty.value || pendingCreate.value || createSubmitting.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

/** 状态标签与色彩是活动模块的业务映射，沿用 Demo 的语义分配（台账见 UI 规范 5.6）。 */
const statusLabel: Record<ActivityStatus, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待确认',
  APPROVED: '已确认',
  EXECUTING: '执行中',
  REVIEWING: '复盘中',
  ARCHIVED: '已归档',
  CANCELLED: '已取消',
}

function statusTagType(status: ActivityStatus): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'APPROVED' || status === 'EXECUTING') {
    return 'success'
  }
  if (status === 'REVIEWING' || status === 'PENDING_APPROVAL') {
    return 'warning'
  }
  if (status === 'CANCELLED') {
    return 'danger'
  }
  return 'info'
}

/** 活动时间拆成日期与时刻两行，避免窄列把日期逐段切断。 */
function dateOf(value: string | null): string {
  return value ? value.slice(0, 10) : '待安排'
}

function timeOf(value: string | null): string {
  return value ? `${value.slice(11, 16)} 开始` : '时间待定'
}

const hasFilter = computed(() => Boolean(appliedQuery.value || statusFilter.value))

const emptyText = computed(() => {
  if (hasFilter.value) {
    return '没有符合条件的活动，请调整关键词或状态筛选。'
  }
  return amOwner.value
    ? '尚未创建活动，可从右上角创建活动草稿。'
    : '暂无可查看的活动；活动经组织负责人确认发布后会在这里展示。'
})

// R03 模式：请求序号 + 会话代次双守卫。
let requestSeq = 0

async function loadActivities(): Promise<void> {
  if (!orgId.value) {
    return
  }
  const seq = ++requestSeq
  const targetOrgId = orgId.value
  const gen = session.capture()
  // 首次加载显示同构骨架；已有数据时保留内容，只提示正在更新（UI 规范第 10 节）。
  if (items.value.length > 0) {
    refreshing.value = true
  } else {
    loading.value = true
  }
  errorText.value = ''
  try {
    const [result, organization] = await Promise.all([
      fetchActivities(targetOrgId, {
        page: page.value,
        query: appliedQuery.value || undefined,
        status: statusFilter.value || undefined,
      }),
      fetchOrganization(targetOrgId),
    ])
    if (seq !== requestSeq || targetOrgId !== orgId.value || gen !== session.generation) {
      return
    }
    items.value = result.items
    total.value = result.total
    amOwner.value = organization.role === 'OWNER'
  } catch (error) {
    if (seq !== requestSeq || targetOrgId !== orgId.value || gen !== session.generation) {
      return
    }
    items.value = []
    total.value = 0
    // 角色读取失败时隐藏创建入口，重试后按组织接口确认，不能从活动条数推断。
    amOwner.value = false
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = readErrorMessage(error, '活动列表加载失败，请重试。')
  } finally {
    if (seq === requestSeq) {
      loading.value = false
      refreshing.value = false
      loadedOnce.value = true
    }
  }
}

function openCreate() {
  if (!amOwner.value) return
  if (pendingCreate.value) {
    createVisible.value = true
    return
  }
  createKey = ''
  createError.value = ''
  createForm.name = ''
  createForm.ownerMemberId = ''
  createForm.description = ''
  createForm.startTime = ''
  createForm.endTime = ''
  createForm.location = ''
  createForm.budget = '0.00'
  createForm.registrationScope = 'INTERNAL'
  createForm.capacity = null
  createForm.registrationStart = ''
  createForm.registrationEnd = ''
  initialForm = JSON.stringify(createForm)
  void loadCandidates()
  createVisible.value = true
  void nextTick(() => createFormRef.value?.clearValidate())
}

async function loadCandidates() {
  const seq = ++candidatesSeq
  const targetOrgId = orgId.value
  const gen = session.capture()
  const isCurrent = () => seq === candidatesSeq && targetOrgId === orgId.value && gen === session.generation
  candidatesLoading.value = true
  candidatesError.value = ''
  candidates.value = []
  try {
    const result = await fetchOwnerCandidates(targetOrgId)
    if (isCurrent()) candidates.value = result
  } catch (error) {
    if (!isCurrent() || session.observeUnauthorized(error, gen)) return
    candidatesError.value = '负责人列表加载失败，请重试。'
  } finally {
    if (isCurrent()) candidatesLoading.value = false
  }
}

// 关闭、切换组织或离开页面后，不接受旧弹窗的候选人响应。
watch(createVisible, visible => {
  if (!visible) {
    candidatesSeq++
    candidatesLoading.value = false
  }
})
onBeforeUnmount(() => {
  requestSeq++
  candidatesSeq++
})

function search() {
  page.value = 1
  appliedQuery.value = query.value.trim()
  void loadActivities()
}

function clearFilters() {
  query.value = ''
  appliedQuery.value = ''
  statusFilter.value = ''
  page.value = 1
  void loadActivities()
}

function changePage(next: number) {
  page.value = next
  void loadActivities()
}

function openDetail(row: ActivityItem) {
  void router.push(`/org/${orgId.value}/activities/${row.id}`)
}

watch(orgId, () => {
  createVisible.value = false
  pendingCreate.value = null
  createdActivity.value = null
  createKey = ''
  candidatesSeq++
  candidates.value = []
  page.value = 1
  appliedQuery.value = ''
  query.value = ''
  items.value = []
  total.value = 0
  amOwner.value = false
  statusFilter.value = ''
  loadedOnce.value = false
  void loadActivities()
})
onMounted(() => {
  void loadActivities()
})

async function submitCreate() {
  if (createUnverifiable.value) return
  if (!amOwner.value || createSubmitting.value || candidatesLoading.value || candidatesError.value) return
  createSubmitting.value = true
  const targetOrgId = orgId.value
  const gen = session.capture()
  createError.value = ''
  try {
    if (!pendingCreate.value) {
      if (!(await createFormRef.value?.validate().catch(() => false))) return
      // 输入按界面标明的北京时间解释，符合接口的带偏移时间合同。
      const toIso = (value: string) => value ? `${value}:00+08:00` : undefined
      pendingCreate.value = {
        name: createForm.name.trim(),
        ownerMemberId: createForm.ownerMemberId,
        description: createForm.description.trim() || undefined,
        startTime: toIso(createForm.startTime),
        endTime: toIso(createForm.endTime),
        location: createForm.location.trim() || undefined,
        budget: createForm.budget || undefined,
        registrationScope: createForm.registrationScope,
        capacity: createForm.capacity ?? undefined,
      }
      createKey = newIdempotencyKey()
    }
    // 未知结果重试固定原键及内容，不因编辑表单产生第二次创建。
    const result = await createActivity(targetOrgId, pendingCreate.value, createKey)
    if (targetOrgId !== orgId.value || gen !== session.generation) return
    pendingCreate.value = null
    createKey = ''
    createVisible.value = false
    createdActivity.value = result
    await loadActivities()
  } catch (error) {
    if (targetOrgId !== orgId.value || gen !== session.generation) return
    if (session.observeUnauthorized(error, gen)) return
    if (error instanceof ApiError && !error.isTransportError) {
      if (error.code === 'IDEMPOTENCY_RESULT_UNVERIFIABLE') createUnverifiable.value = true
      // 明确拒绝的请求未产生草稿，可修改后以新意图提交；409／5xx 保留原请求待核对。
      if ([400, 403, 404, 422, 429].includes(error.status)) {
        pendingCreate.value = null
        createKey = ''
      }
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        createError.value = '安全校验已过期，请重新提交。'
      } else {
        createError.value = error.fieldErrors[0]?.message || error.detail || '创建失败，请检查输入后重试。'
      }
    } else {
      createError.value = '网络中断，暂时无法确认是否保存成功。请核对保存结果，不要重复新建。'
    }
  } finally {
    createSubmitting.value = false
  }
}

</script>

<template>
  <div
    v-if="createdActivity"
    class="activities-saved"
    role="status"
  >
    <p>草稿已保存，可继续完善活动方案。</p>
    <ElButton
      type="primary"
      @click="openDetail(createdActivity)"
    >
      查看活动草稿
    </ElButton>
  </div>
  <ListPage
    title="活动管理"
    panel-title="活动明细"
    description="从草稿开始安排活动，在详情中完善方案、分派任务并跟进进展。"
    :context="amOwner ? '组织负责人 · 全部活动' : '当前身份可见范围'"
    :error="errorText"
    :loading="loading"
    :refreshing="refreshing"
    :loaded="loadedOnce"
    :row-count="items.length"
    :total="total"
    :empty-text="emptyText"
    :skeleton-columns="6"
    retry-text="重新加载"
    @retry="loadActivities"
  >
    <template #actions>
      <ElButton
        v-if="amOwner"
        type="primary"
        @click="openCreate"
      >
        创建活动
      </ElButton>
    </template>

    <template #filters>
      <ElForm
        class="activities-search"
        :inline="true"
        @submit.prevent="search"
      >
        <ElFormItem>
          <ElInput
            v-model="query"
            placeholder="按活动主题搜索"
            aria-label="按活动主题搜索"
            clearable
            @clear="search"
          />
        </ElFormItem>
        <ElFormItem>
          <ElButton native-type="submit">
            搜索
          </ElButton>
        </ElFormItem>
        <ElFormItem class="activities-status-item">
          <ElSelect
            v-model="statusFilter"
            class="activities-status"
            placeholder="全部状态"
            aria-label="按活动状态筛选"
            @change="search"
          >
            <ElOption
              label="草稿"
              value="DRAFT"
            />
            <ElOption
              label="待确认"
              value="PENDING_APPROVAL"
            />
            <ElOption
              label="已确认"
              value="APPROVED"
            />
            <ElOption
              label="执行中"
              value="EXECUTING"
            />
            <ElOption
              label="复盘中"
              value="REVIEWING"
            />
            <ElOption
              label="已归档"
              value="ARCHIVED"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem v-if="hasFilter">
          <ElButton
            text
            @click="clearFilters"
          >
            清除筛选
          </ElButton>
        </ElFormItem>
      </ElForm>
    </template>

    <ElTable
      class="list-table"
      :data="items"
    >
      <ElTableColumn
        label="活动主题"
        :min-width="readUiLength('--ui-table-col-220')"
      >
        <template #default="{ row }">
          <div class="list-table__main">
            <span
              class="list-table__icon"
              aria-hidden="true"
            >
              <Tickets v-if="row.id % 2" />
              <Calendar v-else />
            </span>
            <div>
              <button
                type="button"
                class="list-table__link"
                @click="openDetail(row as ActivityItem)"
              >
                {{ (row as ActivityItem).name }}
              </button>
              <p class="list-table__secondary">
                {{ (row as ActivityItem).description || '暂无活动说明' }}
              </p>
            </div>
          </div>
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="状态"
        :min-width="readUiLength('--ui-table-col-100')"
      >
        <template #default="{ row }">
          <ElTag
            round
            effect="light"
            :type="statusTagType((row as ActivityItem).status)"
          >
            {{ statusLabel[(row as ActivityItem).status] ?? (row as ActivityItem).status }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="负责人"
        :min-width="readUiLength('--ui-table-col-130')"
      >
        <template #default="{ row }">
          {{ (row as ActivityItem).ownerUsername }}
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="活动时间"
        :min-width="readUiLength('--ui-table-col-120')"
      >
        <template #default="{ row }">
          <span class="list-table__figure">{{ dateOf((row as ActivityItem).startTime) }}</span>
          <p class="list-table__secondary">
            {{ timeOf((row as ActivityItem).startTime) }}
          </p>
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="报名范围"
        :min-width="readUiLength('--ui-table-col-100')"
      >
        <template #default="{ row }">
          {{ scopeLabel((row as ActivityItem).registrationScope) }}
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="预算（元）"
        :min-width="readUiLength('--ui-table-col-110')"
        align="right"
      >
        <template #default="{ row }">
          <span class="list-table__figure">{{ (row as ActivityItem).budget }}</span>
          <p
            v-if="(row as ActivityItem).status === 'DRAFT' || (row as ActivityItem).status === 'PENDING_APPROVAL'"
            class="list-table__secondary"
          >
            未计入概况
          </p>
        </template>
      </ElTableColumn>
    </ElTable>

    <template #footer>
      <ElPagination
        v-if="total > PAGE_SIZE"
        class="activities-pagination"
        layout="prev, pager, next"
        :total="total"
        :page-size="PAGE_SIZE"
        :current-page="page"
        @current-change="changePage"
      />
    </template>
  </ListPage>

  <ElDialog
    v-model="createVisible"
    title="创建活动草稿"
    class="activity-create-dialog"
    width="min(100%, var(--ui-drawer-width))"
    top="var(--ui-space-5)"
    :before-close="closeCreate"
    :close-on-click-modal="false"
    :show-close="!createSubmitting"
  >
    <p class="create-hint">
      先填写活动主题并指定负责人，其余安排可稍后补充。保存草稿不会开放报名。
    </p>
    <div
      v-if="createError"
      class="activities-error"
      role="alert"
    >
      <p>{{ createError }}</p>
      <p v-if="pendingCreate">
        {{ createUnverifiable ? '旧请求结果无法确认，请先核对活动列表，不要更换请求键再次创建。' : '为避免重复创建，当前内容暂不可修改；核对成功后可进入详情编辑。' }}
      </p>
    </div>
    <ElForm
      ref="createFormRef"
      :model="createForm"
      :rules="createRules"
      :disabled="createSubmitting || Boolean(pendingCreate)"
      label-position="top"
      scroll-to-error
      @submit.prevent="submitCreate"
    >
      <section
        class="create-section"
        aria-labelledby="create-basic-heading"
      >
        <h3 id="create-basic-heading">
          基本信息
        </h3>
        <div>
          <ElFormItem
            label="活动主题"
            prop="name"
          >
            <ElInput
              v-model="createForm.name"
              placeholder="例如：秋季团队交流活动"
              :maxlength="128"
            />
          </ElFormItem>
          <ElFormItem
            label="活动负责人"
            prop="ownerMemberId"
          >
            <ElSelect
              v-model="createForm.ownerMemberId"
              placeholder="选择本组织成员"
              :loading="candidatesLoading"
              :disabled="candidatesLoading || Boolean(candidatesError)"
              no-data-text="暂无可选负责人"
              filterable
            >
              <ElOption
                v-for="candidate in candidates"
                :key="candidate.memberId"
                :label="candidate.username"
                :value="candidate.memberId"
              />
            </ElSelect>
          </ElFormItem>
        </div>
        <div
          v-if="candidatesError"
          class="activities-error"
          role="alert"
        >
          <p>{{ candidatesError }}</p>
          <ElButton @click="loadCandidates">
            重新加载负责人
          </ElButton>
        </div>
        <ElFormItem
          label="活动简介"
          prop="description"
        >
          <ElInput
            v-model="createForm.description"
            type="textarea"
            :rows="3"
            :maxlength="2000"
            show-word-limit
            placeholder="选填，说明活动目标与主要内容"
          />
        </ElFormItem>
      </section>
      <section
        class="create-section"
        aria-labelledby="create-schedule-heading"
      >
        <h3 id="create-schedule-heading">
          时间与地点
        </h3>
        <p class="create-hint">
          选填，时间按北京时间（UTC+8）填写。
        </p>
        <div class="create-grid">
          <ElFormItem
            label="活动开始时间"
            prop="startTime"
          >
            <ElInput
              v-model="createForm.startTime"
              type="datetime-local"
            />
          </ElFormItem>
          <ElFormItem
            label="活动结束时间"
            prop="endTime"
          >
            <ElInput
              v-model="createForm.endTime"
              type="datetime-local"
            />
          </ElFormItem>
        </div>
        <ElFormItem
          label="活动地点"
          prop="location"
        >
          <ElInput
            v-model="createForm.location"
            :maxlength="255"
            placeholder="选填，可稍后确定"
          />
        </ElFormItem>
      </section>
      <section
        class="create-section"
        aria-labelledby="create-registration-heading"
      >
        <h3 id="create-registration-heading">
          预算与报名
        </h3>
        <div class="create-grid">
          <ElFormItem
            label="预算（元）"
            prop="budget"
          >
            <ElInput
              v-model="createForm.budget"
              inputmode="decimal"
              placeholder="0.00"
            />
          </ElFormItem>
          <ElFormItem
            label="名额"
            prop="capacity"
          >
            <ElInputNumber
              v-model="createForm.capacity"
              :min="1"
              :max="2147483647"
              placeholder="不填则不限名额"
            />
          </ElFormItem>
          <ElFormItem
            label="报名范围"
            prop="registrationScope"
          >
            <ElSelect v-model="createForm.registrationScope">
              <ElOption
                label="内部报名"
                value="INTERNAL"
              />
              <ElOption
                label="公开报名"
                value="PUBLIC"
              />
            </ElSelect>
          </ElFormItem>
        </div>
        <p class="create-hint">
          内部报名面向本组织成员；公开报名允许组织外的注册用户参与。活动确认后仍需单独开放报名。
        </p>
      </section>
    </ElForm>
    <template #footer>
      <ElButton
        :disabled="createSubmitting"
        @click="closeCreate"
      >
        取消
      </ElButton>
      <ElButton
        type="primary"
        :loading="createSubmitting"
        :disabled="candidatesLoading || Boolean(candidatesError) || createUnverifiable"
        @click="submitCreate"
      >
        {{ createUnverifiable ? '请先核对活动列表' : pendingCreate && !createSubmitting ? '核对保存结果' : '保存草稿' }}
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
/* 页头、面板与表格排版统一由 base.css 的公共类提供，此处只保留本页特有部分。 */
.activities-search {
  row-gap: var(--ui-space-3);
}

/* 关键词与搜索是高频动作，状态筛选靠右与表格右缘对齐（对照 Demo 工具栏）。 */
.activities-search :deep(.activities-status-item) {
  margin-left: auto;
}

.activities-status {
  width: var(--ui-filter-width);
}

.activities-pagination {
  margin-top: var(--ui-space-2);
}

.activities-error {
  margin-bottom: var(--ui-space-4);
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}
:global(.activity-create-dialog) {
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - var(--ui-space-5) * 2);
}
:global(.activity-create-dialog .el-dialog__body) {
  min-height: 0;
  overflow-y: auto;
}
:global(.activity-create-dialog .el-dialog__header),
:global(.activity-create-dialog .el-dialog__footer) {
  flex-shrink: 0;
}
.create-section + .create-section {
  margin-top: var(--ui-space-5);
  padding-top: var(--ui-space-5);
  border-top: var(--ui-border-width) solid var(--ui-color-border);
}
.create-section h3 {
  margin-bottom: var(--ui-space-4);
  font-size: var(--ui-font-size-body);
}
.create-grid {
  display: grid;
  grid-template-columns: var(--ui-layout-form-columns);
  column-gap: var(--ui-space-5);
}
.create-grid :deep(.el-form-item),
.create-grid :deep(.el-input-number) {
  min-width: 0;
  width: 100%;
}
.create-hint {
  margin-bottom: var(--ui-space-4);
  color: var(--ui-color-text-secondary);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-body);
}
.activities-saved {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--ui-space-3);
  padding: var(--ui-space-4);
  margin-bottom: var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-success-tint-9);
}
</style>
