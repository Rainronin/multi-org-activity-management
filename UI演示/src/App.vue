<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { ElButton, ElDialog, ElDrawer, ElEmpty, ElForm, ElFormItem, ElInput, ElOption, ElSelect, ElTable, ElTableColumn, ElTag, ElDropdown, ElDropdownMenu, ElDropdownItem, ElSkeleton, ElSkeletonItem, type FormInstance } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/drawer/style/css'
import 'element-plus/es/components/empty/style/css'
import 'element-plus/es/components/form/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/select/style/css'
import 'element-plus/es/components/table/style/css'
import 'element-plus/es/components/tag/style/css'
import 'element-plus/es/components/dropdown/style/css'
import 'element-plus/es/components/skeleton/style/css'
import { ArrowRight, ArrowDown, Calendar, Check, CircleCheck, Clock, FolderOpened, Document, Plus, Search, Tickets } from '@element-plus/icons-vue'
const Overview = defineAsyncComponent(() => import('./Overview.vue'))
const FocusBoard = defineAsyncComponent(() => import('./FocusBoard.vue'))
const LoginPreview = defineAsyncComponent(() => import('./LoginPreview.vue'))
import { initialActivities, organizations, type Activity, type ActivityStatus } from './demo'

const orgId = ref('volunteer')
const loginOpen = ref(new URLSearchParams(location.search).get('view') === 'login')
const navExpanded = ref(true)
const sidebarCollapsed = ref(false)
const focusBoard = ref<InstanceType<typeof FocusBoard>>()
const loading = ref(true)
let loadingTimer: ReturnType<typeof setTimeout> | undefined
function reloadPreview() {
  clearTimeout(loadingTimer)
  loading.value = true
  loadingTimer = setTimeout(() => { loading.value = false }, 850)
}
function previewCommand(command: string) {
  if (command === 'login') loginOpen.value = true
  else reloadPreview()
}
onMounted(reloadPreview)
onBeforeUnmount(() => clearTimeout(loadingTimer))
const organization = computed(() => organizations.find(org => org.id === orgId.value)!)
const activities = ref(structuredClone(initialActivities))
const aiDrafts = ref<Record<string, string>>({})
const section = ref<'activities' | 'tasks'>('activities')
const query = ref('')
const status = ref('全部活动')
const tableMode = ref('活动信息')
const listPanel = ref<HTMLElement>()
const statuses = ['全部活动', '进行中', '筹备中', '执行中', '复盘中', '待确认', '草稿', '已归档']
const currentActivities = computed(() => activities.value.filter(activity => activity.org === orgId.value))
const visibleActivities = computed(() => currentActivities.value.filter(activity =>
  (status.value === '全部活动' || activity.status === status.value || (status.value === '进行中' && ['筹备中', '执行中'].includes(activity.status))) &&
  `${activity.title} ${activity.description}`.includes(query.value.trim()),
))
const myTasks = computed(() => currentActivities.value.flatMap(activity =>
  activity.tasks.filter(task => task.owner === '林悦' && !task.done).map(task => ({ ...task, activity })),
))
const selected = ref<Activity>()
const detailOpen = ref(false)
const detailTab = ref('活动概览')
const detailTabs = ['活动概览', '任务清单']
const stages = ['草稿', '待确认', '筹备中', '执行中', '复盘中', '已归档']

function openActivity(activity: Activity) {
  selected.value = activity
  detailTab.value = '活动概览'
  detailOpen.value = true
}
function statusType(value: ActivityStatus) {
  if (value === '筹备中') return 'primary'
  if (value === '执行中') return 'success'
  if (value === '待确认') return 'warning'
  return 'info'
}
function progress(activity: Activity) {
  if (!activity.tasks.length) return '—'
  return `${activity.tasks.filter(task => task.done).length} / ${activity.tasks.length}`
}
function money(amount: string) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(Number(amount))
}

