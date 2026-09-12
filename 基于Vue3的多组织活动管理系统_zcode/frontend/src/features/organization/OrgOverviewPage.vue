<script setup lang="ts">
import { ElButton, ElTag } from 'element-plus'
import { roleLabel, type OrganizationDetail } from './types'

const props = defineProps<{
  orgDetail: OrganizationDetail | null
  state: 'loading' | 'ready' | 'missing'
  errorText: string
}>()

const emit = defineEmits<{
  retry: []
}>()

const isOwner = (props.orgDetail?.role ?? null) === 'OWNER'
</script>

<template>
  <div>
    <div class="page-heading">
      <div>
        <p class="page-eyebrow">
          组织协作 / 概览
        </p>
        <h1>
          <template v-if="state === 'ready' && orgDetail">
            {{ orgDetail.name }}
          </template>
          <template v-else-if="state === 'loading'">
            组织概览
          </template>
          <template v-else>
            组织不可访问
          </template>
        </h1>
        <p
          v-if="state === 'ready' && orgDetail?.description"
          class="page-description"
        >
          {{ orgDetail.description }}
        </p>
      </div>
    </div>

    <p
      v-if="state === 'loading'"
      class="page-note"
      aria-live="polite"
    >
      正在加载组织信息……
    </p>

    <section
      v-else-if="state === 'ready' && orgDetail"
      class="overview-card"
    >
      <dl class="overview-card__list">
        <div>
          <dt>组织简介</dt>
          <dd>{{ orgDetail.description || '暂无简介' }}</dd>
        </div>
        <div>
          <dt>成员数</dt>
          <dd>{{ orgDetail.memberCount }}</dd>
        </div>
        <div>
          <dt>我的角色</dt>
          <dd>
            <ElTag :type="isOwner ? 'success' : 'info'">
              {{ roleLabel(orgDetail.role) }}
            </ElTag>
          </dd>
        </div>
        <div>
          <dt>创建时间</dt>
          <dd>{{ orgDetail.createdAt.slice(0, 10) }}</dd>
        </div>
      </dl>
      <p class="overview-card__note">
        成员邀请、活动管理等能力将随开发阶段逐项开放；当前已开放的信息以实际权限为准。
      </p>
    </section>

    <section
      v-else
      class="overview-card overview-card--missing"
    >
      <p
        class="page-note"
        role="alert"
      >
        {{ errorText || '组织不存在或无权访问。' }}
      </p>
      <ElButton @click="emit('retry')">
        重新加载
      </ElButton>
      <RouterLink
        class="overview-card__back"
        to="/"
      >
        返回账号页
      </RouterLink>
    </section>
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
  line-height: var(--ui-line-height-tight);
  letter-spacing: var(--ui-letter-spacing-title);
}

.page-description {
  margin-top: var(--ui-space-3);
  color: var(--ui-color-text-secondary);
  line-height: var(--ui-line-height-body);
}

.page-note {
  margin-top: var(--ui-space-6);
  color: var(--ui-color-text-secondary);
}

.overview-card {
  margin-top: var(--ui-space-8);
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
  box-shadow: var(--ui-shadow-glass);
}

.overview-card__list {
  display: grid;
  grid-template-columns: var(--ui-layout-form-columns);
  gap: var(--ui-space-6);
  margin: 0;
}

.overview-card__list dt {
  margin-bottom: var(--ui-space-2);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.overview-card__list dd {
  margin: 0;
  color: var(--ui-color-text);
  overflow-wrap: anywhere;
}

.overview-card__note {
  margin-top: var(--ui-space-6);
  padding-top: var(--ui-space-4);
  border-top: var(--ui-border-width) solid var(--ui-color-border);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
}

.overview-card--missing {
  display: grid;
  justify-items: start;
  gap: var(--ui-space-3);
}

.overview-card__back {
  color: var(--ui-color-selected-text);
  font-size: var(--ui-font-size-small);
  text-decoration: none;
}

.overview-card__back:hover {
  text-decoration: underline;
}
</style>
