<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElLoading,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { createActivity, fetchActivities, fetchOwnerCandidates } from './api'
import type { MemberItem } from '../organization/types'
import { formatDateTime, scopeLabel, type ActivityItem } from './types'

// 活动管理（P2.1）：OWNER 创建草稿并指定负责人；读取随路由与代次守卫重建（同成员页模式）。
const vLoading = ElLoading.directive

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
const errorText = ref('')
const amOwner = ref(false)

const createVisible = ref(false)
const createSubmitting = ref(false)
const createError = ref('')
const candidates = ref<MemberItem[]>([])
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

// R03 模式：请求序号 + 会话代次双守卫。
let requestSeq = 0

async function loadActivities(): Promise<void> {
  if (!orgId.value) {
    return
  }
  const seq = ++requestSeq
  const targetOrgId = orgId.value
  const gen = session.capture()
  loading.value = true
  errorText.value = ''
  try {
    const result = await fetchActivities(targetOrgId, {
      page: page.value,
      query: appliedQuery.value || undefined,
      status: statusFilter.value || undefined,
    })
    if (seq !== requestSeq || targetOrgId !== orgId.value || gen !== session.generation) {
      return
    }
    items.value = result.items
    total.value = result.total
    amOwner.value = result.total > 0
      ? true
      : await hasOwnerRole(targetOrgId, gen)
  } catch (error) {
    if (seq !== requestSeq || targetOrgId !== orgId.value || gen !== session.generation) {
      return
    }
    items.value = []
    total.value = 0
    amOwner.value = false
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '活动列表加载失败，请重试。'
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

// 空列表时无法从结果判断角色；单独查询本人是否为组织负责人（目录摘要接口，轻量）。
async function hasOwnerRole(targetOrgId: string, gen: number): Promise<boolean> {
  try {
    const me = await fetchActivitiesSelfRole(targetOrgId)
    return gen === session.generation && me === 'OWNER'
  } catch {
    return false
  }
}

async function fetchActivitiesSelfRole(targetOrgId: string): Promise<string> {
  const { request } = await import('../../shared/http/client')
  const response = await request<{ role: string }>(
    `/api/organizations/${encodeURIComponent(targetOrgId)}`,
  )
  return response.data.role
}

function openCreate() {
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
  void loadCandidates()
  createVisible.value = true
}

async function loadCandidates() {
  try {
    candidates.value = await fetchOwnerCandidates(orgId.value)
  } catch {
    candidates.value = []
  }
}

function search() {
  page.value = 1
  appliedQuery.value = query.value.trim()
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
  page.value = 1
  appliedQuery.value = ''
  query.value = ''
  items.value = []
  total.value = 0
  amOwner.value = false
  statusFilter.value = ''
  void loadActivities()
})

onMounted(() => {
  void loadActivities()
})

async function submitCreate() {
  createSubmitting.value = true
  createError.value = ''
  try {
    await createActivity(orgId.value, {
      name: createForm.name.trim(),
      ownerMemberId: createForm.ownerMemberId,
      description: createForm.description.trim() || undefined,
      startTime: createForm.startTime || undefined,
      endTime: createForm.endTime || undefined,
      location: createForm.location.trim() || undefined,
      budget: createForm.budget || undefined,
      registrationScope: createForm.registrationScope,
      capacity: createForm.capacity ?? undefined,
      registrationStart: createForm.registrationStart || undefined,
      registrationEnd: createForm.registrationEnd || undefined,
    })
    createVisible.value = false
    await loadActivities()
  } catch (error) {
    if (session.observeUnauthorized(error, session.capture())) {
      return
    }
    if (error instanceof ApiError && !error.isTransportError) {
      if (error.code === 'CSRF_INVALID') {
        invalidateCsrfCredential()
        createError.value = '安全校验已过期，请重新提交。'
      } else {
        const fieldError = error.fieldErrors[0]?.message
        createError.value = fieldError || error.detail || '创建失败，请检查输入后重试。'
      }
    } else {
      createError.value = '网络连接失败，请检查网络后重试。'
    }
  } finally {
    createSubmitting.value = false
  }
}
</script>

<template>
  <div class="activities-page">
    <div class="page-heading">
      <div class="page-heading__title">
        <p class="page-eyebrow">
          组织协作 / 活动管理
        </p>
        <h1>活动管理</h1>
        <p class="page-description">
          草稿仅组织负责人与活动负责人可见；提交确认流程随下一阶段开放。
        </p>
      </div>
      <div class="activities-actions">
        <ElButton
          v-if="amOwner"
          type="primary"
          @click="openCreate"
        >
          创建活动
        </ElButton>
      </div>
    </div>

    <p
      v-if="errorText"
      class="activities-error"
      role="alert"
    >
      {{ errorText }}
    </p>

    <ElForm
      class="activities-search"
      :inline="true"
      @submit.prevent="search"
    >
      <ElFormItem>
        <ElInput
          v-model="query"
          placeholder="按活动主题搜索"
          clearable
          @clear="search"
        />
      </ElFormItem>
      <ElFormItem>
        <ElButton native-type="submit">
          搜索
        </ElButton>
      </ElFormItem>
      <ElFormItem>
        <ElSelect
          v-model="statusFilter"
          class="activities-status"
          @change="search"
        >
          <ElOption
            label="全部状态"
            value=""
          />
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
        </ElSelect>
      </ElFormItem>
    </ElForm>

    <div
      v-loading="loading"
      class="activities-table"
    >
      <ElTable :data="items">
        <ElTableColumn
          label="活动主题"
          min-width="200"
        >
          <template #default="{ row }">
            <ElLink
              type="primary"
              @click="openDetail(row as ActivityItem)"
            >
              {{ (row as ActivityItem).name }}
            </ElLink>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="状态"
          min-width="100"
        >
          <template #default="{ row }">
            <ElTag type="info">
              {{ row.status === 'DRAFT' ? '草稿' : row.status }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="活动负责人"
          min-width="130"
        >
          <template #default="{ row }">
            {{ row.ownerUsername }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="活动时间"
          min-width="170"
        >
          <template #default="{ row }">
            {{ formatDateTime(row.startTime) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="报名范围"
          min-width="100"
        >
          <template #default="{ row }">
            {{ scopeLabel(row.registrationScope) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="预算（元）"
          min-width="110"
          align="right"
        >
          <template #default="{ row }">
            <span class="activities-amount">{{ row.budget }}</span>
          </template>
        </ElTableColumn>
        <template #empty>
          <span class="activities-note">
            {{ amOwner ? '尚未创建活动。' : '暂无可查看的活动；活动经组织负责人确认发布后会在这里展示。' }}
          </span>
        </template>
      </ElTable>
    </div>

    <ElPagination
      v-if="total > 20"
      class="activities-pagination"
      layout="prev, pager, next"
      :total="total"
      :page-size="20"
      :current-page="page"
      @current-change="changePage"
    />

    <ElDialog
      v-model="createVisible"
      title="创建活动草稿"
      width="min(100%, var(--ui-drawer-width))"
      top="6vh"
    >
      <p
        v-if="createError"
        class="activities-error"
        role="alert"
      >
        {{ createError }}
      </p>
      <ElForm
        :model="createForm"
        label-position="top"
      >
        <ElFormItem
          label="活动主题"
          required
        >
          <ElInput
            v-model="createForm.name"
            placeholder="1-128 字符"
          />
        </ElFormItem>
        <ElFormItem
          label="活动负责人"
          required
        >
          <ElSelect
            v-model="createForm.ownerMemberId"
            placeholder="选择本组织有效成员"
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
        <ElFormItem label="活动简介">
          <ElInput
            v-model="createForm.description"
            type="textarea"
            :rows="3"
            placeholder="选填，2000 字符以内"
          />
        </ElFormItem>
        <ElFormItem label="活动开始时间">
          <ElInput
            v-model="createForm.startTime"
            type="datetime-local"
          />
        </ElFormItem>
        <ElFormItem label="活动结束时间">
          <ElInput
            v-model="createForm.endTime"
            type="datetime-local"
          />
        </ElFormItem>
        <ElFormItem label="活动地点">
          <ElInput
            v-model="createForm.location"
            placeholder="选填"
          />
        </ElFormItem>
        <ElFormItem label="预算（元）">
          <ElInput
            v-model="createForm.budget"
            placeholder="0.00"
          />
        </ElFormItem>
        <ElFormItem label="报名范围">
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
        <ElFormItem label="名额">
          <ElInputNumber
            v-model="createForm.capacity"
            :min="1"
            placeholder="选填"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="createVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="createSubmitting"
          @click="submitCreate"
        >
          保存草稿
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.activities-page {
  min-width: 0;
}

.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-6);
  flex-wrap: wrap;
}

.page-heading__title {
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

.activities-actions {
  display: flex;
  gap: var(--ui-space-3);
  flex-wrap: wrap;
}

.activities-error {
  margin-top: var(--ui-space-4);
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.activities-search {
  margin-top: var(--ui-space-6);
}

.activities-table {
  margin-top: var(--ui-space-6);
  width: 100%;
}

.activities-amount {
  font-variant-numeric: tabular-nums;
}

.activities-pagination {
  margin-top: var(--ui-space-4);
}

.activities-note {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}
</style>