const createOpen = ref(false)
const form = ref<FormInstance>()
const draft = ref({ title: '', location: '' })
const rules = {
  title: [{ required: true, whitespace: true, message: '请填写活动名称', trigger: 'blur' }],
  location: [{ required: true, whitespace: true, message: '请填写活动地点', trigger: 'blur' }],
}
async function createDraft() {
  if (!(await form.value?.validate().catch(() => false))) return
  const activity: Activity = {
    id: Date.now(), org: orgId.value, title: draft.value.title.trim(), description: '新活动草稿',
    date: '', time: '时间待定', location: draft.value.location.trim(), owner: '林悦', status: '草稿',
    registration: '暂未发布', approved: 0, capacity: 0, budget: '0.00', expense: '0.00', tasks: [],
  }
  activities.value.unshift(activity)
  query.value = ''
  status.value = '全部活动'
  createOpen.value = false
  draft.value = { title: '', location: '' }
  section.value = 'activities'
  await nextTick()
}
async function focusDetails(target: 'active' | 'tasks' | 'expenses' | 'registrations') {
  section.value = target === 'tasks' ? 'tasks' : 'activities'
  status.value = target === 'active' ? '进行中' : '全部活动'
  query.value = ''
  tableMode.value = target === 'expenses' ? '经费明细' : target === 'registrations' ? '报名明细' : '活动信息'
  await nextTick()
  listPanel.value?.scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start' })
}
watch(orgId, () => {
  reloadPreview()
  tableMode.value = '活动信息' 
  query.value = ''
  status.value = '全部活动'
  detailOpen.value = false
  selected.value = undefined
  createOpen.value = false
  draft.value = { title: '', location: '' }
})
</script>

