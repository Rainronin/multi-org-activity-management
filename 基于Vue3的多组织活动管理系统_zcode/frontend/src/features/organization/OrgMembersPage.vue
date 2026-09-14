<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElLoading,
  ElMessageBox,
  ElPagination,
  ElSkeleton,

  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { useOrganizationsStore } from '../../stores/organizations'
import {
  createInvitation,
  fetchInvitations,
  fetchMembers,
  leaveOrganization,
  promoteOwner,
  removeMember,
  revokeInvitation,
  revokeOwner,
  transferOwnership,
} from './api'
import { roleLabel, type InvitationCreated, type InvitationItem, type MemberItem } from './types'

// 成员管理（P1.3 / 审核 R03、R06、R07）：
// 读取随路由 orgId 重建并核对请求序号与会话代次；失败清空旧数据；
// 确认框固定发起时的组织与目标；OWNER 可邀请、管理已发邀请、授予/撤销/移交与移除。
const vLoading = ElLoading.directive

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const directoryStore = useOrganizationsStore()

const emit = defineEmits<{
  changed: []
}>()

const orgId = computed(() => String(route.params.orgId ?? ''))

const members = ref<MemberItem[]>([])
const memberTotal = ref(0)
const memberPage = ref(1)
const memberQuery = ref('')
const appliedQuery = ref('')
const loading = ref(false)
const errorText = ref('')
const actionError = ref('')
const actingMemberId = ref('')

const invitations = ref<InvitationItem[]>([])
const inviteVisible = ref(false)
const inviteSubmitting = ref(false)
const inviteForm = ref({ expiresInDays: 7, maxUses: 10 })
const issuedInvitation = ref<InvitationCreated | null>(null)
const revokingInvitationId = ref('')

const amOwner = computed(() => {
  const me = session.account?.username
  return members.value.some((m) => m.username === me && m.role === 'OWNER')
})

// R03：读取随路由重建；请求序号 + 会话代次双守卫，迟到响应与旧组织数据不渲染。
let requestSeq = 0

async function loadMembers(): Promise<void> {
  if (!orgId.value) {
    return
  }
  const seq = ++requestSeq
  const targetOrgId = orgId.value
  const gen = session.capture()
  loading.value = true
  errorText.value = ''
  try {
    const page = await fetchMembers(targetOrgId, {
      page: memberPage.value,
      pageSize: 20,
      query: appliedQuery.value || undefined,
    })
    if (seq !== requestSeq || targetOrgId !== orgId.value || gen !== session.generation) {
      return
    }
    members.value = page.items
    memberTotal.value = page.total
  } catch (error) {
    if (seq !== requestSeq || targetOrgId !== orgId.value || gen !== session.generation) {
      return
    }
    // 失败清空旧组织数据，不残留
    members.value = []
    memberTotal.value = 0
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '成员列表加载失败，请重试。'
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

async function loadInvitations(): Promise<void> {
  if (!orgId.value || !amOwner.value) {
    return
  }
  const seq = requestSeq
  const gen = session.capture()
  try {
    const items = await fetchInvitations(orgId.value)
    if (seq === requestSeq && gen === session.generation) {
      invitations.value = items
    }
  } catch {
    // 邀请列表加载失败不影响成员列表
  }
}

function reloadAll(): void {
  void loadMembers()
  void loadInvitations()
}

watch(orgId, () => {
  memberPage.value = 1
  appliedQuery.value = ''
  memberQuery.value = ''
  members.value = []
  memberTotal.value = 0
  invitations.value = []
  errorText.value = ''
  actionError.value = ''
  reloadAll()
})

onMounted(reloadAll)

function search() {
  memberPage.value = 1
  appliedQuery.value = memberQuery.value.trim()
  void loadMembers()
}

function changePage(page: number) {
  memberPage.value = page
  void loadMembers()
}

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
      } else if (error.code === 'LAST_OWNER_PROTECTED') {
        actionError.value = '组织至少保留一名负责人，请先移交负责人身份。'
      } else if (error.code === 'MEMBER_STATE_CONFLICT') {
        actionError.value = '成员关系已被处理，请刷新后查看。'
      } else {
        actionError.value = error.detail || '操作失败，请稍后重试。'
      }
    } else {
      actionError.value = '网络连接失败，请检查网络后重试。'
    }
  }
}

async function submitInvite() {
  inviteSubmitting.value = true
  await guard(async () => {
    issuedInvitation.value = await createInvitation(orgId.value, {
      expiresInDays: inviteForm.value.expiresInDays,
      maxUses: inviteForm.value.maxUses,
    })
    await loadInvitations()
  })
  inviteSubmitting.value = false
}

async function copyInvitationCode() {
  if (!issuedInvitation.value) {
    return
  }
  await navigator.clipboard.writeText(issuedInvitation.value.code)
}

function closeInviteDialog() {
  issuedInvitation.value = null
  inviteVisible.value = false
}

