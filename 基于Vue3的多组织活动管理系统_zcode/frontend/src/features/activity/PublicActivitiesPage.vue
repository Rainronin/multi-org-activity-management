<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ElButton, ElSkeleton, ElTag } from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { useSessionStore } from '../../stores/session'
import { applyBlockReason, fetchPublicActivities, type PublicActivityItem } from './publicActivity'
import { formatDateTime, scopeLabel } from './types'

// 公开活动列表（P4.1）：未登录可浏览已发布的公开活动安排与报名条件；
// 只展示公开字段，不出现预算、方案、成员或附件。
const session = useSessionStore()
const router = useRouter()

const items = ref<PublicActivityItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 12
const loading = ref(false)
const loadedOnce = ref(false)
const errorText = ref('')

async function load(targetPage = page.value): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const result = await fetchPublicActivities(targetPage, pageSize)
    items.value = result.items
    total.value = result.total
    page.value = result.page
  } catch (error) {
    items.value = []
    total.value = 0
    errorText.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '公开活动加载失败，请稍后重试。'
  } finally {
    loading.value = false
    loadedOnce.value = true
  }
}

onMounted(() => load(1))

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

/** 报名入口：未登录先去登录并回跳，已登录进入活动详情完成报名。 */
function startApply(item: PublicActivityItem): void {
  const detail = `/public/activities/${item.id}`
  if (session.status !== 'authenticated') {
    void router.push({ path: '/login', query: { next: detail } })
    return
  }
  void router.push(detail)
}
</script>

<template>
  <section class="public-list">
    <header class="public-list__head">
      <div>
        <p class="public-eyebrow">
          公开活动
        </p>
        <h1>看看有哪些活动正在进行<span class="public-accent">。</span></h1>
        <p class="public-intro">
          这里只展示发布者公开的活动简介、安排与报名条件；报名、取消与结果查询需要登录账号。
        </p>
      </div>
    </header>

    <p
      v-if="errorText && items.length === 0"
      class="public-error"
      role="alert"
    >
      {{ errorText }}
      <ElButton
        text
        @click="load(page)"
      >
        重新加载
      </ElButton>
    </p>

    <ElSkeleton
      v-else-if="loading"
      :rows="6"
      animated
      data-public-skeleton
      aria-busy="true"
      aria-label="正在加载公开活动"
    />

    <p
      v-else-if="loadedOnce && items.length === 0"
      class="public-empty"
      data-public-empty
    >
      暂无公开活动。公开活动需要由组织负责人确认发布并选择公开报名范围。
    </p>

    <template v-else-if="items.length > 0">
      <ul class="public-cards">
        <li
          v-for="item in items"
          :key="item.id"
          class="public-card"
        >
          <div class="public-card__head">
            <h2>{{ item.name }}</h2>
            <ElTag
              size="small"
              :type="applyBlockReason(item) ? 'info' : 'success'"
            >
              {{ applyBlockReason(item) ? '未开放报名' : '报名开放中' }}
            </ElTag>
          </div>
          <p class="public-card__org">
            {{ item.organizationName }}
          </p>
          <p class="public-card__desc">
            {{ item.description || '暂无简介' }}
          </p>
          <dl class="public-card__facts">
            <div><dt>开始</dt><dd>{{ formatDateTime(item.startTime) }}</dd></div>
            <div><dt>结束</dt><dd>{{ formatDateTime(item.endTime) }}</dd></div>
            <div><dt>地点</dt><dd>{{ item.location || '待定' }}</dd></div>
            <div><dt>名额</dt><dd>{{ item.capacity ?? '—' }}</dd></div>
            <div><dt>报名范围</dt><dd>{{ scopeLabel(item.registrationScope) }}</dd></div>
          </dl>
          <div class="public-card__actions">
            <ElButton
              type="primary"
              @click="startApply(item)"
            >
              {{ session.status === 'authenticated' ? '查看并报名' : '登录后报名' }}
            </ElButton>
            <RouterLink
              class="public-link"
              :to="`/public/activities/${item.id}`"
            >
              查看安排
            </RouterLink>
          </div>
        </li>
      </ul>

      <div
        v-if="totalPages > 1"
        class="public-pager"
      >
        <ElButton
          :disabled="page <= 1"
          @click="load(page - 1)"
        >
          上一页
        </ElButton>
        <span>第 {{ page }} / {{ totalPages }} 页</span>
        <ElButton
          :disabled="page >= totalPages"
          @click="load(page + 1)"
        >
          下一页
        </ElButton>
      </div>
    </template>
  </section>
</template>

<style scoped>
.public-list__head {
  margin-bottom: var(--ui-space-8);
}

.public-eyebrow {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
}

.public-list__head h1 {
  margin-top: var(--ui-space-2);
  font-size: var(--ui-font-size-page-title);
  font-weight: var(--ui-font-weight-bold);
  letter-spacing: var(--ui-letter-spacing-title);
}

.public-accent {
  color: var(--ui-color-accent);
}

.public-intro {
  max-width: var(--ui-form-max-width);
  margin-top: var(--ui-space-4);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-prose);
}

.public-error {
  margin-bottom: var(--ui-space-6);
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.public-empty {
  padding: var(--ui-space-8);
  border: var(--ui-border-width) dashed var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
  text-align: center;
}

.public-cards {
  display: grid;
  gap: var(--ui-layout-grid-gap);
  grid-template-columns: repeat(auto-fill, minmax(min(100%, var(--ui-layout-card-min-width-wide)), 1fr));
  padding: 0;
  margin: 0;
  list-style: none;
}

.public-card {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
}

.public-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ui-space-3);
}

.public-card__head h2 {
  font-size: var(--ui-font-size-section);
  font-weight: var(--ui-font-weight-semibold);
}

.public-card__org {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.public-card__desc {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-body);
}

.public-card__facts {
  display: grid;
  gap: var(--ui-space-2);
  grid-template-columns: repeat(2, minmax(0, 1fr));
  font-size: var(--ui-font-size-small);
}

.public-card__facts dt {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.public-card__actions {
  display: flex;
  align-items: center;
  gap: var(--ui-space-4);
  margin-top: auto;
}

.public-link {
  color: var(--ui-color-selected-text);
  font-size: var(--ui-font-size-small);
  text-decoration: none;
}

/* 微交互：与工作台同口径的悬停反馈（token 驱动，减少动效下取消） */
@media (hover: hover) and (pointer: fine) {
  .public-card {
    transition: transform var(--ui-motion-base) var(--ui-motion-ease),
      box-shadow var(--ui-motion-base) var(--ui-motion-ease);
  }

  .public-card:hover {
    transform: translateY(calc(var(--ui-motion-lift) * -1));
    box-shadow: var(--ui-shadow-hover);
  }
}

@media (prefers-reduced-motion: reduce) {
  .public-card,
  .public-card:hover {
    transition: none;
    transform: none;
  }
}

.public-link:hover {
  text-decoration: underline;
}

.public-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--ui-space-4);
  margin-top: var(--ui-space-8);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}
</style>
