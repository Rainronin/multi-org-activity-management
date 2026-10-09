<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElButton,
  ElSkeleton,
  ElSkeletonItem,
  ElTag,
} from 'element-plus'
import {
  ArrowRight,
  Calendar,
  Check,
  CircleCheck,
  Document,
  Location,
  TrendCharts,
  Wallet,
} from '@element-plus/icons-vue'
import SpotlightCard from '../../shared/motion/SpotlightCard.vue'
import CountUp from '../../shared/motion/CountUp.vue'
import RubberSegment from '../../shared/motion/RubberSegment.vue'
import StatusMark from '../../shared/motion/StatusMark.vue'
import { readErrorMessage } from '../../shared/http/error'
import { useSessionStore } from '../../stores/session'
import { fetchActivities } from '../activity/api'
import type { ActivityItem } from '../activity/types'
import { fetchAiConfig, type AiConfig } from '../ai/ai'
import { fetchWorkbench, todoKindLabel, workbenchRoleLabel, type Workbench, type WorkbenchCard } from './workbench'

/**
 * P7.1／P7.2 工作台，按确认 Demo 的阅读顺序还原：
 * 待办＋重点活动 → 精简 KPI → 经费与 AI 文稿 → 指标明细。
 *
 * 数据全部来自真实接口：工作台聚合（cards／todos／myCards）、组织活动列表与组织模型配置；
 * 没有趋势接口就不画趋势，经费环图只表示"已确认支出 / 预算"这一真实口径。
 */
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const orgId = computed(() => String(route.params.orgId ?? ''))
const workbench = ref<Workbench | null>(null)
const activities = ref<ActivityItem[]>([])
const aiConfig = ref<AiConfig | null>(null)
const aiMode = ref<'总结' | '方案'>('总结')
/** R05：口径说明默认收起，明细保持紧凑；需要时一键展开。 */
const showHints = ref(false)
const loading = ref(false)
const errorText = ref('')

let requestSeq = 0

const stages = ['草稿', '待确认', '筹备中', '执行中', '复盘中', '已归档']
const stageOf: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待确认',
  APPROVED: '筹备中',
  EXECUTING: '执行中',
  REVIEWING: '复盘中',
  ARCHIVED: '已归档',
}

/** 重点活动：优先执行中，其次最早的筹备中活动（真实活动列表，按状态与开始时间取一条）。 */
const focusActivity = computed<ActivityItem | null>(() => {
  const executing = activities.value.filter((item) => item.status === 'EXECUTING')
  const preparing = activities.value.filter((item) => item.status === 'APPROVED')
  const pool = executing.length > 0 ? executing : preparing
  return [...pool].sort((a, b) => (a.startTime ?? '').localeCompare(b.startTime ?? ''))[0] ?? null
})

const focusStage = computed(() => (focusActivity.value ? stageOf[focusActivity.value.status] ?? '' : ''))
const focusDate = computed(() => {
  const value = focusActivity.value?.startTime
  return value ? value.slice(0, 10) : ''
})

/** AI 文稿候选：总结取复盘中的活动，方案取草稿活动（与业务入口一致）。 */
const aiCandidate = computed<ActivityItem | null>(() => {
  const status = aiMode.value === '总结' ? 'REVIEWING' : 'DRAFT'
  return activities.value.find((item) => item.status === status) ?? null
})
const isManager = computed(() => workbench.value?.role !== 'MEMBER')
const aiAvailable = computed(() => aiConfig.value?.available === true)
const aiOpenLabel = computed(() => aiCandidate.value
  ? `打开${aiMode.value === '总结' ? '复盘' : '方案'}编辑`
  : aiMode.value === '方案' && workbench.value?.role === 'OWNER' ? '创建活动草稿' : '查看活动')

function card(key: string): WorkbenchCard | undefined {
  const all = [...(workbench.value?.cards ?? []), ...(workbench.value?.myCards ?? [])]
  return all.find((item) => item.key === key)
}

/** KPI 带只保留四个行动指标：进行中活动、待处理任务、有效报名、已确认支出。 */
const kpis = computed(() => {
  const definitions = [
    { key: 'activities-in-progress', fallback: 'activities-mine', label: '进行中活动', unit: '个', icon: TrendCharts },
    { key: 'tasks-open', fallback: 'my-tasks-open', label: '待处理任务', unit: '个', icon: CircleCheck },
    { key: 'registrations-effective', fallback: 'my-registrations', label: '有效报名', unit: '人', icon: Check },
    { key: 'expense-approved', fallback: 'expense-approved', label: '已确认支出', unit: '元', icon: Wallet },
  ]
  return definitions
    .map((definition) => {
      const source = card(definition.key) ?? card(definition.fallback)
      if (!source) {
        return null
      }
      return {
        key: source.key,
        label: source.label,
        unit: source.key === 'my-registrations' ? '项' : definition.unit,
        icon: definition.icon,
        value: source.value,
        hint: source.hint,
        path: source.detailPath,
      }
    })
    .filter((item): item is NonNullable<typeof item> => item !== null)
})