function confirmRevokeInvitation(invitation: InvitationItem) {
  const targetOrgId = orgId.value
  void ElMessageBox.confirm('确定撤销该邀请？撤销后邀请码立即失效。', '撤销邀请', {
    confirmButtonText: '撤销',
    cancelButtonText: '取消',
    type: 'warning',
  })
    .then(() =>
      guard(async () => {
        if (orgId.value !== targetOrgId) {
          return
        }
        revokingInvitationId.value = invitation.invitationId
        await revokeInvitation(targetOrgId, invitation.invitationId)
        await loadInvitations()
      }),
    )
    .catch(() => undefined)
    .finally(() => {
      revokingInvitationId.value = ''
    })
}

// 危险操作在打开确认框时固定组织与目标成员；确认期间切页则放弃提交（R03）。
function roleAction(member: MemberItem, action: 'promote' | 'revoke' | 'transfer' | 'remove') {
  const targetOrgId = orgId.value
  const titles = {
    promote: '授予负责人',
    revoke: '撤销负责人',
    transfer: '移交负责人',
    remove: '移除成员',
  } as const
  const messages = {
    promote: `确定把「${member.username}」设为组织负责人？负责人可以管理成员、邀请与活动。`,
    revoke: `确定撤销「${member.username}」的负责人身份？`,
    transfer: `确定把负责人移交给「${member.username}」？你将降为普通成员。`,
    remove: `确定把「${member.username}」移出组织？移除后其需要新的邀请才能再加入。`,
  } as const
  actingMemberId.value = member.memberId
  void ElMessageBox.confirm(messages[action], titles[action], {
    confirmButtonText: titles[action],
    cancelButtonText: '取消',
    type: 'warning',
  })
    .then(() =>
      guard(async () => {
        // 确认期间组织已切换则放弃，避免旧目标与新组织上下文混用
        if (orgId.value !== targetOrgId) {
          actionError.value = '组织已切换，操作已取消。'
          return
        }
        if (action === 'promote') {
          await promoteOwner(targetOrgId, member.memberId)
        } else if (action === 'revoke') {
          await revokeOwner(targetOrgId, member.memberId)
        } else if (action === 'transfer') {
          await transferOwnership(targetOrgId, member.memberId)
        } else {
          await removeMember(targetOrgId, member.memberId)
        }
        // 角色变化后同步侧栏身份与组织目录（审核第 3 节）
        if (action !== 'remove') {
          emit('changed')
        }
        await loadMembers()
      }),
    )
    .catch(() => undefined)
    .finally(() => {
      actingMemberId.value = ''
    })
}

