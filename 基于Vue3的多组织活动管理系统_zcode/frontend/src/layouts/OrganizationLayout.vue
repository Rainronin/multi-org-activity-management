<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, ArrowRight, CircleCheck, FolderOpened, HomeFilled, Menu, Setting, User } from '@element-plus/icons-vue'
import { ElAlert, ElButton, ElDropdown, ElDropdownItem, ElDropdownMenu, ElMessage } from 'element-plus'
import { ApiError } from '../shared/http/error'
import { useSessionStore } from '../stores/session'
import { useOrganizationsStore } from '../stores/organizations'
import { fetchOrganization } from '../features/organization/api'
import { roleLabel, type OrganizationDetail } from '../features/organization/types'
import OrganizationSwitcher from '../features/organization/OrganizationSwitcher.vue'

// 组织工作区壳：单个可折叠深色悬浮侧栏（UI 规范 5.1，按 Demo 还原）。
// 当前组织由路由 orgId 决定；切换只改路由，数据作用域随路由变化。
const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const directoryStore = useOrganizationsStore()

const collapsed = ref(false)
const mobileNavOpen = ref(false)
const mobileNavButton = ref<HTMLButtonElement>()
function closeMobileNav(): void {
  if (!mobileNavOpen.value) return
  mobileNavOpen.value = false
  mobileNavButton.value?.focus()
}
watch(() => route.fullPath, closeMobileNav)
/** 导航分组展开状态（Demo 的组织工作区分组标题）。 */
const navExpanded = ref(true)
const directoryLoading = ref(true)
const orgDetail = ref<OrganizationDetail | null>(null)
const orgState = ref<'loading' | 'ready' | 'missing'>('loading')

const orgId = computed(() => String(route.params.orgId ?? ''))

// 来回切换同一组织时，也只接受最新请求及当前会话的响应。
let organizationRequestId = 0
let directoryRequestId = 0

const role = computed(() => orgDetail.value?.role ?? null)
const roleText = computed(() => (role.value ? roleLabel(role.value) : ''))
const username = computed(() => session.account?.username ?? '')
const avatarInitial = computed(() => username.value.charAt(0))

async function loadOrganization(targetOrgId: string, background = false): Promise<void> {
  const requestId = ++organizationRequestId
  if (!background) {
    orgState.value = 'loading'
    orgDetail.value = null
  }
  loadError.value = ''
  const gen = session.capture()
  try {
    const detail = await fetchOrganization(targetOrgId)
    if (requestId !== organizationRequestId || gen !== session.generation) {
      return
    }
    orgDetail.value = detail
    orgState.value = 'ready'
  } catch (error) {
    if (requestId !== organizationRequestId || gen !== session.generation) {
      return
    }
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    if (error instanceof ApiError && !error.isTransportError && [403, 404].includes(error.status)) {
      orgState.value = 'missing'
      orgDetail.value = null
      loadError.value = error.status === 403 ? '无权访问此组织。' : '组织不存在或无权访问。'
      void loadDirectory(true)
    } else if (background && orgDetail.value) {
      // 读取失败不等于撤权，不清空仍有效的身份和未保存表单。
      loadError.value = '组织权限核对失败，请重试。'
    } else {
      // 读取失败保留为可重试状态；网络问题不当作无权访问。
      orgState.value = 'missing'
      orgDetail.value = null
      loadError.value = error instanceof ApiError && error.isTransportError
        ? '网络连接失败，请检查网络后重试。'
        : '组织信息加载失败，请重试。'
    }
  }
}

const loadError = ref('')

async function loadDirectory(force = false): Promise<void> {
  directoryLoading.value = true
  const requestId = ++directoryRequestId
  const gen = session.capture()
  try {
    await directoryStore.fetchDirectory(force)
    if (directoryRequestId === requestId) {
      directoryLoading.value = false
    }
  } catch (error) {
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    directoryLoading.value = false
  }
}

function switchOrganization(targetOrgId: string): void {
  void router.push(`/org/${targetOrgId}`)
}

function onAccountCommand(command: string): void {
  if (command === 'account') {
    void router.push('/')
  } else if (command === 'logout') {
    void handleLogout()
  }
}

async function handleLogout(): Promise<void> {
  const gen = session.capture()
  try {
    await session.logout()
    if (session.status === 'guest') await router.replace('/login')
  } catch {
    if (gen === session.generation) ElMessage.error('退出结果尚未确认，请检查网络后再次退出。')
  }
}