/** 环图与进度条只使用工作台已给出的真实口径。 */
const budgetChart = computed(() => {
  const approved = Number(card('expense-approved')?.value ?? '0')
  const balance = Number(card('expense-balance')?.value ?? '0')
  const budget = approved + balance
  if (!Number.isFinite(approved) || !Number.isFinite(balance) || budget <= 0) {
    return null
  }
  const actualRatio = approved / budget
  const ratio = Math.min(1, Math.max(0, actualRatio))
  return {
    approved: approved.toFixed(2),
    balance: balance.toFixed(2),
    budget: budget.toFixed(2),
    percent: `${(actualRatio * 100).toFixed(1)}%`,
    dash: `${(ratio * 100).toFixed(1)} ${(100 - ratio * 100).toFixed(1)}`,
  }
})

function percentOf(key: string): number | null {
  const value = card(key)?.value ?? ''
  if (!value.endsWith('%')) {
    return null
  }
  const parsed = Number(value.slice(0, -1))
  return Number.isFinite(parsed) ? parsed : null
}

const rates = computed(() => [
  { key: 'tasks-completion', label: '任务完成率', value: percentOf('tasks-completion'), hint: card('tasks-completion')?.hint ?? '' },
  { key: 'registrations-capacity', label: '名额使用率', value: percentOf('registrations-capacity'), hint: card('registrations-capacity')?.hint ?? '' },
])

const kpiKeys = computed(() => new Set(kpis.value.map((item) => item.key)))
/** 明细只列未进入 KPI 带的指标，避免同一数字重复出现。 */
const details = computed(() => {
  const all = [...(workbench.value?.cards ?? []), ...(workbench.value?.myCards ?? [])]
  const kept = all.filter((item) => !kpiKeys.value.has(item.key)
    && item.key !== 'expense-approved' && item.key !== 'expense-balance'
    && item.key !== 'tasks-completion' && item.key !== 'registrations-capacity')
  return kept
})
const todos = computed(() => workbench.value?.todos ?? [])

