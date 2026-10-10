<script setup lang="ts">
import { readUiLength } from '../../shared/utils/readUiToken'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElMessageBox,
  ElPagination,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import ListPage from '../../shared/components/ListPage.vue'
import { ApiError, readErrorMessage } from '../../shared/http/error'
import { newIdempotencyKey } from '../../shared/http/idempotency'
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
import { roleLabel, type InvitationCreated, type InvitationItem, type MemberItem, type OrganizationDetail } from './types'

// 成员管理（P1.3 / 审核 R03、R06、R07）：
// 读取随路由 orgId 重建并核对请求序号与会话代次；失败清空旧数据；
// 确认框固定发起时的组织与目标；OWNER 可邀请、管理已发邀请、授予/撤销/移交与移除。
const PAGE_SIZE = 20

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const directoryStore = useOrganizationsStore()

const emit = defineEmits<{
  changed: []
}>()

// 管理入口按当前组织身份判断，不由“当前分页／搜索结果里的成员行”推断：
// 换搜索词或翻到没有自己的那一页时，负责人身份不会因此消失（审核反馈 2）。
const props = defineProps<{
  orgDetail: OrganizationDetail | null
  state: 'loading' | 'ready' | 'missing'
}>()

const orgId = computed(() => String(route.params.orgId ?? ''))
let alive = true
let actionGeneration = 0

const members = ref<MemberItem[]>([])
const memberTotal = ref(0)
const memberPage = ref(1)
const memberQuery = ref('')
const appliedQuery = ref('')
const loading = ref(false)
const refreshing = ref(false)
const loadedOnce = ref(false)
const errorText = ref('')
const actionError = ref('')
const actingMemberId = ref('')

const invitations = ref<InvitationItem[]>([])
const invitationsLoading = ref(false)
const invitationsLoaded = ref(false)
const invitationsError = ref('')
const inviteVisible = ref(false)
const inviteSubmitting = ref(false)
const inviteForm = ref({ expiresInDays: 7, maxUses: 10 })
const pendingInvite = ref<{ orgId: string; input: { expiresInDays: number; maxUses: number }; key: string } | null>(null)
const issuedInvitation = ref<InvitationCreated | null>(null)
const revokingInvitationId = ref('')
const inviteDirty = computed(() => inviteVisible.value && !issuedInvitation.value
  && (inviteForm.value.expiresInDays !== 7 || inviteForm.value.maxUses !== 10))

function resetInvite(): void {
  inviteForm.value = { expiresInDays: 7, maxUses: 10 }
  issuedInvitation.value = null
  pendingInvite.value = null
  actionError.value = ''
}

async function confirmInviteLeave(): Promise<boolean> {
  if (inviteSubmitting.value) return false
  if (!pendingInvite.value && !inviteDirty.value) return true
  try {
    await ElMessageBox.confirm(pendingInvite.value
      ? '邀请创建结果尚未确认。离开后请先核对邀请列表，避免重复发放。确定放弃核对？'
      : '邀请设置尚未保存，确定放弃填写？', '未保存的修改', {
      confirmButtonText: '放弃', cancelButtonText: '继续填写', type: 'warning',
    })
    return true
  } catch { return false }
}