watch(orgId, (value) => {
  if (value) {
    void loadOrganization(value)
  }
})

function reloadOrganization(): void {
  void loadOrganization(orgId.value)
  void directoryStore.fetchDirectory(true)
}

function checkPermissions(): void {
  if (document.hidden || session.status !== 'authenticated' || !orgId.value) return
  void loadOrganization(orgId.value, true)
  void loadDirectory(true)
}

onMounted(() => {
  document.addEventListener('visibilitychange', checkPermissions)
  void loadDirectory()
  if (orgId.value) {
    void loadOrganization(orgId.value)
  }
})
onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', checkPermissions)
  organizationRequestId++
  directoryRequestId++
})

const pageTitle = computed(() => (route.meta.title as string | undefined) ?? '组织概览')
</script>

<template>
  <div
    class="workspace"
    :data-nav-collapsed="collapsed"
    :data-group-collapsed="!navExpanded"
    :data-mobile-nav-open="mobileNavOpen"
  >
    <aside
      class="unified-sidebar"
      aria-label="组织侧栏"
      @keydown.esc="closeMobileNav"
    >
      <div class="u-mobile-header">
        <div class="u-mobile-context">
          <strong>{{ orgDetail?.name ?? '组织工作区' }}</strong>
          <span>{{ pageTitle }}</span>
        </div>
        <button
          ref="mobileNavButton"
          class="u-mobile-toggle"
          :aria-label="mobileNavOpen ? '收起导航' : '展开导航'"
          :aria-expanded="mobileNavOpen"
          aria-controls="organization-menu primary-navigation account-menu"
          @click="mobileNavOpen = !mobileNavOpen"
        >
          <Menu aria-hidden="true" />
          {{ mobileNavOpen ? '收起' : '菜单' }}
        </button>
      </div>
      <div class="u-brand-row">
        <RouterLink
          class="u-brand"
          to="/"
          aria-label="返回账号页"
          title="多组织活动管理"
        >
          <svg
            class="u-mark"
            viewBox="0 0 32 32"
            aria-hidden="true"
          >
            <path
              fill="currentColor"
              d="M8 8h5L8 27H3zm9-6h5l-7 25h-5z"
            />
            <path
              fill="var(--ui-color-accent)"
              d="M23 17h5l-3 10h-5z"
            />
          </svg>
          <span class="u-label u-brand-name">多组织活动管理<small>ACTIVITY WORKSPACE</small></span>
        </RouterLink>
      </div>

      <button
        class="u-collapse"
        :aria-label="collapsed ? '展开侧栏' : '折叠侧栏'"
        :aria-expanded="!collapsed"
        aria-controls="primary-navigation"
        @click="collapsed = !collapsed"
      >
        <ArrowRight :class="{ 'u-turn': !collapsed }" />
        <span class="u-label">收起侧栏</span>
      </button>

      <div
        id="organization-menu"
        class="u-organization"
      >
        <span class="u-label u-eyebrow">当前组织</span>
        <OrganizationSwitcher
          :organizations="directoryStore.items"
          :model-value="orgId"
          @change="switchOrganization"
        />
        <div class="u-meta">
          <span>{{ orgDetail ? `${orgDetail.memberCount} 位成员` : '' }}</span>
          <span>{{ roleText }}</span>
        </div>
      </div>

      <nav
        id="primary-navigation"
        class="u-navigation"
        aria-label="主要导航"
      >
        <!-- Demo 还原：导航为「分组标题 + 紧凑圆角子项」，折叠后同栏变为图标岛 -->
        <button
          class="u-group"
          :aria-expanded="navExpanded"
          aria-controls="workspace-subnav"
          @click="navExpanded = !navExpanded"
        >
          <span class="u-label">组织工作区</span>
          <ArrowDown :class="{ 'u-turn': !navExpanded }" />
        </button>
        <div
          id="workspace-subnav"
          class="u-subitems"
        >
          <RouterLink
            class="u-item"
            :class="{ active: route.name === 'org-overview' }"
            :to="`/org/${orgId}`"
            aria-label="组织概览"
            title="组织概览"
          >
            <HomeFilled />
            <span class="u-label">组织概览</span>
          </RouterLink>
          <RouterLink
            class="u-item"
            :class="{ active: route.name === 'org-activities' || route.name === 'org-activity-detail' }"
            :to="`/org/${orgId}/activities`"
            aria-label="活动管理"
            title="活动管理"
          >
            <FolderOpened />
            <span class="u-label">活动管理</span>
          </RouterLink>
          <RouterLink
            class="u-item"
            :class="{ active: route.name === 'org-my-tasks' }"
            :to="`/org/${orgId}/my-tasks`"
            aria-label="我的任务"
            title="我的任务"
          >
            <CircleCheck />
            <span class="u-label">我的任务</span>
          </RouterLink>
          <RouterLink
            class="u-item"
            :class="{ active: route.name === 'org-members' }"
            :to="`/org/${orgId}/members`"
            aria-label="成员管理"
            title="成员管理"
          >
            <User />
            <span class="u-label">成员管理</span>
          </RouterLink>
          <RouterLink
            class="u-item"
            :class="{ active: route.name === 'org-settings' }"
            :to="`/org/${orgId}/settings`"
            aria-label="组织设置"
            title="组织设置"
          >
            <Setting />
            <span class="u-label">组织设置</span>
          </RouterLink>
        </div>
      </nav>

      <div
        id="account-menu"
        class="u-account"
      >
        <ElDropdown
          trigger="click"
          @command="onAccountCommand"
        >
          <button
            class="u-profile"
            aria-label="账号菜单"
            :title="username"
          >
            <span class="u-avatar">{{ avatarInitial }}</span>
            <span class="u-label">{{ username }}<small>{{ roleText }}</small></span>
            <ArrowDown class="u-label" />
          </button>
          <template #dropdown>
            <ElDropdownMenu>
              <ElDropdownItem command="account">
                账号管理
              </ElDropdownItem>
              <ElDropdownItem command="logout">
                退出登录
              </ElDropdownItem>
            </ElDropdownMenu>
          </template>
        </ElDropdown>
      </div>
    </aside>

    <div class="main-shell">
      <header class="topbar">
        <div>
          <span>{{ orgDetail?.name ?? (orgState === 'missing' ? '组织不可访问' : '加载中…') }}</span>
          <span class="breadcrumb-separator">/</span>
          <strong>{{ pageTitle }}</strong>
        </div>
      </header>
      <main class="org-main">
        <ElAlert
          v-if="loadError && orgState === 'ready'"
          type="warning"
          :title="loadError"
          :closable="false"
        >
          <ElButton @click="checkPermissions">
            重新核对权限
          </ElButton>
        </ElAlert>
        <!-- 组织不可访问时不渲染子页，避免子页继续加载受限数据（R03） -->
        <template v-if="orgState !== 'missing'">
          <RouterView v-slot="{ Component }">
            <component
              :is="Component"
              :key="`${orgId}:${orgDetail?.role}`"
              :org-detail="orgDetail"
              :state="orgState"
              :error-text="loadError"
              @retry="loadOrganization(orgId)"
              @changed="reloadOrganization"
            />
          </RouterView>
        </template>
        <section
          v-else
          class="org-missing"
        >
          <p
            class="org-missing__text"
            role="alert"
          >
            {{ loadError || '组织不存在或无权访问。' }}
          </p>
          <ElButton @click="loadOrganization(orgId)">
            重新加载
          </ElButton>
          <RouterLink
            class="org-missing__back"
            to="/"
          >
            返回账号页
          </RouterLink>
        </section>
      </main>
    </div>
  </div>