async function load(): Promise<void> {
  if (!orgId.value) {
    return
  }
  const seq = ++requestSeq
  const gen = session.capture()
  loading.value = true
  errorText.value = ''
  try {
    const [workbenchData, activityPage, config] = await Promise.all([
      fetchWorkbench(orgId.value),
      fetchActivities(orgId.value, { pageSize: 50 }),
      fetchAiConfig(orgId.value).catch(() => null),
    ])
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    workbench.value = workbenchData
    activities.value = activityPage.items
    aiConfig.value = config
  } catch (error) {
    if (seq !== requestSeq || gen !== session.generation) {
      return
    }
    workbench.value = null
    activities.value = []
    if (session.observeUnauthorized(error, gen)) {
      return
    }
    errorText.value = readErrorMessage(error, '工作台数据加载失败，请重试。')
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

watch(orgId, () => {
  workbench.value = null
  activities.value = []
  aiConfig.value = null
  void load()
})
onMounted(load)

function open(path: string) {
  if (path) {
    void router.push(path)
  }
}

/** R02：跳转可携带目标页签，详情页会按当前状态与权限校验后选中（无效则回到概览）。 */
function openActivity(activity: ActivityItem | null, tab?: 'proposal' | 'lifecycle') {
  if (activity) {
    const query = tab ? `?tab=${tab}` : ''
    void router.push(`/org/${orgId.value}/activities/${activity.id}${query}`)
  }
}

function openAiEditor() {
  if (aiCandidate.value) {
    openActivity(aiCandidate.value, aiMode.value === '总结' ? 'lifecycle' : 'proposal')
  } else {
    const create = aiMode.value === '方案' && workbench.value?.role === 'OWNER'
    open(`/org/${orgId.value}/activities${create ? '?create=1' : ''}`)
  }
}

defineExpose({ reload: load })
</script>

<template>
  <section
    class="workbench"
    aria-label="组织工作台"
  >
    <p
      v-if="errorText"
      class="workbench__error"
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

    <!-- 骨架保持与待办、重点活动、KPI、辅助区相同的排列。 -->
    <section
      v-else-if="loading && !workbench"
      class="workbench__skeleton"
      role="status"
      aria-busy="true"
      aria-label="正在加载组织工作台"
    >
      <ElSkeleton
        animated
        :rows="0"
      >
        <template #template>
          <div class="skeleton-focus">
            <ElSkeletonItem
              variant="rect"
              data-skeleton-block="todos"
            />
            <ElSkeletonItem variant="rect" />
          </div>
          <div class="skeleton-kpis">
            <ElSkeletonItem
              v-for="n in 4"
              :key="n"
              variant="rect"
            />
          </div>
          <div class="skeleton-panels">
            <div
              class="skeleton-panel skeleton-panel--chart"
              data-skeleton-block="chart"
            >
              <div class="skeleton-panel__head">
                <ElSkeletonItem variant="text" />
                <ElSkeletonItem variant="text" />
              </div>
              <div class="skeleton-panel__chart">
                <ElSkeletonItem variant="circle" />
                <div class="skeleton-panel__legend">
                  <ElSkeletonItem
                    v-for="n in 3"
                    :key="n"
                    variant="text"
                  />
                </div>
              </div>
              <!-- 真实图表面板下方还有两条比率进度条，缺了就会少一大截高度 -->
              <div class="skeleton-panel__rates">
                <ElSkeletonItem
                  v-for="n in 2"
                  :key="n"
                  variant="rect"
                />
              </div>
            </div>
            <div
              class="skeleton-panel"
              data-skeleton-block="assistant"
            >
              <div class="skeleton-panel__head">
                <ElSkeletonItem variant="text" />
                <ElSkeletonItem variant="text" />
              </div>
              <ElSkeletonItem variant="rect" />
            </div>
            <div
              class="skeleton-panel skeleton-panel--wide"
              data-skeleton-block="details"
            >
              <div class="skeleton-panel__head">
                <ElSkeletonItem variant="text" />
                <ElSkeletonItem variant="text" />
              </div>
              <!-- 指标明细按真实条目数占位（当前 8 条） -->
              <ElSkeletonItem
                v-for="n in 8"
                :key="n"
                variant="rect"
              />
            </div>
          </div>
        </template>
      </ElSkeleton>
    </section>

    <template v-else-if="workbench">
      <p
        v-if="loading"
        class="panel__note"
        role="status"
      >
        正在更新工作台…
      </p>
      <!-- 先处理待办，再查看重点活动；AI 入口下移到辅助区。 -->
      <div
        class="focus-board"
        :class="{ 'focus-board--member': !isManager, 'focus-board--quiet': !focusActivity }"
      >
        <section
          class="panel panel--todos"
          :class="{ 'panel--quiet': todos.length === 0 }"
          data-todo-panel
        >
          <div class="panel__head">
            <h2><CircleCheck />待我处理</h2>
            <span class="panel__tag">当前显示 {{ todos.length }} 项</span>
          </div>
          <ul
            v-if="todos.length > 0"
            class="todo-list"
          >
            <li
              v-for="todo in todos"
              :key="`${todo.kind}-${todo.title}`"
              class="todo-list__item"
            >
              <div>
                <ElTag size="small">
                  {{ todoKindLabel[todo.kind] ?? todo.kind }}
                </ElTag>
                <strong>{{ todo.title }}</strong>
                <p>{{ todo.detail }}</p>
              </div>
              <ElButton
                size="small"
                @click="open(todo.path)"
              >
                处理
              </ElButton>
            </li>
          </ul>
          <p
            v-else
            class="panel__empty"
          >
            <StatusMark
              state="done"
              label="暂无待处理事项，可以安排下一步。"
            />
          </p>
        </section>
        <SpotlightCard
          v-if="focusActivity"
          class="spotlight"
          data-focus-activity
        >
          <div class="spotlight__top">
            <span class="spotlight__eyebrow">
              <i aria-hidden="true" />重点活动
              <span class="spotlight__state">{{ focusStage }}</span>
            </span>
            <span class="spotlight__aside">活动进展</span>
          </div>
          <div class="spotlight__title">
            <div>
              <p class="spotlight__slogan">
                当前重点
              </p>
              <h2>{{ focusActivity.name }}</h2>
              <span class="spotlight__desc">{{ focusActivity.description || '暂无活动说明' }}</span>
            </div>
            <div
              v-if="focusDate"
              class="spotlight__date"
            >
              {{ focusDate.slice(8) }}<small>{{ focusDate.slice(5, 7) }} 月</small>
            </div>
          </div>
          <ol
            class="stage-rail"
            aria-label="重点活动阶段"
          >
            <li
              v-for="(stage, index) in stages"
              :key="stage"
              :class="{
                reached: index < stages.indexOf(focusStage),
                current: stage === focusStage,
              }"
            >
              <span>
                <Check v-if="index < stages.indexOf(focusStage)" />
                <template v-else>
                  {{ index + 1 }}
                </template>
              </span>{{ stage }}
            </li>
          </ol>
          <div class="spotlight__footer">
            <div class="spotlight__meta">
              <Calendar />
              <span>{{ focusDate || '时间待定' }}<small>{{ focusActivity.location || '地点待定' }}</small></span>
            </div>
            <div class="spotlight__meta">
              <Location />
              <span>名额 {{ focusActivity.capacity ?? '未设置' }}<small>负责人 {{ focusActivity.ownerUsername }}</small></span>
            </div>
            <ElButton
              data-focus-open
              @click="openActivity(focusActivity)"
            >
              查看活动<ArrowRight />
            </ElButton>
          </div>
        </SpotlightCard>
        <article
          v-else
          class="focus-guide"
          data-focus-activity
        >
          <div>
            <h2>下一场活动，从这里开始</h2>
            <p>暂无筹备或执行中的活动。查看已有草稿，或为组织安排新的活动。</p>
          </div>
          <ElButton @click="open(`/org/${orgId}/activities`)">
            前往活动管理
          </ElButton>
        </article>
      </div>

      <!-- 2. 精简 KPI 带 -->
      <div
        v-if="kpis.length > 0"
        class="kpi-grid"
        data-kpi-band
      >
        <button
          v-for="kpi in kpis"
          :key="kpi.key"
          type="button"
          class="kpi-card"
          :data-kpi="kpi.key"
          @click="open(kpi.path)"
        >
          <span class="kpi-card__top">
            <span class="kpi-card__icon"><component :is="kpi.icon" /></span>
            <ArrowRight class="kpi-card__arrow" />
          </span>
          <span class="kpi-card__label">{{ kpi.label }}</span>
          <span class="kpi-card__value">
            <CountUp
              v-if="kpi.unit !== '元'"
              :value="String(kpi.value)"
              :scope-key="`${orgId}-${kpi.key}`"
            /><template v-else>{{ kpi.value }}</template><small>{{ kpi.unit }}</small>
          </span>
          <span
            class="kpi-card__note"
            :title="kpi.hint"
          >{{ kpi.hint }}</span>
        </button>
      </div>

      <!-- 3. 图表：只画真实口径 -->
      <div class="content-grid">
        <section
          v-if="isManager"
          class="panel panel--chart"
          data-chart-panel
        >
          <div class="panel__head">
            <h2><TrendCharts />经费结构</h2>
            <span class="panel__tag">已确认支出占预算</span>
          </div>
          <div
            v-if="budgetChart"
            class="budget-chart"
          >
            <div class="budget-chart__visual">
              <svg
                class="budget-chart__ring"
                viewBox="0 0 42 42"
                role="img"
                :aria-label="`已确认支出 ${budgetChart.approved} 元，占预算 ${budgetChart.percent}`"
              >
                <circle
                  class="budget-chart__track"
                  cx="21"
                  cy="21"
                  r="15.9155"
                />
                <circle
                  class="budget-chart__value"
                  cx="21"
                  cy="21"
                  r="15.9155"
                  :stroke-dasharray="budgetChart.dash"
                />
              </svg>
              <span
                class="budget-chart__label"
                aria-hidden="true"
              >{{ budgetChart.percent }}<small>支出占预算</small></span>
            </div>
            <dl class="budget-chart__legend">
              <div>
                <dt>已确认支出</dt>
                <dd>{{ budgetChart.approved }} 元</dd>
              </div>
              <div>
                <dt>预算余额</dt>
                <dd>{{ budgetChart.balance }} 元</dd>
              </div>
              <div>
                <dt>预算合计</dt>
                <dd>{{ budgetChart.budget }} 元</dd>
              </div>
            </dl>
          </div>
          <p
            v-else
            class="panel__empty"
            data-chart-empty
          >
            暂无已立项活动的预算与支出记录，暂不展示经费图表。
          </p>
          <div
            v-if="rates.some((rate) => rate.value !== null)"
            class="rate-bars"
          >
            <div
              v-for="rate in rates"
              :key="rate.key"
              class="rate-bar"
            >
              <div class="rate-bar__head">
                <span>{{ rate.label }}</span>
                <strong>{{ rate.value === null ? '—' : `${rate.value}%` }}</strong>
              </div>
              <div
                class="rate-bar__track"
                role="img"
                :aria-label="`${rate.label} ${rate.value === null ? '不适用' : `${rate.value}%`}`"
              >
                <i :style="{ width: `${rate.value ?? 0}%` }" />
              </div>
              <p class="rate-bar__note">
                {{ rate.hint }}
              </p>
            </div>
          </div>
          <p class="panel__note">
            预算余额为预算减已确认支出，不代表账户余额。
          </p>
        </section>

        <aside
          v-if="isManager"
          class="assistant-card"
          :class="{ 'assistant-card--quiet': !aiCandidate }"
          data-ai-card
        >
          <header class="assistant-card__head">
            <span class="assistant-card__mark"><Document /></span>
            <div>
              <h2>AI 文稿助手</h2>
              <p>把活动记录，整理成下一步。</p>
            </div>
            <span class="assistant-card__label">AI</span>
          </header>
          <RubberSegment
            :model-value="aiMode"
            :items="[{ value: '总结', label: '活动总结' }, { value: '方案', label: '活动方案' }]"
            label="AI 文稿类型"
            @update:model-value="aiMode = $event as '总结' | '方案'"
          />
          <div class="assistant-card__paper">
            <span>{{ aiMode === '总结' ? '复盘，从已有记录开始' : '让想法，变成可讨论的方案' }}</span>
            <h3>{{ aiCandidate?.name ?? (aiMode === '总结' ? '暂无复盘中的活动' : '先创建一份活动草稿') }}</h3>
            <p>
              {{ !aiCandidate
                ? aiMode === '总结' ? '活动进入复盘阶段后，可在这里编写总结或使用 AI 生成草稿。' : '先创建并保存活动草稿，再进入方案编辑使用 AI。活动草稿由组织负责人创建。'
                : aiMode === '总结'
                  ? '汇总任务、报名与经费，补充真实反馈，形成可编辑的总结草稿。'
                  : '围绕活动目标整理流程、分工与注意事项，待确认的信息清楚标出。' }}
            </p>
          </div>
          <div class="assistant-card__bottom">
            <ElButton
              data-ai-open
              @click="openAiEditor"
            >
              {{ aiOpenLabel }}<ArrowRight />
            </ElButton>
            <small>{{ aiConfig?.availabilityHint ?? '模型状态暂不可用，可前往编辑区手工编写' }}</small>
            <ElButton
              v-if="!aiAvailable && workbench.role === 'OWNER'"
              link
              data-ai-settings
              @click="open(`/org/${orgId}/settings`)"
            >
              前往模型设置
            </ElButton>
          </div>
        </aside>
        <section
          class="panel panel--wide"
          data-detail-panel
        >
          <div class="panel__head">
            <h2>指标明细</h2>
            <span class="panel__tag">{{ workbenchRoleLabel[workbench.role] }}</span>
            <ElButton
              size="small"
              link
              data-detail-hints-toggle
              @click="showHints = !showHints"
            >
              {{ showHints ? '收起口径说明' : '显示口径说明' }}
            </ElButton>
          </div>
          <ul
            v-if="details.length > 0"
            class="detail-list"
          >
            <li
              v-for="item in details"
              :key="item.key"
              class="detail-list__item"
            >
              <div class="detail-list__main">
                <span class="detail-list__label">{{ item.label }}</span>
                <span class="detail-list__value">{{ item.value }}</span>
              </div>
              <p
                v-if="showHints"
                class="detail-list__hint"
              >
                {{ item.hint }}
              </p>
              <ElButton
                v-if="item.detailPath"
                size="small"
                link
                @click="open(item.detailPath)"
              >
                {{ item.detailLabel || '查看明细' }}
              </ElButton>
            </li>
          </ul>
          <p
            v-else
            class="panel__empty"
          >
            当前身份没有额外的管理指标。
          </p>
        </section>
      </div>
    </template>
  </section>
</template>

<style scoped>

.workbench {
  display: flex;
  flex-direction: column;
  gap: var(--ui-layout-grid-gap);
  margin-top: var(--ui-space-6);
}

.workbench__error {
  margin: 0;
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.workbench__skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--ui-layout-grid-gap);
}

