<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Calendar, CircleCheck, Tickets, Wallet, ArrowRight, TrendCharts } from '@element-plus/icons-vue'
import { gsap } from 'gsap'
import { approvalHistory, type Activity } from './demo'

const props = defineProps<{ activities: Activity[]; orgId: string; pendingTasks: number }>()
const emit = defineEmits<{ focus: [target: 'active' | 'tasks' | 'expenses' | 'registrations'] }>()
const root = ref<HTMLElement>()
const period = ref(7)
const selectedIndex = ref(6)
const approvedTotal = computed(() => props.activities.reduce((total, activity) => total + activity.approved, 0))
const activeCount = computed(() => props.activities.filter(activity => ['筹备中', '执行中'].includes(activity.status)).length)
const confirmedActivities = computed(() => props.activities.filter(activity => !['草稿', '待确认'].includes(activity.status)))
const cents = (value: string) => Math.round(Number(value) * 100)
const expenseCents = computed(() => confirmedActivities.value.reduce((total, activity) => total + cents(activity.expense), 0))
const budgetCents = computed(() => confirmedActivities.value.reduce((total, activity) => total + cents(activity.budget), 0))
const budgetPercent = computed(() => budgetCents.value ? Math.round(expenseCents.value / budgetCents.value * 100) : 0)
const money = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(value / 100)
const stats = computed(() => [
  { label: '进行中活动', value: activeCount.value, unit: '场', note: '筹备中与执行中', icon: Calendar, target: 'active' as const },
  { label: '已批准报名', value: approvedTotal.value, unit: '人次', note: '按活动累计，非到场人数', icon: Tickets, target: 'registrations' as const },
  { label: '我的待处理任务', value: props.pendingTasks, unit: '项', note: '当前组织 · 分配给我', icon: CircleCheck, target: 'tasks' as const },
  { label: '已确认支出', value: expenseCents.value / 100, unit: '元', note: `已立项活动预算 ${money(budgetCents.value)}`, icon: Wallet, target: 'expenses' as const },
])
const daily = computed(() => {
  const ids = new Set(props.activities.map(activity => activity.id))
  const totals = new Map<string, number>()
  approvalHistory.filter(item => ids.has(item.activityId)).forEach(item => totals.set(item.date, (totals.get(item.date) || 0) + item.count))
  return Array.from({ length: period.value }, (_, index) => {
    const date = new Date(Date.UTC(2026, 8, 12 - period.value + 1 + index)).toISOString().slice(0, 10)
    return { date, value: totals.get(date) || 0 }
  })
})
const maxY = computed(() => Math.max(4, Math.ceil(Math.max(...daily.value.map(item => item.value)) / 4) * 4))
const points = computed(() => daily.value.map((item, index) => ({ ...item, x: 38 + index / (daily.value.length - 1) * 574, y: 176 - item.value / maxY.value * 140 })))
const line = computed(() => points.value.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' '))
const area = computed(() => `${line.value} L 612 176 L 38 176 Z`)
const currentPoint = computed(() => points.value[selectedIndex.value] || points.value.at(-1)!)
const periodTotal = computed(() => daily.value.reduce((sum, item) => sum + item.value, 0))
const xLabels = computed(() => [0, Math.floor((period.value - 1) / 2), period.value - 1])
let media: gsap.MatchMedia | undefined

function animate(revealCards = true) {
  media?.revert()
  if (!root.value) return
  media = gsap.matchMedia()
  media.add('(prefers-reduced-motion: no-preference)', () => {
    if (revealCards) {
      gsap.from('.kpi-card', { autoAlpha: 0, y: 18, duration: .65, stagger: .075, ease: 'power3.out', clearProps: 'all' })
      gsap.from('.analysis-card', { autoAlpha: 0, y: 20, duration: .7, delay: .15, stagger: .08, ease: 'power3.out', clearProps: 'all' })
      root.value!.querySelectorAll<HTMLElement>('[data-count]').forEach(element => {
      const target = Number(element.dataset.count)
      const counter = { value: 0 }
      gsap.to(counter, { value: target, duration: .95, delay: .1, ease: 'power2.out', onUpdate: () => { element.textContent = Math.round(counter.value).toLocaleString('zh-CN') }, onComplete: () => { element.textContent = target.toLocaleString('zh-CN') } })
      })
    }
    gsap.from('.approval-line', { strokeDashoffset: 1, duration: 1.05, delay: .25, ease: 'power2.inOut' })
    gsap.from('.approval-area', { opacity: 0, duration: 1, delay: .3 })
    if (revealCards) gsap.from('.budget-arc', { strokeDasharray: '0 100', duration: 1.1, delay: .2, ease: 'power3.out' })
    // 提前切换组织或启用减少动效时，恢复准确数字，避免残留中间帧。
    return () => root.value?.querySelectorAll<HTMLElement>('[data-count]').forEach(element => { element.textContent = Number(element.dataset.count).toLocaleString('zh-CN') })
  }, root.value)
}
watch([() => props.orgId, period], () => { selectedIndex.value = period.value - 1 })
watch(() => props.activities, () => animate(), { flush: 'post' })
watch(period, () => animate(false), { flush: 'post' })
onMounted(() => animate())
onBeforeUnmount(() => media?.revert())
</script>