</template>

<style scoped>
/* 侧栏与壳样式迁移自 UI演示/src/sidebar.css 与 App.vue（token 已对齐）。 */
.workspace > .main-shell {
  margin-left: var(--ui-layout-main-offset);
  transition: margin-left var(--ui-motion-base) var(--ui-motion-ease);
}

.unified-sidebar {
  position: var(--ui-layout-nav-position);
  inset: var(--ui-layout-outer-gap) auto auto var(--ui-layout-nav-left);
  width: var(--ui-layout-nav-width);
  height: var(--ui-nav-height);
  margin: var(--ui-nav-margin);
  padding: var(--ui-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--ui-nav-gap);
  overflow-y: auto;
  overflow-x: hidden;
  color: var(--ui-color-on-dark);
  background: var(--ui-surface-spotlight);
  border: var(--ui-border-width) solid var(--ui-color-border-dark);
  border-radius: var(--ui-radius-island);
  box-shadow: var(--ui-shadow-spotlight);
  z-index: var(--ui-z-navigation);
  font-family: var(--ui-font-family-body);
  font-size: var(--ui-font-size-body);
  line-height: var(--ui-line-height-body);
  transition: width var(--ui-motion-base) var(--ui-motion-ease);
}

.unified-sidebar svg {
  width: var(--ui-icon);
  height: var(--ui-icon);
}