.skeleton-focus {
  display: grid;
  grid-template-columns: var(--ui-layout-focus-columns);
  gap: var(--ui-layout-grid-gap);
}

/* 与真实重点卡同高：用工作台自己的最小高度 token，不用卡片最小宽度凑数。 */
.skeleton-focus :deep(.el-skeleton__item) {
  height: var(--ui-workbench-focus-min-height);
  border-radius: var(--ui-radius-card);
}

.skeleton-kpis {
  display: grid;
  grid-template-columns: var(--ui-layout-kpi-columns);
  gap: var(--ui-layout-grid-gap);
}

/* KPI 占位沿用真实卡片的表面，避免出现四条没有边框的空灰块。 */
.skeleton-kpis :deep(.el-skeleton__item) {
  height: var(--ui-workbench-kpi-min-height);
  border-radius: var(--ui-radius-card);
  border: var(--ui-border-width) solid var(--ui-color-glass-edge);
  background: var(--ui-surface-glass);
  box-shadow: var(--ui-shadow-glass);
}

/* 分析区与明细区必须一起出现，否则整块塌成一条灰条。 */
.skeleton-panels {
  display: grid;
  grid-template-columns: var(--ui-layout-analysis-columns);
  gap: var(--ui-layout-grid-gap);
  align-items: start;
}