onBeforeRouteLeave(confirmInviteLeave)
onBeforeRouteUpdate((to, from) => to.path === from.path ? true : confirmInviteLeave())
function beforeUnload(event: BeforeUnloadEvent): void {
  if (inviteSubmitting.value || pendingInvite.value || inviteDirty.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => {
  alive = false
  requestSeq++
  window.removeEventListener('beforeunload', beforeUnload)
})

const canManage = computed(() => props.state === 'ready' && props.orgDetail?.role === 'OWNER')

const hasFilter = computed(() => Boolean(appliedQuery.value))

const emptyText = computed(() => (hasFilter.value
  ? '没有匹配的成员，可清除关键词后重试。'
  : '暂无其他成员，可通过邀请添加。'))

// R03：读取随路由重建；请求序号 + 会话代次双守卫，迟到响应与旧组织数据不渲染。
let requestSeq = 0

async function loadMembers(): Promise<void> {
  if (!orgId.value) {
    return
  }
  const seq = ++requestSeq
  const targetOrgId = orgId.value
  const gen = session.capture()
  // 首次加载与后台刷新分开：有旧数据时保留内容并就地提示（UI 规范第 10 节）。
  if (members.value.length > 0) {
    refreshing.value = true
  } else {
    loading.value = true
  }
  errorText.value = ''
  try {
    const page = await fetchMembers(targetOrgId, {
      page: memberPage.value,
      pageSize: PAGE_SIZE,
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
    errorText.value = readErrorMessage(error, '成员列表加载失败，请重试。')
  } finally {
    if (seq === requestSeq) {
      loading.value = false
      refreshing.value = false
      loadedOnce.value = true
    }
  }
}

async function loadInvitations(): Promise<void> {
  if (!orgId.value || !canManage.value) {
    return
  }
  const seq = requestSeq
  const gen = session.capture()
  invitationsLoading.value = true
  invitationsError.value = ''
  try {
    const items = await fetchInvitations(orgId.value)
    if (seq === requestSeq && gen === session.generation) {
      invitations.value = items
    }
  } catch (error) {
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    // 邀请区段有自己的失败态：清空旧结果并给出重试，不能退化成“暂无有效邀请”（审核反馈 3）。
    invitations.value = []
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    invitationsError.value = readErrorMessage(error, '邀请列表加载失败，请重试。')
  } finally {
    // 成员已换组织或更新完成时不再改动新列表的状态
    if (seq === requestSeq && gen === session.generation) {
      invitationsLoading.value = false
      invitationsLoaded.value = true
    }
  }
}

/** 成员列表加载完成后才按当前身份拉取邀请，避免用上一次的角色判断。 */
async function reloadAll(): Promise<void> {
  await loadMembers()
  if (canManage.value) {
    await loadInvitations()
  }
}

watch(orgId, () => {
  actionGeneration++
  resetInvite()
  inviteVisible.value = false
  memberPage.value = 1
  appliedQuery.value = ''
  memberQuery.value = ''
  members.value = []
  memberTotal.value = 0
  invitations.value = []
  invitationsLoaded.value = false
  invitationsError.value = ''
  errorText.value = ''
  actionError.value = ''
  void reloadAll()
})

onMounted(reloadAll)

function search() {
  memberPage.value = 1
  appliedQuery.value = memberQuery.value.trim()
  void loadMembers()
}

function clearFilters() {
  memberQuery.value = ''
  appliedQuery.value = ''
  memberPage.value = 1
  void loadMembers()
}

function changePage(page: number) {
  memberPage.value = page
  void loadMembers()
}

function captureAction() {
  return { orgId: orgId.value, generation: session.capture(), scope: actionGeneration }
}

function isActionCurrent(context: ReturnType<typeof captureAction>): boolean {
  return alive && context.orgId === orgId.value && context.generation === session.generation
    && context.scope === actionGeneration
}

async function guard(fn: () => Promise<void>, context = captureAction()): Promise<unknown> {
  if (!isActionCurrent(context)) return
  actionError.value = ''
  try {
    await fn()
  } catch (error) {
    if (session.observeUnauthorized(error, context.generation)) {
      return error
    }
    if (!isActionCurrent(context)) return error
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
    return error
  }
}

async function submitInvite() {
  if (inviteSubmitting.value || !canManage.value) return
  inviteSubmitting.value = true
  if (!pendingInvite.value) pendingInvite.value = {
    orgId: orgId.value, input: { ...inviteForm.value }, key: newIdempotencyKey(),
  }
  const intent = pendingInvite.value
  const context = captureAction()
  const failure = await guard(async () => {
    const result = await createInvitation(intent.orgId, intent.input, intent.key)
    if (!isActionCurrent(context)) return
    issuedInvitation.value = result
    pendingInvite.value = null
    await loadInvitations()
  }, context)
  if (failure instanceof ApiError && !failure.isTransportError
    && [400, 403, 404, 422, 429].includes(failure.status)) pendingInvite.value = null
  inviteSubmitting.value = false
}

async function copyInvitationCode() {
  if (!issuedInvitation.value?.code) {
    return
  }
  await navigator.clipboard.writeText(issuedInvitation.value.code)
  ElMessage.success('邀请码已复制到剪贴板。')
}

async function closeInviteDialog(done?: () => void) {
  if (!(await confirmInviteLeave())) return
  resetInvite()
  if (done) done()
  else inviteVisible.value = false
}

function confirmRevokeInvitation(invitation: Pick<InvitationItem, 'invitationId'>) {
  const context = captureAction()
  void ElMessageBox.confirm('确定撤销该邀请？撤销后邀请码立即失效。', '撤销邀请', {
    confirmButtonText: '撤销',
    cancelButtonText: '取消',
    type: 'warning',
  })
    .then(() =>
      guard(async () => {
        revokingInvitationId.value = invitation.invitationId
        await revokeInvitation(context.orgId, invitation.invitationId)
        if (!isActionCurrent(context)) return
        if (issuedInvitation.value?.invitationId === invitation.invitationId) issuedInvitation.value = null
        await loadInvitations()
      }, context),
    )
    .catch(() => undefined)
    .finally(() => {
      revokingInvitationId.value = ''
    })
}

// 危险操作在打开确认框时固定组织与目标成员；确认期间切页则放弃提交（R03）。
function roleAction(member: MemberItem, action: 'promote' | 'revoke' | 'transfer' | 'remove') {
  const context = captureAction()
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
        if (action === 'promote') {
          await promoteOwner(context.orgId, member.memberId)
        } else if (action === 'revoke') {
          await revokeOwner(context.orgId, member.memberId)
        } else if (action === 'transfer') {
          await transferOwnership(context.orgId, member.memberId)
        } else {
          await removeMember(context.orgId, member.memberId)
        }
        if (!isActionCurrent(context)) return
        // 角色变化后同步侧栏身份与组织目录（审核第 3 节）
        if (action !== 'remove') {
          emit('changed')
        }
        await loadMembers()
      }, context),
    )
    .catch(() => undefined)
    .finally(() => {
      actingMemberId.value = ''
    })
}