<template>
  <LoginPreview v-if="loginOpen" @enter="loginOpen = false; reloadPreview()" />
  <div v-else class="workspace" :data-nav-collapsed="sidebarCollapsed" :data-group-collapsed="!navExpanded">
    <aside class="unified-sidebar" aria-label="组织侧栏">
      <div class="u-brand-row">
        <a class="u-brand" href="#" @click.prevent="section = 'activities'" aria-label="返回活动管理" title="多组织活动管理">
          <svg class="u-mark" viewBox="0 0 32 32" aria-hidden="true"><path fill="currentColor" d="M8 8h5L8 27H3zm9-6h5l-7 25h-5z"/><path fill="var(--ui-color-accent)" d="M23 17h5l-3 10h-5z"/></svg>
          <span class="u-label u-brand-name">多组织活动管理<small>ACTIVITY WORKSPACE</small></span>
        </a>
      </div>
      <button class="u-collapse" :aria-label="sidebarCollapsed ? '展开侧栏' : '折叠侧栏'" :aria-expanded="!sidebarCollapsed" aria-controls="primary-navigation" @click="sidebarCollapsed = !sidebarCollapsed"><ArrowRight :class="{ 'u-turn': !sidebarCollapsed }"/><span class="u-label">收起侧栏</span></button>
      <div class="u-organization">
        <span class="u-label u-eyebrow">当前组织</span>
        <ElSelect v-model="orgId" aria-label="切换组织" class="organization-select" filterable popper-class="organization-popper" :title="organization.name">
          <template #prefix><span class="u-org-initial">{{ organization.initials }}</span></template>
          <ElOption v-for="org in organizations" :key="org.id" :label="org.name" :value="org.id"><span class="org-option"><b>{{ org.initials }}</b><span>{{ org.name }}<small>{{ org.members }} 位成员 · 组织空间</small></span></span></ElOption>
        </ElSelect>
        <div class="u-meta"><span>{{ organization.members }} 位成员</span><span>组织负责人</span></div>
      </div>
      <nav id="primary-navigation" class="u-navigation" aria-label="主要导航">
        <button class="u-group" :aria-expanded="navExpanded" aria-controls="workspace-subnav" @click="navExpanded = !navExpanded"><span>组织工作区</span><ArrowDown :class="{ 'u-turn': !navExpanded }" /></button>
        <div id="workspace-subnav" class="u-subitems">
          <button class="u-item" :class="{ active: section === 'activities' }" :aria-current="section === 'activities' ? 'page' : undefined" aria-label="活动管理" title="活动管理" @click="section = 'activities'"><FolderOpened/><span class="u-label">活动管理</span><span class="u-count">{{ currentActivities.length }}</span></button>
          <button class="u-item" :class="{ active: section === 'tasks' }" :aria-current="section === 'tasks' ? 'page' : undefined" aria-label="我的任务" title="我的任务" @click="section = 'tasks'"><CircleCheck/><span class="u-label">我的任务</span><span class="u-count">{{ myTasks.length }}</span></button>
        </div>
        <button class="u-item" aria-label="AI 文稿助手" title="AI 文稿助手" :disabled="loading" @click="focusBoard?.openAssistant()"><Document/><span class="u-label">AI 文稿助手</span></button>
      </nav>
      <div class="u-account"><ElDropdown trigger="click" @command="previewCommand"><button class="u-profile" aria-label="账号菜单" title="林悦 · 演示账号"><span class="u-avatar">林</span><span class="u-label">林悦<small>组织负责人</small></span><ArrowDown class="u-label"/></button><template #dropdown><ElDropdownMenu><ElDropdownItem command="login">查看登录界面</ElDropdownItem></ElDropdownMenu></template></ElDropdown></div>
    </aside>

    <div class="main-shell">
      <header class="topbar"><div><span>{{ organization.name }}</span><span class="breadcrumb-separator">/</span><strong>{{ section === 'activities' ? '活动管理' : '我的任务' }}</strong></div><div class="topbar-tools"><span class="demo-badge"><span></span>UI 演示 · 示例数据</span><ElDropdown trigger="click" @command="previewCommand"><button class="preview-menu">演示选项<ArrowDown /></button><template #dropdown><ElDropdownMenu><ElDropdownItem command="login">查看登录界面</ElDropdownItem><ElDropdownItem command="reload">重播加载骨架</ElDropdownItem></ElDropdownMenu></template></ElDropdown></div></header>
      <main>
        <div class="page-heading">
          <div><p class="eyebrow">{{ section === 'activities' ? '组织协作 / 活动管理' : '个人协作 / 待办任务' }}</p><h1>{{ section === 'activities' ? '把每一场活动，做好' : '让协作，向前一步' }}<span class="orange">。</span></h1><p class="page-description">{{ section === 'activities' ? '从筹备到复盘，让想法有进展，让协作有回应。' : '专注当前组织中分配给你的未完成任务。' }}</p></div>
          <ElButton v-if="section === 'activities'" type="primary" :icon="Plus" class="create-button" @click="createOpen = true">创建活动</ElButton>
        </div>

        <section v-if="loading" class="workspace-skeleton" aria-label="正在加载组织工作台" role="status" aria-busy="true"><span class="sr-only">正在加载组织工作台</span><ElSkeleton animated><template #template><div class="skeleton-focus"><div><ElSkeletonItem variant="h1" /><ElSkeletonItem variant="p" /><ElSkeletonItem variant="rect" class="skeleton-stage" /></div><div><ElSkeletonItem variant="h3" /><ElSkeletonItem variant="p" /><ElSkeletonItem variant="p" /><ElSkeletonItem variant="rect" class="skeleton-action" /></div></div><div class="skeleton-kpis"><ElSkeletonItem v-for="n in 4" :key="n" variant="rect" /></div><div class="skeleton-focus"><div><ElSkeletonItem variant="h3" /><ElSkeletonItem variant="rect" class="skeleton-stage" /></div><div><ElSkeletonItem variant="circle" class="skeleton-donut" /></div></div></template></ElSkeleton></section>
        <div v-else class="loaded-workspace">
        <FocusBoard ref="focusBoard" v-model:drafts="aiDrafts" :key="orgId" :activities="currentActivities" @open="openActivity" />
        <Overview :activities="currentActivities" :org-id="orgId" :pending-tasks="myTasks.length" @focus="focusDetails" />

        <div class="content-grid">
          <section ref="listPanel" class="activity-panel" aria-label="活动与任务列表">
            <template v-if="section === 'activities'">
              <div class="panel-heading"><h2>活动明细<span>{{ currentActivities.length }}</span></h2><span class="quiet-label">当前组织 · 全部负责人</span></div>
              <div class="detail-mode" aria-label="明细类型"><button v-for="mode in ['活动信息', '报名明细', '经费明细']" :key="mode" :class="{ selected: tableMode === mode }" :aria-pressed="tableMode === mode" @click="tableMode = mode">{{ mode }}</button></div>
              <div class="toolbar"><div class="quick-filters" aria-label="常用活动筛选"><button v-for="filter in ['全部活动', '筹备中', '复盘中']" :key="filter" :class="{ selected: status === filter }" :aria-pressed="status === filter" @click="status = filter">{{ filter }}</button></div><ElInput v-model="query" :prefix-icon="Search" placeholder="搜索活动" aria-label="搜索活动" clearable class="search-input" /></div>
              <div class="list-meta"><span>共 {{ visibleActivities.length }} 场活动</span><ElDropdown trigger="click" @command="(value: string) => status = value"><button class="status-menu" aria-label="按活动状态筛选">{{ status }}<ArrowDown /></button><template #dropdown><ElDropdownMenu><ElDropdownItem v-for="item in statuses" :key="item" :command="item" :class="{ 'chosen-status': status === item }">{{ item }}<Check v-if="status === item" class="menu-check" /></ElDropdownItem></ElDropdownMenu></template></ElDropdown></div>
              <ElTable :data="visibleActivities" class="activity-table" empty-text="没有符合条件的活动，请调整筛选条件。">
                <ElTableColumn label="活动名称" min-width="265"><template #default="{ row }"><div class="activity-name-cell"><span class="activity-symbol" aria-hidden="true"><Tickets v-if="row.id % 2" /><Calendar v-else /></span><div><button class="activity-title" @click="openActivity(row as Activity)">{{ row.title }}</button><p>{{ row.description }}</p></div></div></template></ElTableColumn>
                <ElTableColumn v-if="tableMode === '活动信息'" label="活动时间" width="105"><template #default="{ row }"><span class="date-text">{{ row.date ? row.date.slice(5).replace('-', '.') : '待安排' }}</span><p class="table-secondary">{{ row.date ? '2026 年' : '时间待定' }}</p></template></ElTableColumn>
                <ElTableColumn label="状态" width="104"><template #default="{ row }"><ElTag :type="statusType(row.status)" effect="light" round>{{ row.status }}</ElTag></template></ElTableColumn>
                <ElTableColumn v-if="tableMode === '活动信息'" label="负责人" width="88"><template #default="{ row }"><span class="owner-name">{{ row.owner }}</span></template></ElTableColumn>
                <ElTableColumn v-if="tableMode === '报名明细'" label="已批准 / 名额" width="125"><template #default="{ row }"><span class="date-text">{{ row.approved }} / {{ row.capacity || '—' }}</span></template></ElTableColumn>
                <ElTableColumn v-if="tableMode === '报名明细'" label="报名状态" width="118"><template #default="{ row }"><span class="owner-name">{{ row.registration }}</span></template></ElTableColumn>
                <ElTableColumn v-if="tableMode === '经费明细'" label="预算" width="110"><template #default="{ row }"><span class="amount-cell">{{ money(row.budget) }}</span><small v-if="['草稿', '待确认'].includes(row.status)" class="unconfirmed-note">未计入概况</small></template></ElTableColumn>
                <ElTableColumn v-if="tableMode === '经费明细'" label="已确认支出" width="115"><template #default="{ row }"><span class="amount-cell">{{ money(row.expense) }}</span></template></ElTableColumn>
                <ElTableColumn label="查看" width="56" align="right"><template #default="{ row }"><button class="arrow-button" :aria-label="`查看${row.title}`" @click="openActivity(row as Activity)"><ArrowRight /></button></template></ElTableColumn>
              </ElTable>
              <div class="list-footer"><span><span class="tiny-dot"></span>活动主状态与报名状态独立管理</span><span>共 {{ visibleActivities.length }} 条</span></div>
            </template>
            <template v-else>
              <div class="panel-heading"><h2>我的待办<span>{{ myTasks.length }}</span></h2><span class="quiet-label">林悦 · 当前组织</span></div>
              <div v-for="task in myTasks" :key="`${task.activity.id}-${task.title}`" class="personal-task"><span class="task-marker"><Clock /></span><div><h3>{{ task.title }}</h3><p>{{ task.activity.title }}</p></div><span class="task-date">{{ task.due.replace('-', '.') }} 截止</span><button class="arrow-button" :aria-label="`查看任务所属活动${task.activity.title}`" @click="openActivity(task.activity); detailTab = '任务清单'"><ArrowRight /></button></div>
              <ElEmpty v-if="!myTasks.length" description="当前没有待处理任务" />
            </template>
          </section>


        </div>
        <footer class="page-footer"><span>多组织活动管理系统</span><span>风格预览 · 本地操作刷新后重置</span></footer>
        </div>
      </main>
    </div>

    <ElDrawer v-model="detailOpen" size="min(760px, 100vw)" class="activity-drawer" :title="selected?.title" destroy-on-close>
      <template v-if="selected">
        <div class="detail-heading"><div class="detail-kicker"><ElTag :type="statusType(selected.status)">{{ selected.status }}</ElTag><span>{{ organization.name }}</span></div><h2>{{ selected.title }}<span class="orange">。</span></h2><p>{{ selected.description }}</p></div>
        <ol class="stage-rail" aria-label="活动阶段"><li v-for="(stage, index) in stages" :key="stage" :class="{ reached: index < stages.indexOf(selected.status), current: stage === selected.status }" :aria-current="stage === selected.status ? 'step' : undefined"><span><Check v-if="index < stages.indexOf(selected.status)" /><template v-else>{{ index + 1 }}</template></span>{{ stage }}</li></ol>
        <div class="detail-tabs" aria-label="详情区段"><button v-for="tab in detailTabs" :key="tab" :class="{ selected: detailTab === tab }" :aria-pressed="detailTab === tab" @click="detailTab = tab">{{ tab }}</button></div>
        <div v-if="detailTab === '活动概览'" class="detail-content">
          <dl class="detail-facts"><div><dt>活动日期</dt><dd>{{ selected.date || '待安排' }}<small>{{ selected.time }}</small></dd></div><div><dt>活动地点</dt><dd>{{ selected.location }}</dd></div><div><dt>活动负责人</dt><dd>{{ selected.owner }}</dd></div><div><dt>报名状态</dt><dd>{{ selected.registration }}<small>已批准 {{ selected.approved }} 人<span v-if="selected.capacity"> / 名额 {{ selected.capacity }} 人</span></small></dd></div></dl>
          <section class="budget-section"><div><span class="section-label">活动预算</span><strong>{{ money(selected.budget) }}</strong></div><div><span class="section-label">已确认支出</span><strong>{{ money(selected.expense) }}</strong></div></section>
          <div class="detail-note"><span class="mini-rule"></span><h3>把筹备事项，落到每一个人</h3><p>在任务清单中查看分工与截止时间。任务进度独立展示，不自动改变活动阶段。</p><ElButton text @click="detailTab = '任务清单'">查看任务清单<ArrowRight /></ElButton></div>
        </div>
        <div v-else class="detail-content"><div class="task-list-heading"><h3>任务清单</h3><span>完成 {{ progress(selected) }}</span></div><div v-for="task in selected.tasks" :key="task.title" class="detail-task"><span class="task-marker" :class="{ done: task.done }"><Check v-if="task.done" /><Clock v-else /></span><div><h4>{{ task.title }}</h4><p>{{ task.owner }}<span>·</span>{{ task.due.replace('-', '.') }} 截止</p></div><span class="task-state">{{ task.done ? '已完成' : '待处理' }}</span></div><ElEmpty v-if="!selected.tasks.length" description="还没有任务安排" /></div>
      </template>
      <template #footer><span class="drawer-footer-note">此处展示示例活动，不提交业务变更。</span><ElButton @click="detailOpen = false">返回列表</ElButton></template>
    </ElDrawer>

    <ElDialog v-model="createOpen" title="创建活动草稿" width="min(480px, calc(100vw - 32px))" @closed="form?.clearValidate()">
      <p class="dialog-description">先记下活动的想法。此次草稿仅保留在当前演示页面，刷新后清除。</p>
      <ElForm ref="form" :model="draft" :rules="rules" label-position="top" @submit.prevent="createDraft">
        <ElFormItem label="活动名称" prop="title"><ElInput v-model="draft.title" placeholder="例如：校园秋日旧书交换" maxlength="50" show-word-limit /></ElFormItem>
        <ElFormItem label="活动地点" prop="location"><ElInput v-model="draft.location" placeholder="例如：图书馆南广场" maxlength="80" /></ElFormItem>
        <div class="dialog-actions"><ElButton @click="createOpen = false">取消</ElButton><ElButton type="primary" native-type="submit">添加演示草稿</ElButton></div>
      </ElForm>
    </ElDialog>
  </div>
</template>