async function confirmLeave() {
  const targetOrgId = orgId.value
  try {
    await ElMessageBox.confirm(
      '确定退出该组织？退出后立即失去组织内部数据访问，需要新的邀请才能再加入。',
      '退出组织',
      { confirmButtonText: '退出', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  await guard(async () => {
    if (orgId.value !== targetOrgId) {
      return
    }
    await leaveOrganization(targetOrgId)
    await directoryStore.fetchDirectory(true)
    await router.replace('/')
  })
}
</script>

<template>
  <div class="members-page">
    <div class="page-heading">
      <div class="page-heading__title">
        <p class="page-eyebrow">
          组织协作 / 成员管理
        </p>
        <h1>成员管理</h1>
        <p class="page-description">
          邀请只授予普通成员身份；负责人授予、移交与移除会记录操作历史。
        </p>
      </div>
      <div class="members-actions">
        <ElButton
          v-if="amOwner"
          type="primary"
          @click="inviteVisible = true"
        >
          创建邀请
        </ElButton>
        <ElButton
          type="danger"
          plain
          @click="confirmLeave"
        >
          退出组织
        </ElButton>
      </div>
    </div>

    <p
      v-if="errorText"
      class="members-error"
      role="alert"
    >
      {{ errorText }}
    </p>
    <p
      v-if="actionError"
      class="members-error"
      role="alert"
    >
      {{ actionError }}
    </p>

    <ElForm
      class="members-search"
      :inline="true"
      @submit.prevent="search"
    >
      <ElFormItem>
        <ElInput
          v-model="memberQuery"
          placeholder="按用户名搜索"
          clearable
          @clear="search"
        />
      </ElFormItem>
      <ElFormItem>
        <ElButton native-type="submit">
          搜索
        </ElButton>
      </ElFormItem>
    </ElForm>

    <div
      v-loading="loading"
      class="members-table"
    >
      <ElTable :data="members">
        <ElTableColumn
          prop="username"
          label="用户名"
          min-width="160"
        />
        <ElTableColumn
          label="角色"
          min-width="110"
        >
          <template #default="{ row }">
            <ElTag :type="row.role === 'OWNER' ? 'success' : 'info'">
              {{ roleLabel((row as MemberItem).role) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="加入时间"
          min-width="110"
        >
          <template #default="{ row }">
            {{ (row as MemberItem).joinedAt.slice(0, 10) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          v-if="amOwner"
          label="操作"
          min-width="240"
        >
          <template #default="{ row }">
            <template v-if="row.role === 'MEMBER'">
              <ElButton
                size="small"
                @click="roleAction(row as MemberItem, 'promote')"
              >
                授予负责人
              </ElButton>
              <ElButton
                size="small"
                @click="roleAction(row as MemberItem, 'transfer')"
              >
                移交负责人
              </ElButton>
            </template>
            <ElButton
              v-else
              size="small"
              @click="roleAction(row as MemberItem, 'revoke')"
            >
              撤销负责人
            </ElButton>
            <ElButton
              size="small"
              type="danger"
              plain
              @click="roleAction(row as MemberItem, 'remove')"
            >
              移除
            </ElButton>
          </template>
        </ElTableColumn>
        <template #empty>
          <ElSkeleton
            v-if="loading"
            :rows="5"
            animated
            data-table-skeleton
          />
          <span class="members-note">{{ appliedQuery ? '没有匹配的成员。' : '暂无其他成员，可通过邀请添加。' }}</span>
        </template>
      </ElTable>
    </div>

    <ElPagination
      v-if="memberTotal > 20"
      class="members-pagination"
      layout="prev, pager, next"
      :total="memberTotal"
      :page-size="20"
      :current-page="memberPage"
      @current-change="changePage"
    />

    <template v-if="amOwner">
      <h2 class="invitations-title">
        已发邀请
      </h2>
      <div
        v-loading="loading"
        class="members-table"
      >
        <ElTable :data="invitations">
          <ElTableColumn
            label="创建时间"
            min-width="110"
          >
            <template #default="{ row }">
              {{ (row as InvitationItem).createdAt.slice(0, 10) }}
            </template>
          </ElTableColumn>
          <ElTableColumn
            label="有效期至"
            min-width="110"
          >
            <template #default="{ row }">
              {{ (row as InvitationItem).expiresAt.slice(0, 10) }}
            </template>
          </ElTableColumn>
          <ElTableColumn
            label="使用情况"
            min-width="100"
          >
            <template #default="{ row }">
              {{ (row as InvitationItem).usedCount }} / {{ (row as InvitationItem).maxUses }}
            </template>
          </ElTableColumn>
          <ElTableColumn
            label="操作"
            min-width="100"
          >
            <template #default="{ row }">
              <ElButton
                size="small"
                type="danger"
                plain
                :loading="revokingInvitationId === (row as InvitationItem).invitationId"
                @click="confirmRevokeInvitation(row as InvitationItem)"
              >
                撤销
              </ElButton>
            </template>
          </ElTableColumn>
          <template #empty>
            <span class="members-note">暂无有效邀请。</span>
          </template>
        </ElTable>
      </div>
    </template>

    <ElDialog
      v-model="inviteVisible"
      title="创建邀请"
      width="var(--ui-dialog-width)"
      @closed="closeInviteDialog"
    >
      <template v-if="!issuedInvitation">
        <ElForm
          :model="inviteForm"
          label-position="top"
        >
          <ElFormItem label="有效期（天）">
            <ElInputNumber
              v-model="inviteForm.expiresInDays"
              :min="1"
              :max="30"
            />
          </ElFormItem>
          <ElFormItem label="最大使用次数">
            <ElInputNumber
              v-model="inviteForm.maxUses"
              :min="1"
              :max="100"
            />
          </ElFormItem>
        </ElForm>
        <p class="members-note">
          接受邀请的账号将以普通成员身份加入组织。
        </p>
      </template>
      <template v-else>
        <p
          class="members-note"
          role="alert"
        >
          邀请码仅此一次展示，关闭后不可再次查看，请立即复制并分享给被邀请人。
        </p>
        <p class="members-code">
          {{ issuedInvitation.code }}
        </p>
        <p class="members-note">
          有效期至 {{ issuedInvitation.expiresAt.slice(0, 10) }} · 最多使用 {{ issuedInvitation.maxUses }} 次
        </p>
      </template>
      <template #footer>
        <template v-if="!issuedInvitation">
          <ElButton @click="closeInviteDialog">
            取消
          </ElButton>
          <ElButton
            type="primary"
            :loading="inviteSubmitting"
            @click="submitInvite"
          >
            生成邀请码
          </ElButton>
        </template>
        <template v-else>
          <ElButton @click="copyInvitationCode">
            复制邀请码
          </ElButton>
          <ElButton
            type="primary"
            @click="closeInviteDialog"
          >
            我已保存
          </ElButton>
        </template>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.members-page {
  min-width: 0;
}

/* 小屏重排仅用结构换行（wrap），断点仍由 tokens 集中管理。 */
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

.members-actions {
  display: flex;
  gap: var(--ui-space-3);
  flex-wrap: wrap;
}

.members-error {
  margin-top: var(--ui-space-4);
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.members-search {
  margin-top: var(--ui-space-6);
}

.members-table {
  margin-top: var(--ui-space-6);
  width: 100%;
}

.members-pagination {
  margin-top: var(--ui-space-4);
}

.members-note {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-prose);
}

.members-code {
  margin: var(--ui-space-4) 0;
  padding: var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-surface-muted);
  font-family: var(--ui-font-family-data);
  font-size: var(--ui-font-size-section);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
  text-align: center;
  overflow-wrap: anywhere;
}

.invitations-title {
  margin-top: var(--ui-space-10);
  font-size: var(--ui-font-size-section);
  font-weight: var(--ui-font-weight-semibold);
}
</style>
