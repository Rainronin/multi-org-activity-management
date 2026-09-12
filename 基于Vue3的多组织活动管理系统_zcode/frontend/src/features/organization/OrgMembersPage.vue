<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInputNumber,
  ElLoading,
  ElMessageBox,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import { useOrganizationsStore } from '../../stores/organizations'
import {
  createInvitation,
  fetchMembers,
  leaveOrganization,
  promoteOwner,
  removeMember,
  revokeOwner,
  transferOwnership,
} from './api'
import { roleLabel, type InvitationCreated, type MemberItem } from './types'

// 显式导入模式下 v-loading 指令需本地注册（未全局安装 Element Plus）。
const vLoading = ElLoading.directive

// 成员管理（P1.3）：OWNER 可邀请、授予/撤销负责人、移交与移除；所有成员可退出。
// 危险操作走 ElMessageBox 二次确认；后端为唯一授权与最后负责人保护来源。
const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const directoryStore = useOrganizationsStore()

const orgId = computed(() => String(route.params.orgId ?? ''))
const members = ref<MemberItem[]>([])
const loading = ref(false)
const errorText = ref('')
const actionError = ref('')
const actingMemberId = ref('')

const amOwner = computed(() => {
  const me = session.account?.username
  return members.value.some((m) => m.username === me && m.role === 'OWNER')
})

const inviteVisible = ref(false)
const inviteSubmitting = ref(false)
const inviteForm = ref({ expiresInDays: 7, maxUses: 10 })
const issuedInvitation = ref<InvitationCreated | null>(null)

async function loadMembers(): Promise<void> {
  if (!orgId.value) {
    return
  }
  loading.value = true
  errorText.value = ''
  try {
    members.value = await fetchMembers(orgId.value)
  } catch (error) {
    if (session.observeUnauthorized(error)) {
      return
    }
    errorText.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '成员列表加载失败，请重试。'
  } finally {
    loading.value = false
  }
}

onMounted(loadMembers)

async function guard(fn: () => Promise<void>): Promise<void> {
  actionError.value = ''
  try {
    await fn()
  } catch (error) {
    if (session.observeUnauthorized(error)) {
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
  void loadMembers()
}

function confirmPromote(member: MemberItem) {
  actingMemberId.value = member.memberId
  void ElMessageBox.confirm(
    `确定把「${member.username}」设为组织负责人？负责人可以管理成员、邀请与活动。`,
    '授予负责人',
    { confirmButtonText: '授予', cancelButtonText: '取消', type: 'warning' },
  )
    .then(() => guard(async () => {
      await promoteOwner(orgId.value, member.memberId)
      await loadMembers()
    }))
    .catch(() => undefined)
    .finally(() => {
      actingMemberId.value = ''
    })
}

function confirmRevoke(member: MemberItem) {
  actingMemberId.value = member.memberId
  void ElMessageBox.confirm(
    `确定撤销「${member.username}」的负责人身份？`,
    '撤销负责人',
    { confirmButtonText: '撤销', cancelButtonText: '取消', type: 'warning' },
  )
    .then(() => guard(async () => {
      await revokeOwner(orgId.value, member.memberId)
      await loadMembers()
    }))
    .catch(() => undefined)
    .finally(() => {
      actingMemberId.value = ''
    })
}

function confirmTransfer(member: MemberItem) {
  actingMemberId.value = member.memberId
  void ElMessageBox.confirm(
    `确定把负责人移交给「${member.username}」？你将降为普通成员。`,
    '移交负责人',
    { confirmButtonText: '移交', cancelButtonText: '取消', type: 'warning' },
  )
    .then(() => guard(async () => {
      await transferOwnership(orgId.value, member.memberId)
      await loadMembers()
    }))
    .catch(() => undefined)
    .finally(() => {
      actingMemberId.value = ''
    })
}

function confirmRemove(member: MemberItem) {
  actingMemberId.value = member.memberId
  void ElMessageBox.confirm(
    `确定把「${member.username}」移出组织？移除后其需要新的邀请才能再加入。`,
    '移除成员',
    { confirmButtonText: '移除', cancelButtonText: '取消', type: 'warning' },
  )
    .then(() => guard(async () => {
      await removeMember(orgId.value, member.memberId)
      await loadMembers()
    }))
    .catch(() => undefined)
    .finally(() => {
      actingMemberId.value = ''
    })
}

async function confirmLeave() {
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
    await leaveOrganization(orgId.value)
    await directoryStore.fetchDirectory(true)
    await router.replace('/')
  })
}
</script>

<template>
  <div>
    <div class="page-heading">
      <div>
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

    <ElTable
      v-loading="loading"
      :data="members"
      class="members-table"
    >
      <ElTableColumn
        prop="username"
        label="用户名"
        min-width="180"
      />
      <ElTableColumn
        label="角色"
        width="140"
      >
        <template #default="{ row }">
          <ElTag :type="row.role === 'OWNER' ? 'success' : 'info'">
            {{ roleLabel(row.role) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="加入时间"
        width="140"
      >
        <template #default="{ row }">
          {{ row.joinedAt.slice(0, 10) }}
        </template>
      </ElTableColumn>
      <ElTableColumn
        v-if="amOwner"
        label="操作"
        min-width="260"
      >
        <template #default="{ row }">
          <ElButton
            v-if="row.role === 'MEMBER'"
            size="small"
            :loading="actingMemberId === row.memberId"
            @click="confirmPromote(row as MemberItem)"
          >
            授予负责人
          </ElButton>
          <ElButton
            v-if="row.role === 'OWNER'"
            size="small"
            :loading="actingMemberId === row.memberId"
            @click="confirmRevoke(row as MemberItem)"
          >
            撤销负责人
          </ElButton>
          <ElButton
            v-if="row.role === 'MEMBER'"
            size="small"
            :loading="actingMemberId === row.memberId"
            @click="confirmTransfer(row as MemberItem)"
          >
            移交负责人
          </ElButton>
          <ElButton
            size="small"
            type="danger"
            plain
            :loading="actingMemberId === row.memberId"
            @click="confirmRemove(row as MemberItem)"
          >
            移除
          </ElButton>
        </template>
      </ElTableColumn>
    </ElTable>

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
.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-6);
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
  flex-shrink: 0;
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

.members-table {
  margin-top: var(--ui-space-8);
  width: 100%;
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
</style>
