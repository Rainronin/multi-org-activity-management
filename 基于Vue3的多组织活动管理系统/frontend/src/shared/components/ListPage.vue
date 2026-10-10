<script setup lang="ts">
import { computed, watch } from 'vue'
import { ElButton, ElEmpty, ElSkeleton, ElSkeletonItem } from 'element-plus'
import { useLoadingSkeleton } from '../utils/useLoadingSkeleton'

/**
 * 业务列表页公共骨架（UI 规范 5.3／5.6、第 10 节）：
 * 页头 → 筛选 → 列表面板 → 分页／页脚，并把区块状态收敛为互斥的四态。
 *
 * 状态优先级：失败且无数据 → 首次加载（与真实表格同构的骨架）
 * → 空态（已加载且无记录）→ 已有数据（刷新时保留内容，只提示正在更新）。
 * 因此永远不会同时出现骨架、空态与整表遮罩。
 *
 * 骨架的出现与收起经过 leading／trailing 延迟：本地接口几十毫秒返回时不闪一帧，
 * 骨架一旦出现也至少停留一瞬；时长只来自集中 token（UI 规范第 10 节）。
 */
const props = withDefaults(defineProps<{
  title: string
  /** 面板标题；默认与页面标题一致，便于区分时可传入具体区段名。 */
  panelTitle?: string
  /** 页头眉标；省略只显示标题与说明。 */
  eyebrow?: string
  description?: string
  /** 面板标题右侧的当前范围说明，例如“组织负责人 · 全部负责人”。 */
  context?: string
  /** 页脚左侧固定说明。 */
  message?: string
  /** 页面级错误；有内容时按状态优先级展示。 */
  error?: string
  /** 首次加载（尚无任何数据）。 */
  loading?: boolean
  /** 已有数据的后台刷新。 */
  refreshing?: boolean
  /** 是否已完成至少一次请求；未完成时不显示空态。 */
  loaded?: boolean
  /** 当前展示的记录数，决定是否进入空态。 */
  rowCount?: number
  /** 列表来源总数；用于面板计数与页脚统计。 */
  total?: number
  emptyText?: string
  skeletonRows?: number
  skeletonColumns?: number
  retryText?: string
}>(), {
  panelTitle: '',
  eyebrow: '',
  description: '',
  context: '',
  message: '',
  error: '',
  loading: false,
  refreshing: false,
  loaded: false,
  rowCount: 0,
  total: 0,
  emptyText: '暂无记录。',
  skeletonRows: 5,
  skeletonColumns: 4,
  retryText: '重试',
})

const emit = defineEmits<{
  retry: []
}>()

/** 骨架只在首次进入、尚无内容时延时出现；已有数据的刷新由 refreshing 表达。 */
const skeleton = useLoadingSkeleton()
watch(() => props.loading, (value) => {
  if (value) {
    skeleton.begin()
  } else {
    skeleton.end()
  }
}, { immediate: true })

const state = computed(() => {
  if (props.error && props.rowCount === 0) {
    return 'error'
  }
  if (skeleton.loading.value && props.rowCount === 0) {
    return 'loading'
  }
  if (props.loaded && props.rowCount === 0) {
    return 'empty'
  }
  return 'ready'
})

/** 页脚与面板计数按已有数据计算，不伪造总量。 */
const panelHeading = computed(() => props.panelTitle || props.title)

/** total 传 0 表示本区段不重复展示计数（例如计数已写在 context 里）。 */
const showCount = computed(() => props.total > 0)

const scopeNote = computed(() => (props.total > props.rowCount && props.rowCount > 0
  ? `本页 ${props.rowCount} 条 / 共 ${props.total} 条`
  : `共 ${props.total} 条`))
</script>

<template>
  <div class="list-page">
    <div class="page-heading">
      <div class="page-heading__title">
        <p
          v-if="eyebrow"
          class="page-eyebrow"
        >
          {{ eyebrow }}
        </p>
        <h1>{{ title }}</h1>
        <p
          v-if="description"
          class="page-description"
        >
          {{ description }}
        </p>
      </div>
      <div
        v-if="$slots.actions"
        class="page-actions"
      >
        <slot name="actions" />
      </div>
    </div>

    <section
      class="list-panel"
      :aria-busy="loading || refreshing ? 'true' : 'false'"
    >
      <div class="list-panel__head">
        <h2>
          {{ panelHeading }}
          <span
            v-if="state === 'ready' && showCount"
            class="list-panel__count"
          >{{ total }}</span>
        </h2>
        <span
          v-if="state === 'ready' && refreshing"
          class="list-panel__status"
          role="status"
        >
          正在更新…
        </span>
        <span
          v-else-if="state === 'ready' && context"
          class="list-panel__aside"
        >
          {{ context }}
        </span>
      </div>

      <div
        v-if="$slots.filters"
        class="list-panel__filters"
      >
        <slot name="filters" />
      </div>

      <p
        v-if="state === 'error'"
        class="list-panel__error"
        role="alert"
      >
        {{ error }}
        <ElButton
          size="small"
          @click="emit('retry')"
        >
          {{ retryText }}
        </ElButton>
      </p>

      <ElSkeleton
        v-else-if="state === 'loading'"
        class="list-panel__skeleton"
        animated
        data-list-skeleton
        :aria-label="`正在加载${title}`"
      >
        <template #template>
          <div class="list-panel__skeleton-head">
            <ElSkeletonItem
              v-for="column in skeletonColumns"
              :key="column"
              variant="text"
            />
          </div>
          <div
            v-for="row in skeletonRows"
            :key="row"
            class="list-panel__skeleton-row"
          >
            <ElSkeletonItem
              v-for="column in skeletonColumns"
              :key="column"
              variant="text"
            />
          </div>
        </template>
      </ElSkeleton>

      <div
        v-else-if="state === 'empty'"
        class="list-panel__empty"
        data-list-empty
      >
        <slot name="empty">
          <ElEmpty :description="emptyText" />
        </slot>
      </div>

      <template v-else>
        <slot />
        <div
          v-if="showCount || message"
          class="list-panel__footer"
        >
          <span class="list-panel__note">{{ message }}</span>
          <span
            v-if="showCount"
            data-list-total
          >{{ scopeNote }}</span>
        </div>
      </template>
    </section>

    <slot name="footer" />
  </div>
</template>

<style scoped>
/* 页头排版属于本组件的组合样式，必须留在 scoped 里：
   写成全局 .page-heading 会连同工作台等既有页面一起改变构图（审核反馈 1）。 */
.page-heading {
  display: flex;
  align-items: flex-start;
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

.page-description {
  margin-top: var(--ui-space-3);
  color: var(--ui-color-text-secondary);
  line-height: var(--ui-line-height-body);
}

.page-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-3);
}

/* 表格页仍有大号 h1；面板标题是同一区段的小标题，保持一致层级。 */
.list-panel__head h2 {
  font-size: var(--ui-font-size-emphasis);
  font-weight: var(--ui-font-weight-semibold);
}
</style>
