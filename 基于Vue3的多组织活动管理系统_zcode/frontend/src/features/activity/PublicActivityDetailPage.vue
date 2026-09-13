<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElButton, ElSkeleton, ElTag } from 'element-plus'
import { ApiError } from '../../shared/http/error'
import { useSessionStore } from '../../stores/session'
import { applyBlockReason, fetchPublicActivity, type PublicActivityItem } from './publicActivity'
import { formatDateTime, scopeLabel } from './types'

// 公开活动详情（P4.1）：只读展示发布者公开的安排与报名条件。
// 报名、取消与结果查询在登录后的组织工作区内完成，本页不展示任何内部字段。
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const activityId = computed(() => String(route.params.activityId ?? ''))
const item = ref<PublicActivityItem | null>(null)
const loading = ref(false)
const errorText = ref('')
let requestSeq = 0

async function load(): Promise<void> {
  if (!activityId.value) {
    return
  }
  const seq = ++requestSeq
  const target = activityId.value
  loading.value = true
  errorText.value = ''
  try {
    const detail = await fetchPublicActivity(target)
    if (seq !== requestSeq || target !== activityId.value) {
      return
    }
    item.value = detail
  } catch (error) {
    if (seq !== requestSeq) {
      return
    }
    item.value = null
    errorText.value = error instanceof ApiError && error.isTransportError
      ? '网络连接失败，请检查网络后重试。'
      : '活动不存在或未公开。'
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

watch(activityId, load)
onMounted(load)

const blockReason = computed(() => (item.value ? applyBlockReason(item.value) : ''))

/** 报名/取消/查看结果都在登录后的组织工作区完成；未登录先登录并回跳本页。 */
function goApply(): void {
  if (!item.value) {
    return
  }
  if (session.status !== 'authenticated') {
    void router.push({
      path: '/login',
      query: { next: `/public/activities/${item.value.id}` },
    })
    return
  }
  void router.push('/')
}
</script>

<template>
  <section class="public-detail">
    <ElSkeleton
      v-if="loading"
      :rows="6"
      animated
    />

    <template v-else-if="item">
      <nav class="public-breadcrumb">
        <RouterLink to="/public/activities">
          公开活动
        </RouterLink>
        <span aria-hidden="true">/</span>
        <span>{{ item.name }}</span>
      </nav>

      <header class="public-detail__head">
        <div>
          <p class="public-eyebrow">
            {{ item.organizationName }}
          </p>
          <h1>{{ item.name }}<span class="public-accent">。</span></h1>
          <p class="public-intro">
            {{ item.description || '发布者暂未填写活动简介。' }}
          </p>
        </div>
        <ElTag
          size="small"
          :type="blockReason ? 'info' : 'success'"
        >
          {{ blockReason || '报名开放中' }}
        </ElTag>
      </header>

      <dl class="public-facts">
        <div><dt>开始时间</dt><dd>{{ formatDateTime(item.startTime) }}</dd></div>
        <div><dt>结束时间</dt><dd>{{ formatDateTime(item.endTime) }}</dd></div>
        <div><dt>活动地点</dt><dd>{{ item.location || '待定' }}</dd></div>
        <div><dt>报名范围</dt><dd>{{ scopeLabel(item.registrationScope) }}</dd></div>
        <div><dt>名额</dt><dd>{{ item.capacity ?? '—' }}</dd></div>
        <div><dt>报名开始</dt><dd>{{ formatDateTime(item.registrationStart) }}</dd></div>
        <div><dt>报名截止</dt><dd>{{ formatDateTime(item.registrationEnd) }}</dd></div>
      </dl>

      <div class="public-detail__actions">
        <ElButton
          type="primary"
          :disabled="Boolean(blockReason)"
          @click="goApply"
        >
          {{ session.status === 'authenticated' ? '进入工作区报名' : '登录后报名' }}
        </ElButton>
        <span class="public-hint">
          {{ blockReason || '报名后可在“我的报名”查看审核结果，并在活动开始前取消。' }}
        </span>
      </div>

      <p class="public-note">
        本页只包含发布者公开的活动信息；任务分工、成员、预算与资料属于组织内部内容，不在此展示。
      </p>
    </template>

    <p
      v-else
      class="public-error"
      role="alert"
    >
      {{ errorText }}
      <ElButton
        text
        @click="load"
      >
        重试
      </ElButton>
    </p>
  </section>
</template>

<style scoped>
.public-breadcrumb {
  display: flex;
  gap: var(--ui-space-2);
  margin-bottom: var(--ui-space-6);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.public-breadcrumb a {
  color: var(--ui-color-selected-text);
  text-decoration: none;
}

.public-detail__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ui-space-4);
  margin-bottom: var(--ui-space-8);
}

.public-eyebrow {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
}

.public-detail__head h1 {
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

.public-facts {
  display: grid;
  gap: var(--ui-layout-grid-gap);
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 220px), 1fr));
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
  font-size: var(--ui-font-size-small);
}

.public-facts dt {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.public-facts dd {
  margin-top: var(--ui-space-1);
}

.public-detail__actions {
  display: flex;
  align-items: center;
  gap: var(--ui-space-4);
  margin-top: var(--ui-space-6);
  flex-wrap: wrap;
}

.public-hint,
.public-note {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}

.public-note {
  margin-top: var(--ui-space-8);
  line-height: var(--ui-line-height-prose);
}

.public-error {
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}
</style>