.unified-sidebar .u-label {
  display: var(--ui-nav-label-display);
}

.u-brand-row {
  display: var(--ui-nav-brand-display);
  align-items: center;
  padding: var(--ui-space-2) 0;
}

.u-brand {
  display: flex;
  gap: var(--ui-space-2);
  align-items: center;
  white-space: nowrap;
  color: inherit;
  text-decoration: none;
}

.unified-sidebar .u-mark {
  width: var(--ui-nav-logo-size);
  height: var(--ui-nav-logo-size);
  flex-shrink: 0;
}

.u-brand-name {
  font-weight: var(--ui-font-weight-semibold);
}

.u-brand-name small,
.u-profile small {
  display: block;
  font-size: var(--ui-font-size-caption);
  color: var(--ui-color-on-dark-muted);
  font-weight: var(--ui-font-weight-regular);
}

.u-brand-name small {
  font-family: var(--ui-font-family-data);
}

.unified-sidebar button {
  border: 0;
  font: inherit;
  color: inherit;
  background: transparent;
  border-radius: var(--ui-radius-control);
}

.unified-sidebar button:focus-visible,
.u-brand:focus-visible {
  outline: var(--ui-focus-width) solid var(--ui-color-on-dark-accent);
  outline-offset: var(--ui-focus-offset);
}

.u-collapse {
  display: var(--ui-nav-toggle-display);
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-2);
  min-height: var(--ui-control-min-touch);
  width: 100%;
}

.u-collapse svg {
  transition: rotate var(--ui-motion-base) var(--ui-motion-ease);
}

.u-collapse .u-turn {
  rotate: 180deg;
}

.u-eyebrow {
  display: var(--ui-nav-meta-display);
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-caption);
  margin-bottom: var(--ui-space-2);
}

.u-organization .organization-select {
  width: 100%;
  margin: 0;
}

.u-organization {
  display: var(--ui-nav-content-display);
}

.u-mobile-header {
  display: var(--ui-nav-mobile-header-display);
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
}

.u-mobile-context {
  display: grid;
  min-width: 0;
  gap: var(--ui-space-1);
}

.u-mobile-context strong {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: var(--ui-font-size-body);
}

.u-mobile-context span {
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-caption);
}

.u-mobile-toggle {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  min-height: var(--ui-control-min-touch);
  padding: var(--ui-space-2);
  flex-shrink: 0;
  cursor: pointer;
}

.u-organization :deep(.el-select__wrapper) {
  min-height: var(--ui-control-min-touch);
  padding: var(--ui-space-2);
  background: var(--ui-color-glass-on-dark);
  box-shadow: inset 0 0 0 var(--ui-border-width) var(--ui-color-border-dark);
  border-radius: var(--ui-radius-control);
}

.u-organization :deep(.el-select__selected-item),
.u-organization :deep(.el-select__input) {
  color: var(--ui-color-on-dark);
  font-size: var(--ui-font-size-small);
}

.u-organization :deep(.el-select__suffix) {
  display: var(--ui-nav-label-display);
  color: var(--ui-color-on-dark-muted);
}

.u-organization :deep(.el-select__selection) {
  display: flex;
  min-width: 0;
}

.u-organization :deep(.el-select__placeholder) {
  display: var(--ui-nav-label-display);
}

.u-org-initial {
  color: var(--ui-color-on-dark-accent);
  font-weight: var(--ui-font-weight-semibold);
}

.u-meta {
  display: var(--ui-nav-meta-display);
  justify-content: space-between;
  gap: var(--ui-space-2);
  margin-top: var(--ui-space-2);
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-caption);
}

.u-navigation {
  display: var(--ui-nav-display);
  grid-template-columns: var(--ui-nav-columns);
  gap: var(--ui-space-2);
}

/* 分组标题：小号字距标题；是否显示由导航 token 决定（窄屏为 none） */
.unified-sidebar .u-group {
  display: var(--ui-nav-group-display);
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: var(--ui-space-2);
  padding: var(--ui-space-1) var(--ui-space-3);
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-caption);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
  cursor: pointer;
}