<template>
  <section ref="root" class="overview" aria-label="组织数据概览">
    <div class="overview-caption"><span>组织概况</span><span>截至 2026.09.12 · 示例快照</span></div>
    <div class="kpi-grid">
      <button v-for="(stat, index) in stats" :key="stat.target" class="kpi-card" :class="{ 'kpi-featured': index === 0 }" @click="emit('focus', stat.target)">
        <span class="kpi-top"><span class="kpi-icon"><component :is="stat.icon" /></span><ArrowRight class="kpi-arrow" /></span>
        <span class="kpi-label">{{ stat.label }}</span>
        <span class="kpi-value" :aria-label="`${stat.label} ${stat.value} ${stat.unit}`"><span aria-hidden="true" :data-count="stat.value">{{ stat.value.toLocaleString('zh-CN') }}</span><small aria-hidden="true">{{ stat.unit }}</small></span>
        <span class="kpi-note">{{ stat.note }}</span>
      </button>
    </div>

    <div class="analysis-grid">
      <article class="analysis-card trend-card">
        <header class="chart-heading"><div><h2>报名批准走势<TrendCharts /></h2><p>当前组织 · 按批准日期统计</p></div><div class="period-control" aria-label="趋势时间范围"><button v-for="days in [7, 30]" :key="days" :aria-pressed="period === days" :class="{ selected: period === days }" @click="period = days">近 {{ days }} 天</button></div></header>
        <div class="trend-summary"><div><strong>{{ periodTotal }}</strong><span>人次批准</span></div><p class="chart-readout" aria-live="polite">{{ currentPoint.date.slice(5).replace('-', '.') }}<b>{{ currentPoint.value }}</b>人次</p></div>
        <svg class="approval-chart" viewBox="0 0 640 214" role="group" aria-label="每日报名批准人次，可聚焦各数据点查看数值">
          <defs><linearGradient id="approval-fill" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#ff6b2c" stop-opacity=".19" /><stop offset="100%" stop-color="#ff6b2c" stop-opacity=".01" /></linearGradient></defs>
          <g v-for="tick in [0, 1, 2, 3, 4]" :key="tick" class="chart-grid"><line x1="38" :y1="176 - tick * 35" x2="612" :y2="176 - tick * 35" /><text x="24" :y="180 - tick * 35" text-anchor="end">{{ maxY / 4 * tick }}</text></g>
          <path :d="area" class="approval-area" fill="url(#approval-fill)" />
          <path :d="line" class="approval-line" pathLength="1" stroke-dasharray="1" stroke-dashoffset="0" />
          <line :x1="currentPoint.x" :x2="currentPoint.x" y1="30" y2="176" class="chart-crosshair" />
          <g v-for="(point, index) in points" :key="point.date" class="chart-point" tabindex="0" role="button" :aria-label="`${point.date} 批准 ${point.value} 人次`" :aria-pressed="selectedIndex === index" @mouseenter="selectedIndex = index" @focus="selectedIndex = index" @click="selectedIndex = index" @keydown.enter.prevent="selectedIndex = index" @keydown.space.prevent="selectedIndex = index">
            <circle :cx="point.x" :cy="point.y" r="11" fill="transparent" /><circle :cx="point.x" :cy="point.y" :r="selectedIndex === index ? 4.5 : 2.5" class="point-dot" />
          </g>
          <text v-for="index in xLabels" :key="index" :x="points[index]!.x" y="203" text-anchor="middle" class="chart-axis">{{ points[index]!.date.slice(5).replace('-', '.') }}</text>
        </svg>
        <footer class="chart-foot"><span><i></i>每日批准人次</span><button @click="emit('focus', 'registrations')">查看活动报名明细<ArrowRight /></button></footer>
      </article>

      <article class="analysis-card expense-card">
        <header class="chart-heading"><div><h2>经费概况</h2><p>当前组织 · 已立项活动</p></div><Wallet /></header>
        <div class="budget-visual"><div class="donut-wrap"><svg viewBox="0 0 148 148" role="img" :aria-label="`已确认支出占预算 ${budgetPercent}%`"><circle class="budget-track" cx="74" cy="74" r="59" /><circle class="budget-arc" cx="74" cy="74" r="59" pathLength="100" :stroke-dasharray="`${Math.min(100, budgetPercent)} 100`" /></svg><div><strong>{{ budgetCents ? `${budgetPercent}%` : '—' }}</strong><span>支出占预算</span></div></div><div class="budget-caption"><span>预算总额</span><strong>{{ money(budgetCents) }}</strong><small>不含草稿与待确认活动</small></div></div>
        <dl class="expense-legend"><div><dt><i></i>已确认支出</dt><dd>{{ money(expenseCents) }}</dd></div><div><dt><i class="neutral"></i>预算减已确认支出</dt><dd>{{ money(budgetCents - expenseCents) }}</dd></div></dl>
        <footer class="chart-foot"><span>确认记录不等同于付款</span><button @click="emit('focus', 'expenses')">查看明细<ArrowRight /></button></footer>
      </article>
    </div>
  </section>
</template>