async function confirmLeave() {
  const context = captureAction()
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
    await leaveOrganization(context.orgId)
    if (!isActionCurrent(context)) return
    await directoryStore.fetchDirectory(true)
    if (!isActionCurrent(context)) return
    await router.replace('/')
  }, context)
}
</script>

<template>
  <ListPage
    title="成员管理"
    panel-title="成员列表"
    eyebrow="组织协作 / 成员管理"
    description="邀请只授予普通成员身份；负责人授予、移交与移除会记录操作历史。"
    :context="`共 ${memberTotal} 名成员`"
    :error="errorText"
    :loading="loading"
    :refreshing="refreshing"
    :loaded="loadedOnce"
    :row-count="members.length"
    :total="0"
    :empty-text="emptyText"
    :skeleton-columns="4"
    retry-text="重新加载"
    @retry="loadMembers"
  >
    <template #actions>
      <ElButton
        v-if="canManage"
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
    </template>

    <template #filters>
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

    <p
      v-if="actionError && !inviteVisible"
      class="members-error"
      role="alert"
    >
      {{ actionError }}
    </p>

    <ElTable
      class="list-table"
      :data="members"
    >
      <ElTableColumn
        prop="username"
        label="用户名"
        :min-width="readUiLength('--ui-table-col-160')"
      />
      <ElTableColumn
        label="角色"
        :min-width="readUiLength('--ui-table-col-110')"
      >
        <template #default="{ row }">
          <ElTag :type="row.role === 'OWNER' ? 'success' : 'info'">
            {{ roleLabel((row as MemberItem).role) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="加入时间"
        :min-width="readUiLength('--ui-table-col-110')"
      >
        <template #default="{ row }">
          <span class="list-table__figure">{{ (row as MemberItem).joinedAt.slice(0, 10) }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn
        v-if="canManage"
        label="操作"
        :min-width="readUiLength('--ui-table-col-240')"
      >
        <template #default="{ row }">
          <template v-if="(row as MemberItem).role === 'MEMBER'">
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
    </ElTable>

    <template #footer>
      <ElPagination
        v-if="memberTotal > PAGE_SIZE"
        class="members-pagination"
        layout="prev, pager, next"
        :total="memberTotal"
        :page-size="PAGE_SIZE"
        :current-page="memberPage"
        @current-change="changePage"
      />
    </template>

    <!-- 已发邀请是同一页面的独立区段：自己的加载／失败／空状态，不与成员表互斥 -->
    <section
      v-if="canManage"
      class="list-panel"
      data-invitations-panel
    >
      <div class="list-panel__head">
        <h2>
          已发邀请
          <span
            v-if="!invitationsError"
            class="list-panel__count"
          >{{ invitations.length }}</span>
        </h2>
        <span class="list-panel__aside">仅负责人可管理</span>
      </div>
      <p
        v-if="invitationsError"
        class="invitations-error"
        role="alert"
      >
        {{ invitationsError }}
        <ElButton
          size="small"
          @click="loadInvitations"
        >
          重新加载
        </ElButton>
      </p>
      <p
        v-else-if="invitationsLoading"
        class="list-panel__note"
        role="status"
      >
        正在加载邀请…
      </p>
      <ElTable
        v-else
        class="list-table"
        :data="invitations"
      >
        <ElTableColumn
          label="创建时间"
          :min-width="readUiLength('--ui-table-col-110')"
        >
          <template #default="{ row }">
            <span class="list-table__figure">{{ (row as InvitationItem).createdAt.slice(0, 10) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="有效期至"
          :min-width="readUiLength('--ui-table-col-110')"
        >
          <template #default="{ row }">
            <span class="list-table__figure">{{ (row as InvitationItem).expiresAt.slice(0, 10) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="使用情况"
          :min-width="readUiLength('--ui-table-col-100')"
        >
          <template #default="{ row }">
            {{ (row as InvitationItem).usedCount }} / {{ (row as InvitationItem).maxUses }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          :min-width="readUiLength('--ui-table-col-100')"
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
          <p
            v-if="invitationsLoaded"
            class="list-panel__note"
          >
            暂无有效邀请；创建邀请后可在此撤销。
          </p>
        </template>
      </ElTable>
    </section>
  </ListPage>

  <ElDialog
    v-model="inviteVisible"
    title="创建邀请"
    width="min(var(--ui-dialog-width), calc(100vw - var(--ui-space-8)))"
    :before-close="closeInviteDialog"
  >
    <p
      v-if="actionError"
      class="members-error"
      role="alert"
    >
      {{ actionError }}
    </p>
    <template v-if="!issuedInvitation">
      <ElForm
        :model="inviteForm"
        :disabled="inviteSubmitting || Boolean(pendingInvite)"
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
      <p class="list-panel__note">
        {{ pendingInvite ? '创建结果尚未确认，请按原设置核对，避免重复发放邀请。' : '接受邀请的账号将以普通成员身份加入组织。' }}
      </p>
    </template>
    <template v-else>
      <p
        class="list-panel__note"
        role="alert"
      >
        {{ issuedInvitation.code ? '邀请码仅此一次展示，关闭后不可再次查看，请立即复制并分享给被邀请人。' : '邀请码未收到，无法再次显示。请撤销此邀请后重新生成。' }}
      </p>
      <p
        v-if="issuedInvitation.code"
        class="members-code"
      >
        {{ issuedInvitation.code }}
      </p>
      <p class="list-panel__note">
        有效期至 {{ issuedInvitation.expiresAt.slice(0, 10) }} · 最多使用 {{ issuedInvitation.maxUses }} 次
      </p>
    </template>
    <template #footer>
      <template v-if="!issuedInvitation">
        <ElButton @click="closeInviteDialog()">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="inviteSubmitting"
          @click="submitInvite"
        >
          {{ pendingInvite && !inviteSubmitting ? '核对创建结果' : '生成邀请码' }}
        </ElButton>
      </template>
      <template v-else>
        <ElButton
          v-if="issuedInvitation.code"
          @click="copyInvitationCode"
        >
          复制邀请码
        </ElButton>
        <ElButton
          v-else
          type="danger"
          plain
          :loading="Boolean(revokingInvitationId)"
          @click="confirmRevokeInvitation(issuedInvitation)"
        >
          撤销此邀请
        </ElButton>
        <ElButton
          type="primary"
          @click="closeInviteDialog()"
        >
          {{ issuedInvitation.code ? '我已保存' : '关闭' }}
        </ElButton>
      </template>
    </template>
  </ElDialog>
</template>

<style scoped>
/* 页头、面板与表格排版统一由 base.css 的公共类提供。 */
.members-search {
  row-gap: var(--ui-space-3);
}

.members-invitations {
  margin-top: var(--ui-layout-grid-gap);
}

.members-error {
  margin: 0 0 var(--ui-space-4);
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

/* 邀请区段的失败态与成员表错误同形，避免两个区段的错误样式各写一套。 */
.invitations-error {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ui-space-3);
  margin: 0;
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.members-pagination {
  margin-top: var(--ui-space-2);
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