.skeleton-panel {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
  box-shadow: var(--ui-shadow-glass);
}

.skeleton-panel--wide {
  grid-column: 1 / -1;
}

.skeleton-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-4);
}

.skeleton-panel__head :deep(.el-skeleton__item) {
  width: 30%;
  height: var(--ui-icon);
}

.skeleton-panel__head :deep(.el-skeleton__item:last-child) {
  width: 18%;
}

.skeleton-panel__chart {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ui-space-6);
}

/* 圆环占位按真实环图视觉区尺寸，避免整块高度少一截（实测约 220px）。 */
.skeleton-panel__chart :deep(.el-skeleton__item) {
  flex-shrink: 0;
  width: var(--ui-chart-height);
  height: var(--ui-chart-height);
}

.skeleton-panel__legend {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
  flex: 1;
  min-width: 0;
}

.skeleton-panel__legend :deep(.el-skeleton__item) {
  width: 60%;
  height: var(--ui-icon-small);
}

/* 两条比率进度条占位：与真实 .rate-bars 同量级高度。 */
.skeleton-panel__rates {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
}

.skeleton-panel__rates :deep(.el-skeleton__item) {
  width: 100%;
  height: var(--ui-icon);
}

/* 明细与待办列表行：与真实条目的最小高度一致，高度随行数自然累积。 */
.skeleton-panel > :deep(.el-skeleton__item:not(.el-skeleton__item--text)) {
  width: 100%;
  min-height: var(--ui-table-row-height);
  border-radius: var(--ui-radius-control);
}

