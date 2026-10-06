<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { ElButton, ElDropdown, ElDropdownItem, ElDropdownMenu, ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { useSessionStore } from '../stores/session'

// 公开参与壳（UI 规范 5.4）：独立于组织管理壳，只保留品牌、报名入口与账号状态，
// 不暴露内部导航、预算、成员或附件。手机为紧凑文档流。
const session = useSessionStore()
const router = useRouter()
const authenticated = computed(() => session.status === 'authenticated')

function onAccountCommand(command: string) {
  if (command === 'account') {
    void router.push('/')
  } else if (command === 'logout') {
    const gen = session.capture()
    void session.logout().catch(() => {
      if (gen === session.generation) ElMessage.error('退出结果尚未确认，请检查网络后再次退出。')
    })
  }
}
</script>

<template>
  <div class="public-page">
    <header class="public-topbar">
      <RouterLink
        class="public-brand"
        to="/public/activities"
      >
        <span
          class="public-mark"
          aria-hidden="true"
        ><span /><span /><span /></span>
        多组织活动管理
      </RouterLink>
      <nav
        class="public-nav"
        aria-label="公开区导航"
      >
        <RouterLink to="/public/activities">
          公开活动
        </RouterLink>
      </nav>
      <div class="public-account">
        <template v-if="authenticated">
          <ElDropdown
            trigger="click"
            @command="onAccountCommand"
          >
            <button
              class="public-account-button"
              aria-label="账号菜单"
            >
              {{ session.account?.username }}
              <ArrowDown />
            </button>
            <template #dropdown>
              <ElDropdownMenu>
                <ElDropdownItem command="account">
                  返回我的工作区
                </ElDropdownItem>
                <ElDropdownItem command="logout">
                  退出登录
                </ElDropdownItem>
              </ElDropdownMenu>
            </template>
          </ElDropdown>
        </template>
        <template v-else>
          <ElButton
            text
            @click="router.push({ path: '/login', query: { next: '/public/activities' } })"
          >
            登录
          </ElButton>
        </template>
      </div>
    </header>
    <main class="public-main">
      <RouterView />
    </main>
    <footer class="public-footer">
      <span>多组织活动管理系统</span>
      <span>公开活动浏览</span>
    </footer>
  </div>
</template>

<style scoped>
.public-page {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: var(--ui-color-surface-muted);
}

.public-topbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ui-space-6);
  min-height: var(--ui-layout-topbar-height);
  padding: var(--ui-space-3) var(--ui-layout-page-padding);
  border-bottom: var(--ui-border-width) solid var(--ui-color-border);
  background: var(--ui-color-surface);
}

.public-brand {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  color: var(--ui-color-text);
  font-weight: var(--ui-font-weight-semibold);
  text-decoration: none;
  white-space: nowrap;
}

.public-mark {
  display: inline-flex;
  gap: var(--ui-space-1);
}

.public-mark span {
  width: var(--ui-space-2);
  height: var(--ui-space-2);
  border-radius: var(--ui-radius-circle);
  background: var(--ui-color-text);
}

.public-mark span:last-child {
  background: var(--ui-color-accent);
}

.public-nav {
  display: flex;
  white-space: nowrap;
  gap: var(--ui-space-4);
  font-size: var(--ui-font-size-small);
}

.public-nav a {
  color: var(--ui-color-text-muted);
  text-decoration: none;
}

.public-nav a.router-link-active {
  color: var(--ui-color-text);
  font-weight: var(--ui-font-weight-semibold);
}

.public-account {
  margin-left: auto;
  max-width: 100%;
}

.public-account :deep(.el-dropdown) {
  max-width: 100%;
}

.public-account-button {
  display: inline-flex;
  max-width: 100%;
  overflow-wrap: anywhere;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-2) var(--ui-space-3);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-surface);
  color: var(--ui-color-text);
  font: inherit;
  cursor: pointer;
}

.public-account-button svg {
  width: var(--ui-font-size-small);
  flex-shrink: 0;
}

.public-main {
  flex: 1;
  width: 100%;
  max-width: var(--ui-layout-content-max);
  margin: 0 auto;
  padding: var(--ui-layout-page-padding);
  /* 窄屏核对：内容盒宽度会变成 100% + 左右内边距，导致公开页整体横向溢出 */
  box-sizing: border-box;
}

.public-footer {
  display: flex;
  justify-content: space-between;
  gap: var(--ui-space-4);
  padding: var(--ui-space-4) var(--ui-layout-page-padding);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}
</style>
