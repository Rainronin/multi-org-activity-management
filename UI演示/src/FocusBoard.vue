<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElButton, ElDrawer, ElEmpty, ElInput, ElSelect, ElOption, ElSkeleton, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { ArrowRight, Calendar, Check, Clock, Document, Location } from '@element-plus/icons-vue'
import { gsap } from 'gsap'
import type { Activity } from './demo'
const props = defineProps<{ activities: Activity[] }>()
const emit = defineEmits<{ open: [activity: Activity] }>()
const root = ref<HTMLElement>()
const upcoming = computed(() => props.activities.find(activity => activity.status === '筹备中'))
const stages = ['草稿', '待确认', '筹备中', '执行中', '复盘中', '已归档']
const mode = ref<'总结' | '方案'>('总结')
const candidates = computed(() => props.activities.filter(activity => activity.status === (mode.value === '总结' ? '复盘中' : '草稿')))
const aiOpen = ref(false)
const chosen = ref<number>()
const activity = computed(() => candidates.value.find(item => item.id === chosen.value))
const busy = ref(false)
const error = ref('')
const generated = ref('')
const edits = defineModel<Record<string, string>>('drafts', { required: true })
const saved = ref('')
const editKey = computed(() => `${chosen.value}-${mode.value}`)
const edit = computed({ get: () => edits.value[editKey.value] || '', set: value => { edits.value = { ...edits.value, [editKey.value]: value }; saved.value = '' } })
let timer: ReturnType<typeof setTimeout> | undefined
let media: gsap.MatchMedia | undefined
function resetGeneration() {
  clearTimeout(timer)
  busy.value = false
  generated.value = ''
  error.value = ''
  saved.value = ''
}
function openAssistant() {
  chosen.value = candidates.value[0]?.id
  resetGeneration()
  aiOpen.value = true
}
function generate(fail = false) {
  if (!activity.value || busy.value) return
  busy.value = true
  error.value = ''
  const item = activity.value
  // 仅模拟等待与故障，不调用模型；文字依据本地样例拼装并明确标示。
  timer = setTimeout(() => {
    busy.value = false
    if (fail) { error.value = '模拟生成失败。已有编辑内容保留，可重试或继续手工编写。'; return }
    generated.value = mode.value === '总结'
      ? `【示例总结草稿 · 未调用模型】\n\n${item.title}\n\n执行概况\n活动日期：${item.date}，地点：${item.location}。已批准报名 ${item.approved} 人，不代表实际到场。任务完成 ${item.tasks.filter(task => task.done).length}/${item.tasks.length} 项；已确认支出 ¥${item.expense}。\n\n待补充信息\n尚未提供执行说明、真实反馈和成果记录，请负责人补充核实。\n\n改进建议（仅建议）\n下次活动前明确物资领取与责任分工，并在结束后及时收集反馈。`
      : `【示例方案草稿 · 未调用模型】\n\n${item.title}\n\n活动构思\n围绕活动主题组织小规模交流，在${item.location}开展。\n\n建议流程\n准备与分工 → 主题介绍 → 分组参与 → 交流与收尾。\n\n待确认\n面向对象、具体日期、人数与物资报价仍需补充。当前预算字段为 ¥${item.budget}，本草稿不会更改预算或创建任务。`
  }, 1100)
}
async function adopt() {
  if (edit.value && edit.value !== generated.value) {
    try { await ElMessageBox.confirm('采用新草稿将替换当前编辑区的文字，是否继续？', '替换编辑内容', { confirmButtonText: '替换', cancelButtonText: '保留当前内容', type: 'warning' }) } catch { return }
  }
  edit.value = generated.value
}
onMounted(() => {
  media = gsap.matchMedia()
  media.add('(prefers-reduced-motion: no-preference)', () => {
    gsap.from('.spotlight, .assistant-card', { y: 22, opacity: 0, duration: .8, stagger: .12, ease: 'power3.out', clearProps: 'all' })
  }, root.value)
})
onBeforeUnmount(() => { clearTimeout(timer); media?.revert() })
defineExpose({ openAssistant })
</script>
<template>
  <section ref="root" class="focus-board" aria-label="重点活动与 AI 文稿助手">
    <article class="spotlight" v-if="upcoming">
      <div class="spotlight-top"><span><i></i>重点活动<span class="spotlight-state">{{ upcoming.status }}</span></span><span>下一场共同期待</span></div>
      <div class="spotlight-title"><div><p>一起，让好事发生</p><h2>{{ upcoming.title }}</h2><span>{{ upcoming.description }}</span></div><div class="spotlight-date">{{ upcoming.date.slice(8) }}<small>{{ upcoming.date.slice(5, 7) }} 月 / 2026</small></div></div>
      <ol class="stage-rail spotlight-stages" aria-label="重点活动阶段"><li v-for="(stage, index) in stages" :key="stage" :class="{ reached: index < stages.indexOf(upcoming.status), current: stage === upcoming.status }"><span><Check v-if="index < stages.indexOf(upcoming.status)" /><template v-else>{{ index + 1 }}</template></span>{{ stage }}</li></ol>
      <div class="spotlight-footer"><div><Calendar /><span>{{ upcoming.date }}<small>{{ upcoming.time }}</small></span></div><div><Location /><span>{{ upcoming.location }}<small>已批准 {{ upcoming.approved }} / {{ upcoming.capacity }} 人</small></span></div><ElButton @click="emit('open', upcoming)">查看活动<ArrowRight /></ElButton></div>
    </article>
    <article v-else class="spotlight"><ElEmpty description="暂无筹备中的活动" /></article>
    <aside class="assistant-card">
      <header><span class="assistant-mark"><Document /></span><div><h2>AI 文稿助手</h2><p>把活动记录，整理成下一步。</p></div><span class="assistant-label">AI</span></header>
      <div class="assistant-modes" aria-label="AI 文稿类型"><button v-for="item in (['总结', '方案'] as const)" :key="item" :class="{ selected: mode === item }" :aria-pressed="mode === item" @click="mode = item">活动{{ item }}</button></div>
      <div class="assistant-paper"><span>{{ mode === '总结' ? '复盘，从已有记录开始' : '让想法，变成可讨论的方案' }}</span><h3>{{ candidates[0]?.title || (mode === '总结' ? '暂无复盘中的活动' : '先创建一份活动草稿') }}</h3><p>{{ mode === '总结' ? '汇总任务、报名与经费，补充真实反馈，形成可编辑的总结草稿。' : '围绕活动目标整理流程、分工与注意事项，待确认的信息清楚标出。' }}</p></div>
      <div class="assistant-bottom"><ElButton :disabled="!candidates.length" @click="openAssistant">打开{{ mode === '总结' ? '复盘' : '方案' }}编辑<ArrowRight /></ElButton><small>交互演示 · 未连接模型</small></div>
    </aside>
    <ElDrawer v-model="aiOpen" :title="`活动${mode} · AI 文稿助手`" size="min(740px, 100vw)" class="ai-drawer" @closed="resetGeneration">
      <p class="ai-intro">选择当前活动，核对资料后生成示例草稿。采用后可手动修改，保存仅发生在本次演示中。</p>
      <label class="ai-field-label" for="ai-activity">关联活动</label><ElSelect id="ai-activity" v-model="chosen" :disabled="busy" aria-label="AI 关联活动" @change="resetGeneration"><ElOption v-for="item in candidates" :key="item.id" :value="item.id" :label="item.title" /></ElSelect>
      <div v-if="activity" class="ai-source"><strong>本次输入范围</strong><p>{{ activity.title }} · {{ activity.status }} · 负责人 {{ activity.owner }}</p><span>{{ mode === '总结' ? `任务 ${activity.tasks.length} 项、批准报名 ${activity.approved} 人、已确认支出 ¥${activity.expense}；执行说明与反馈待补充。` : `活动地点 ${activity.location}、预算 ¥${activity.budget}；目标、人数与详细时间待补充。` }}</span></div>
      <div class="ai-actions"><ElButton type="primary" :loading="busy" :disabled="!activity" @click="generate()">{{ busy ? '正在准备示例草稿' : '生成示例草稿' }}</ElButton><ElButton :disabled="busy || !activity" text @click="generate(true)">体验生成失败</ElButton></div>
      <p v-if="error" class="ai-error" role="alert">{{ error }}</p>
      <div v-if="busy" class="ai-skeleton" role="status" aria-label="正在生成示例草稿"><ElSkeleton :rows="5" animated /></div>
      <section v-else-if="generated" class="generated-paper"><header><strong>新生成的示例草稿</strong><ElButton size="small" @click="adopt">采用到编辑区</ElButton></header><pre>{{ generated }}</pre></section>
      <label class="ai-field-label" for="ai-editor">{{ mode }}编辑区<span>可直接人工编写</span></label><ElInput id="ai-editor" v-model="edit" type="textarea" :rows="9" maxlength="5000" show-word-limit :placeholder="`在这里编辑活动${mode}，生成结果不会自动覆盖你的文字。`" />
      <template #footer><span class="saved-note" role="status">{{ saved || '保存草稿不代表立项通过或总结确认。' }}</span><ElButton type="primary" :disabled="!edit.trim()" @click="saved = '草稿已保留在当前演示中，刷新后清除。'">保存演示草稿</ElButton></template>
    </ElDrawer>
  </section>
</template>