/* ---------- 重点活动 + AI 双卡 ---------- */

.focus-board {
  display: grid;
  grid-template-columns: var(--ui-layout-focus-columns);
  gap: var(--ui-layout-grid-gap);
  align-items: start;
}

.spotlight {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  padding: var(--ui-layout-card-padding);
  border-radius: var(--ui-radius-card);
  background: var(--ui-surface-spotlight);
  color: var(--ui-color-on-dark);
  box-shadow: var(--ui-shadow-spotlight);
  transition: transform var(--ui-motion-base) var(--ui-motion-ease);
}


.spotlight__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-caption);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
}

.spotlight__eyebrow {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
}

.spotlight__state {
  padding: 0 var(--ui-space-2);
  border: var(--ui-border-width) solid var(--ui-color-border-dark);
  border-radius: var(--ui-radius-pill);
  letter-spacing: normal;
}

.spotlight__slogan {
  margin: 0;
  color: var(--ui-color-on-dark-accent);
  font-size: var(--ui-font-size-small);
}

.spotlight__title h2 {
  margin: var(--ui-space-2) 0;
  font-size: var(--ui-font-size-kpi);
  line-height: var(--ui-line-height-tight);
  letter-spacing: var(--ui-letter-spacing-title);
}

.spotlight__desc {
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-body);
}

.spotlight__date small {
  font-size: var(--ui-font-size-caption);
  color: var(--ui-color-on-dark-muted);
}

.stage-rail li {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: var(--ui-space-2);
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-caption);
}

.stage-rail li > span {
  display: grid;
  place-items: center;
  width: var(--ui-icon-large);
  height: var(--ui-icon-large);
  border: var(--ui-border-width) solid var(--ui-color-border-dark);
  border-radius: var(--ui-radius-circle);
  font-family: var(--ui-font-family-data);
}

.spotlight__meta {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-small);
}

.spotlight__meta span {
  display: flex;
  flex-direction: column;
}

.spotlight__meta small {
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-caption);
}

.assistant-card__head {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
}

.assistant-card__mark {
  display: grid;
  place-items: center;
  width: var(--ui-icon-large);
  height: var(--ui-icon-large);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-ai-end);
  color: var(--ui-color-ai-text);
}

.assistant-card__head h2 {
  margin: 0;
  font-size: var(--ui-font-size-emphasis);
  color: var(--ui-color-ai-text);
}

.assistant-card__head p {
  margin: 0;
  color: var(--ui-color-ai-muted);
  font-size: var(--ui-font-size-caption);
}

.assistant-card__label {
  margin-left: auto;
  padding: 0 var(--ui-space-2);
  border: var(--ui-border-width) solid var(--ui-color-ai-border);
  border-radius: var(--ui-radius-pill);
  color: var(--ui-color-ai-text);
  font-size: var(--ui-font-size-caption);
}


.assistant-card__paper span {
  color: var(--ui-color-ai-muted);
  font-size: var(--ui-font-size-caption);
}

.assistant-card__paper h3 {
  margin: 0;
  font-size: var(--ui-font-size-emphasis);
  color: var(--ui-color-text);
}

.assistant-card__paper p {
  margin: 0;
  color: var(--ui-color-text-secondary);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-body);
}


/* ---------- KPI 带 ---------- */

.kpi-grid {
  display: grid;
  grid-template-columns: var(--ui-layout-kpi-columns);
  gap: var(--ui-layout-grid-gap);
}

.kpi-card:hover {
  border-color: var(--ui-color-accent);
  box-shadow: var(--ui-shadow-hover);
}

.kpi-card__top {
  position: absolute;
  top: var(--ui-space-4);
  right: var(--ui-space-4);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.kpi-card__icon {
  display: grid;
  place-items: center;
  width: var(--ui-icon-large);
  height: var(--ui-icon-large);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-surface-muted);
  color: var(--ui-color-text-secondary);
}

.kpi-card__arrow {
  display: none;
  width: var(--ui-icon-small);
  height: var(--ui-icon-small);
  color: var(--ui-color-text-muted);
  transition: transform var(--ui-motion-base) var(--ui-motion-ease);
}

.kpi-card:hover .kpi-card__arrow {
  display: none;
  transform: translateX(var(--ui-space-1));
  color: var(--ui-color-accent);
}

.kpi-card__label {
  padding-right: var(--ui-space-8);
  color: var(--ui-color-text-secondary);
  font-size: var(--ui-font-size-small);
}

.kpi-card__value {
  display: flex;
  align-items: baseline;
  gap: var(--ui-space-2);
  font-family: var(--ui-font-family-data);
  font-size: var(--ui-font-size-kpi);
  font-weight: var(--ui-font-weight-medium);
  letter-spacing: var(--ui-letter-spacing-title);
  font-variant-numeric: tabular-nums;
}

