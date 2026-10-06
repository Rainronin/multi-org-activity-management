<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElTag } from 'element-plus'
import { roleLabel, type OrganizationDetail } from './types'
import WorkbenchSection from '../workbench/WorkbenchSection.vue'

const props = defineProps<{
  orgDetail: OrganizationDetail | null
  state: 'loading' | 'ready' | 'missing'
  errorText: string
}>()

const emit = defineEmits<{
  retry: []
}>()

const isOwner = computed(() => props.orgDetail?.role === 'OWNER')
</script>

<template>
  <div>
    <div class="page-heading">
      <div>
        <p class="page-eyebrow">
          工作台
        </p>
        <h1>
          <template v-if="state === 'ready' && orgDetail">
            {{ orgDetail.name }}<span class="page-heading__dot">。</span>
          </template>
          <template v-else-if="state === 'loading'">
            组织概览
          </template>
          <template v-else>
            组织不可访问
          </template>
        </h1>
        <p class="page-description">
          <template v-if="state === 'ready' && orgDetail">
            {{ orgDetail.description || '查看待办，跟进活动与分工。' }}
          </template>
          <template v-else>
            工作台按当前身份展示待办与指标。
          </template>
        </p>
      </div>
      <ElTag
        v-if="state === 'ready' && orgDetail"
        :type="isOwner ? 'success' : 'info'"
      >
        {{ roleLabel(orgDetail.role) }}
      </ElTag>
    </div>

    <p
      v-if="state === 'loading'"
      class="page-note"
      aria-live="polite"
    >
      正在加载组织信息……
    </p>

    <!-- 工作台先显示待办与重点活动，统计和文稿辅助随后展示。 -->
    <WorkbenchSection v-if="state === 'ready' && orgDetail" />

    <section
      v-if="state === 'ready' && orgDetail"
      class="org-meta"
      aria-label="组织资料"
    >
      <dl class="org-meta__list">
        <div>
          <dt>成员数</dt>
          <dd>{{ orgDetail.memberCount }}</dd>
        </div>
        <div>
          <dt>我的角色</dt>
          <dd>{{ roleLabel(orgDetail.role) }}</dd>
        </div>
        <div>
          <dt>创建时间</dt>
          <dd>{{ orgDetail.createdAt.slice(0, 10) }}</dd>
        </div>
        <div>
          <dt>组织简介</dt>
          <dd class="org-meta__description">
            {{ orgDetail.description || '暂无简介' }}
          </dd>
        </div>
      </dl>
      <p class="org-meta__note">
        成员邀请与成员管理在「成员管理」，模型配置在「组织设置」；工作台指标均来自真实业务记录。
      </p>
    </section>

    <section
      v-else-if="state !== 'loading'"
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
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ui-space-6);
}

.page-heading > div {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}

.page-heading__dot {
  color: var(--ui-color-accent);
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

/* 组织资料缩为工作台之后的紧凑信息带，不占首屏主位置 */
.org-meta {
  margin-top: var(--ui-layout-grid-gap);
  padding: var(--ui-space-4) var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface-muted);
}

.org-meta__list {
  display: grid;
  grid-template-columns: var(--ui-layout-kpi-columns);
  gap: var(--ui-space-4);
  margin: 0;
}

.org-meta__list dt {
  margin-bottom: var(--ui-space-1);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.org-meta__list dd {
  margin: 0;
  color: var(--ui-color-text);
  font-size: var(--ui-font-size-small);
  overflow-wrap: anywhere;
}

.org-meta__note {
  margin: var(--ui-space-3) 0 0;
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.overview-card--missing {
  display: grid;
  justify-items: start;
  gap: var(--ui-space-3);
  margin-top: var(--ui-space-8);
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
  box-shadow: var(--ui-shadow-glass);
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