.unified-sidebar .u-group :deep(svg) {
  width: var(--ui-icon-small);
  height: var(--ui-icon-small);
  transition: transform var(--ui-motion-fast) var(--ui-motion-ease);
}

.unified-sidebar .u-group .u-turn {
  transform: rotate(180deg);
}

/* 宽屏 flex 让菜单间距生效，窄屏 contents 让各项进入导航网格。 */
.unified-sidebar .u-subitems {
  display: var(--ui-nav-items-display);
  flex-direction: column;
  gap: var(--ui-space-2);
}

.unified-sidebar .u-item {
  width: 100%;
  display: flex;
  flex-direction: var(--ui-nav-item-direction);
  align-items: center;
  justify-content: var(--ui-nav-item-justify);
  gap: var(--ui-nav-item-gap);
  min-height: var(--ui-control-min-touch);
  padding: var(--ui-nav-item-padding);
  border-radius: var(--ui-radius-control);
  white-space: nowrap;
  color: var(--ui-color-on-dark-muted);
  text-decoration: none;
  transition:
    background var(--ui-motion-fast) var(--ui-motion-ease),
    color var(--ui-motion-fast) var(--ui-motion-ease);
}

.unified-sidebar .u-item :deep(svg) {
  width: var(--ui-icon);
  height: var(--ui-icon);
  flex-shrink: 0;
}

.unified-sidebar .u-item:hover {
  background: var(--ui-color-glass-on-dark);
  color: var(--ui-color-on-dark);
}

.unified-sidebar .u-item.active {
  background: var(--ui-color-selected-bg);
  color: var(--ui-color-selected-text);
  font-weight: var(--ui-font-weight-semibold);
}

.u-account {
  display: var(--ui-nav-content-display);
  margin-top: auto;
  padding-top: var(--ui-space-4);
  border-top: var(--ui-border-width) solid var(--ui-color-border-dark);
}

.u-account .el-dropdown {
  width: 100%;
  color: inherit;
}

.u-profile {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-1);
  width: 100%;
  text-align: left;
  min-height: var(--ui-control-min-touch);
}

.u-profile > :deep(svg) {
  margin-left: auto;
}

.u-avatar {
  display: grid;
  place-items: center;
  width: var(--ui-nav-logo-size);
  height: var(--ui-nav-logo-size);
  flex-shrink: 0;
  border-radius: var(--ui-radius-circle);
  background: var(--ui-color-glass-on-dark);
  color: var(--ui-color-on-dark-accent);
}

.topbar {
  position: sticky;
  top: var(--ui-layout-outer-gap);
  z-index: var(--ui-z-sticky);
  min-height: var(--ui-layout-topbar-height);
  margin: var(--ui-layout-outer-gap) var(--ui-layout-page-padding) 0;
  border: var(--ui-border-width) solid var(--ui-color-glass-edge);
  border-radius: var(--ui-radius-island);
  box-shadow: var(--ui-shadow-glass);
  display: var(--ui-layout-topbar-display);
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  padding: var(--ui-space-2) var(--ui-space-5);
  font-size: var(--ui-font-size-body);
  color: var(--ui-color-text-secondary);
  background: var(--ui-color-surface);
}

.topbar > div {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ui-space-3);
  min-width: 0;
  overflow-wrap: anywhere;
}

.topbar strong {
  padding: var(--ui-space-1) var(--ui-space-3);
  border-radius: var(--ui-radius-control);
  background: var(--ui-surface-glass);
  color: var(--ui-color-text);
  font-weight: var(--ui-font-weight-medium);
}

.breadcrumb-separator {
  color: var(--ui-color-text-muted);
}

.org-missing {
  display: grid;
  justify-items: start;
  gap: var(--ui-space-3);
  margin-top: var(--ui-space-8);
}

.org-missing__text {
  color: var(--ui-color-text-secondary);
}

.org-missing__back {
  color: var(--ui-color-selected-text);
  font-size: var(--ui-font-size-small);
  text-decoration: none;
}

.org-missing__back:hover {
  text-decoration: underline;
}

.org-main {
  padding: var(--ui-layout-page-padding) var(--ui-layout-page-padding) 0;
  max-width: var(--ui-layout-content-max);
  margin: 0 auto;
}

</style>