.kpi-card__value small {
  color: var(--ui-color-text-muted);
  font-family: var(--ui-font-family-body);
  font-size: var(--ui-font-size-caption);
}

.kpi-card__note {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
  line-height: var(--ui-line-height-body);
}

/* ---------- 图表与明细 ---------- */

.content-grid {
  display: grid;
  grid-template-columns: var(--ui-layout-analysis-columns);
  gap: var(--ui-layout-grid-gap);
  align-items: start;
}

.panel {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-color-surface);
  box-shadow: var(--ui-shadow-glass);
}

.panel__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-2);
}

.panel__head h2 {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  margin: 0;
  font-size: var(--ui-font-size-emphasis);
  white-space: nowrap;
}

.panel__tag {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.panel__note,
.panel__empty {
  margin: 0;
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
  line-height: var(--ui-line-height-body);
}

.budget-chart {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ui-space-6);
}

.budget-chart__ring {
  width: var(--ui-chart-height);
  height: var(--ui-chart-height);
  transform: rotate(-90deg);
}

.budget-chart__track {
  fill: none;
  stroke: var(--ui-color-chart-track);
  stroke-width: var(--ui-chart-stroke);
}

.budget-chart__value {
  fill: none;
  stroke: var(--ui-color-chart-primary);
  stroke-width: var(--ui-chart-stroke);
  stroke-linecap: round;
}

/* 环图中心显示真实占比（不只放在 aria-label 里） */

.budget-chart__visual {
  position: relative;
  flex-shrink: 0;
}

.budget-chart__label {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  font-family: var(--ui-font-family-data);
  font-size: var(--ui-font-size-kpi);
  font-variant-numeric: tabular-nums;
}

.budget-chart__label small {
  font-family: var(--ui-font-family-body);
  font-size: var(--ui-font-size-caption);
  color: var(--ui-color-text-muted);
}


/* 图表与待办同排，明细整行放在下方，避免左侧出现大块空白 */

.panel--wide {
  grid-column: 1 / -1;
}

.budget-chart__legend {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
  margin: 0;
}

.budget-chart__legend dt {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.budget-chart__legend dd {
  margin: 0;
  font-family: var(--ui-font-family-data);
  font-size: var(--ui-font-size-emphasis);
  font-variant-numeric: tabular-nums;
}

.rate-bars {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
}

.rate-bar__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: var(--ui-font-size-small);
  color: var(--ui-color-text-secondary);
}

.rate-bar__head strong {
  font-family: var(--ui-font-family-data);
  color: var(--ui-color-text);
}

.rate-bar__track {
  height: var(--ui-icon-small);
  border-radius: var(--ui-radius-pill);
  background: var(--ui-color-chart-track);
  overflow: hidden;
}

.rate-bar__track i {
  display: block;
  height: 100%;
  background: var(--ui-color-chart-secondary);
}

.rate-bar__note {
  margin: var(--ui-space-1) 0 0;
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

.detail-list,
.todo-list {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.detail-list__item {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--ui-space-3);
  padding-bottom: var(--ui-space-3);
  border-bottom: var(--ui-border-width) solid var(--ui-color-border);
}

.detail-list__item:last-child {
  padding-bottom: 0;
  border-bottom: none;
}

.detail-list__main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ui-space-3);
}

.detail-list__label {
  color: var(--ui-color-text-secondary);
  font-size: var(--ui-font-size-small);
}

.detail-list__value {
  font-family: var(--ui-font-family-data);
  font-size: var(--ui-font-size-emphasis);
  font-variant-numeric: tabular-nums;
}

.detail-list__hint {
  order: 1;
  flex-basis: 100%;
  margin: 0;
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
  line-height: var(--ui-line-height-body);
}

.todo-list__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  padding-bottom: var(--ui-space-3);
  border-bottom: var(--ui-border-width) solid var(--ui-color-border);
}

.todo-list__item:last-child {
  padding-bottom: 0;
  border-bottom: none;
}

.todo-list__item > div {
  min-width: 0;
  overflow-wrap: anywhere;
}

.todo-list__item .el-button {
  flex-shrink: 0;
}

.panel--todos {
  border-top: var(--ui-space-1) solid var(--ui-color-accent);
}

.todo-list__item strong {
  display: block;
  margin-top: var(--ui-space-1);
}

.todo-list__item p {
  margin: var(--ui-space-1) 0 0;
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
}

/* 微交互：精确指针悬停上浮，减少动效偏好下取消（不依赖动画库） */
@media (hover: hover) and (pointer: fine) {

.spotlight:hover,
  .assistant-card:hover,
  .kpi-card:hover {
  transform: translateY(var(--ui-motion-lift));
}
}

.workbench svg:not(.budget-chart__ring) {
  width: var(--ui-icon);
  height: var(--ui-icon);
  flex-shrink: 0;
}

.spotlight {
  min-height: var(--ui-workbench-focus-min-height);
  border: var(--ui-border-width) solid var(--ui-color-glass-edge-dark);
}

