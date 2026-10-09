<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElButton, ElTag } from 'element-plus'
import { ApiError, readErrorMessage } from '../../shared/http/error'
import { invalidateCsrfCredential, useSessionStore } from '../../stores/session'
import {
  fetchNotifications, markAllNotificationsRead, markNotificationRead, notificationTypeLabel,
  type NotificationItem,
} from './notifications'

// P7.3 站内提示：只含必要摘要；列表与跳转都由后端按当前权限复核，撤权后旧提示不再下发。
const session = useSessionStore()

const items = ref<NotificationItem[]>([])
const total = ref(0)
const unread = ref(0)
const loading = ref(false)
const errorText = ref('')
const acting = ref('')

const hasItems = computed(() => items.value.length > 0)

async function load(): Promise<void> {
  const gen = session.capture()
  loading.value = true
  errorText.value = ''
  try {
    const page = await fetchNotifications(1, 20)
    if (gen !== session.generation) {
      return
    }
    items.value = page.items
    total.value = page.total
    unread.value = page.unread
  } catch (error) {
    if (gen !== session.generation) {
      return
    }
    items.value = []
    total.value = 0
    unread.value = 0
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = readErrorMessage(error, '提示加载失败，请重试。')
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function markRead(item: NotificationItem) {
  if (item.readAt) {
    return
  }
  const gen = session.capture()
  acting.value = item.notificationId
  errorText.value = ''
  try {
    await markNotificationRead(item.notificationId)
    if (gen !== session.generation) return
    await load()
  } catch (error) {
    if (session.observeUnauthorized(error, gen) || gen !== session.generation) {
      return
    }
    if (error instanceof ApiError && error.code === 'CSRF_INVALID') {
      invalidateCsrfCredential()
      errorText.value = '安全校验已过期，请重试。'
    } else if (error instanceof ApiError && !error.isTransportError) {
      errorText.value = error.detail || '操作失败，请重试。'
    } else {
      errorText.value = '网络连接失败，请检查网络后重试。'
    }
  } finally {
    acting.value = ''
  }
}

async function markAll() {
  const gen = session.capture()
  acting.value = 'all'
  errorText.value = ''
  try {
    await markAllNotificationsRead()
    if (gen !== session.generation) return
    await load()
  } catch (error) {
    if (session.observeUnauthorized(error, gen) || gen !== session.generation) {
      return
    }
    errorText.value = error instanceof ApiError && !error.isTransportError
      ? (error.detail || '操作失败，请重试。')
      : '网络连接失败，请检查网络后重试。'
  } finally {
    acting.value = ''
  }
}

defineExpose({ reload: load })
</script>

<template>
  <section class="notifications">
    <div class="notifications__head">
      <h2>站内提示</h2>
      <div class="notifications__tools">
        <ElTag
          v-if="unread > 0"
          size="small"
          type="warning"
        >
          未读 {{ unread }}
        </ElTag>
        <ElButton
          v-if="unread > 0"
          size="small"
          :loading="acting === 'all'"
          @click="markAll"
        >
          全部标为已读
        </ElButton>
      </div>
    </div>

    <p
      v-if="loading"
      class="notifications__note"
      aria-live="polite"
    >
      正在加载提示……
    </p>
    <p
      v-else-if="errorText"
      class="notifications__error"
      role="alert"
    >
      {{ errorText }}
      <ElButton
        text
        @click="load"
      >
        重新加载
      </ElButton>
    </p>

    <ul
      v-else-if="hasItems"
      class="notifications__list"
    >
      <li
        v-for="item in items"
        :key="item.notificationId"
        :class="{ 'notifications__item--unread': !item.readAt }"
      >
        <ElTag
          size="small"
          type="info"
        >
          {{ notificationTypeLabel[item.type] ?? item.type }}
        </ElTag>
        <span class="notifications__payload">{{ item.payload }}</span>
        <RouterLink
          v-if="item.activityPath"
          class="notifications__link"
          :to="item.activityPath"
        >
          {{ item.activityName ?? '查看活动' }}
        </RouterLink>
        <span class="notifications__note">{{ item.createdAt.slice(0, 16).replace('T', ' ') }}</span>
        <ElButton
          v-if="!item.readAt"
          size="small"
          link
          :loading="acting === item.notificationId"
          @click="markRead(item)"
        >
          标为已读
        </ElButton>
      </li>
    </ul>
    <p
      v-else
      class="notifications__note"
    >
      暂无提示。
    </p>
    <p
      v-if="total > items.length"
      class="notifications__note"
    >
      共 {{ total }} 条，仅显示最近 {{ items.length }} 条。
    </p>
  </section>
</template>

<style scoped>
.notifications {
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
}

.notifications__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-4);
}

.notifications__head h2 {
  font-size: var(--ui-font-size-section);
}

.notifications__tools {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
}

.notifications__note {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}

.notifications__error {
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.notifications__list {
  margin: var(--ui-space-4) 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.notifications__list li {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  flex-wrap: wrap;
  font-size: var(--ui-font-size-small);
}

.notifications__item--unread .notifications__payload {
  font-weight: var(--ui-font-weight-medium);
}

.notifications__link {
  color: var(--ui-color-action);
}

.notifications { min-width: 0; }
.notifications__head, .notifications__tools { flex-wrap: wrap; }
.notifications__list li { padding: var(--ui-space-3); border-radius: var(--ui-radius-control); background: var(--ui-color-canvas); border-left: var(--ui-space-1) solid transparent; }
.notifications__list .notifications__item--unread { border-left-color: var(--ui-color-action); }
.notifications__payload { flex-basis: 100%; overflow-wrap: anywhere; line-height: var(--ui-line-height-body); }
.notifications__head + .notifications__note { margin-top: var(--ui-space-5); }
</style>