.spotlight__title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ui-space-4);
  margin: var(--ui-space-4) 0;
}

.spotlight__title > div:first-child {
  min-width: 0;
  overflow-wrap: anywhere;
}

.spotlight__date {
  display: flex;
  flex-direction: column;
  align-items: center;
  font-family: var(--ui-font-family-data);
  line-height: var(--ui-line-height-tight);
  font-size: var(--ui-font-size-brand);
  color: var(--ui-color-on-dark-accent);
  font-weight: var(--ui-font-weight-regular);
}

.spotlight__eyebrow i {
  border-radius: var(--ui-radius-circle);
  width: var(--ui-space-2);
  height: var(--ui-space-2);
  background: var(--ui-color-on-dark-accent);
}

.stage-rail {
  display: flex;
  margin: 0;
  list-style: none;
  padding: var(--ui-space-4) 0;
  gap: 0;
}

.stage-rail li:not(:last-child)::after {
  content: '';
  position: absolute;
  top: calc(var(--ui-icon-large) / 2);
  left: calc(50% + var(--ui-icon-large) / 2);
  width: calc(100% - var(--ui-icon-large));
  height: var(--ui-border-width);
  background: var(--ui-color-border-dark);
}

.stage-rail li.current {
  color: var(--ui-color-on-dark-accent);
}

.stage-rail li.current > span {
  background: var(--ui-color-on-dark-accent);
  border-color: var(--ui-color-on-dark-accent);
  color: var(--ui-color-text);
}

.stage-rail li.reached > span {
  border-color: var(--ui-color-on-dark-accent);
  color: var(--ui-color-on-dark-accent);
}

.spotlight__footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ui-space-4);
  margin-top: auto;
  padding-top: var(--ui-space-5);
  border-top: var(--ui-border-width) solid var(--ui-color-border-dark);
  justify-content: space-between;
}

.spotlight__footer .el-button {
  color: var(--ui-color-on-dark-accent);
  background: var(--ui-color-glass-on-dark);
  border-color: var(--ui-color-border-dark);
}

.assistant-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-ai-border);
  border-radius: var(--ui-radius-card);
  background: var(--ui-surface-ai);
  box-shadow: var(--ui-shadow-glass);
  transition: transform var(--ui-motion-base) var(--ui-motion-ease);
  border-color: var(--ui-color-ai-border);
}



.assistant-card__paper {
  display: flex;
  flex-direction: column;
  border-radius: var(--ui-radius-control);
  background: var(--ui-surface-glass);
  padding: var(--ui-space-5);
  border: var(--ui-border-width) solid var(--ui-color-glass-edge);
  gap: var(--ui-space-2);
}

.assistant-card__bottom {
  display: flex;
  flex-wrap: wrap;
  margin-top: auto;
  flex-direction: column;
  align-items: start;
  gap: var(--ui-space-2);
}

.assistant-card__bottom .el-button {
  width: 100%;
  background: var(--ui-color-ai-start);
  border-color: var(--ui-color-ai-border);
  color: var(--ui-color-ai-text);
}

.assistant-card__bottom small {
  color: var(--ui-color-ai-muted);
  font-size: var(--ui-font-size-caption);
  text-align: center;
}

.kpi-card {
  position: relative;
  min-width: 0;
  min-height: var(--ui-workbench-kpi-min-height);
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
  padding: var(--ui-space-4) var(--ui-space-5);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-card);
  color: var(--ui-color-text);
  text-align: left;
  cursor: pointer;
  background: var(--ui-surface-glass);
  border-color: var(--ui-color-glass-edge);
  transition: transform var(--ui-motion-base) var(--ui-motion-ease), box-shadow var(--ui-motion-base) var(--ui-motion-ease);
}

.skeleton-kpis :deep(.el-skeleton__item) {
  border-radius: var(--ui-radius-card);
  height: var(--ui-workbench-kpi-min-height);
}

@media (prefers-reduced-motion: reduce) {

.spotlight,
  .assistant-card,
  .kpi-card,
  .kpi-card__arrow {
  transition: none;
}

.spotlight:hover,
  .assistant-card:hover,
  .kpi-card:hover {
  transform: none;
}
}



.focus-board--member, .focus-board--quiet { grid-template-columns: minmax(0, 1fr); }
.focus-guide { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--ui-space-4); padding: var(--ui-layout-card-padding); border: var(--ui-border-width) solid var(--ui-color-border); border-radius: var(--ui-radius-card); background: var(--ui-color-surface); }
.focus-guide h2 { font-size: var(--ui-font-size-section); }
.focus-guide p { margin-top: var(--ui-space-2); color: var(--ui-color-text-secondary); }
.panel--quiet { align-self: start; }
.assistant-card--quiet { background: var(--ui-color-surface); }
.assistant-card--quiet .assistant-card__paper { padding: 0; border: none; background: transparent; }
.assistant-card--quiet .assistant-card__paper > span { display: none; }

</style>
