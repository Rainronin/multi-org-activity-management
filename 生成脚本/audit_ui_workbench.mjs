// 工作台视觉还原核对：登录演示账号，同视口截图并断言新结构存在、数值与接口一致、无横向溢出。
// 只读页面 + 一次登录，不写业务数据。
import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

const base = process.env.UI_BASE || 'http://127.0.0.1:5174'
const username = process.env.UI_AUDIT_USER || 'demo_owner_yrnn'
const password = process.env.UI_AUDIT_PASS || 'Demo12345!'

const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const ctx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
  const login = await ctx.request.post('/api/auth/login', {
    form: { username, password }, headers: { [csrf.headerName]: csrf.token },
  })
  assert.equal(login.status(), 204, `登录失败 ${login.status()}（可用 UI_AUDIT_USER／UI_AUDIT_PASS 覆盖）`)
  const orgs = await (await ctx.request.get('/api/me/organizations')).json()
  const org = orgs.items[0]
  assert.ok(org, '账号没有可用组织')
  const workbench = await (await ctx.request.get(`/api/organizations/${org.id}/workbench`)).json()

  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
  const page = await ctx.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  await page.goto(`/org/${org.id}`, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-focus-activity]', { timeout: 20000 })
  await page.waitForSelector('[data-ai-card]', { timeout: 20000 })
  await page.waitForTimeout(600)

  const structure = {
    spotlight: await page.locator('[data-focus-activity]').count(),
    assistant: await page.locator('[data-ai-card]').count(),
    kpis: await page.locator('[data-kpi-band] .kpi-card').count(),
    chart: await page.locator('[data-chart-panel]').count(),
    details: await page.locator('[data-detail-panel]').count(),
    todos: await page.locator('[data-todo-panel]').count(),
    stageRail: await page.locator('.stage-rail li').count(),
  }
  const kpiValues = {}
  for (const key of ['activities-in-progress', 'tasks-open', 'registrations-effective', 'expense-approved']) {
    const locator = page.locator(`[data-kpi="${key}"] .kpi-card__value`)
    kpiValues[key] = (await locator.count()) > 0 ? (await locator.first().innerText()).trim().split('\n')[0].trim() : null
  }
  const spotlightTitle = await page.locator('[data-focus-activity] h2').first().innerText()
  const overflow = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  const shot = fileURLToPath(new URL('../验证截图/UI核对-工作台-1440.png', import.meta.url))
  await page.screenshot({ path: shot, fullPage: true })

  const mobile = await ctx.newPage()
  await mobile.setViewportSize({ width: 390, height: 844 })
  await mobile.goto(`/org/${org.id}`, { waitUntil: 'networkidle' })
  await mobile.waitForSelector('[data-focus-activity]', { timeout: 20000 })
  await mobile.waitForTimeout(400)
  const mobileOverflow = await mobile.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  const mobileShot = fileURLToPath(new URL('../验证截图/UI核对-工作台-390.png', import.meta.url))
  await mobile.screenshot({ path: mobileShot, fullPage: true })

  const cardValue = (key) => [...workbench.cards, ...workbench.myCards].find((item) => item.key === key)?.value ?? null
  const checks = {
    kpiMatchesApi: {
      activities: kpiValues['activities-in-progress'] === cardValue('activities-in-progress'),
      tasks: kpiValues['tasks-open'] === cardValue('tasks-open'),
      registrations: kpiValues['registrations-effective'] === cardValue('registrations-effective'),
      expense: kpiValues['expense-approved'] === cardValue('expense-approved'),
    },
  }
  assert.equal(structure.spotlight, 1, '缺少重点活动卡')
  assert.equal(structure.assistant, 1, '缺少 AI 文稿卡')
  assert.ok(structure.kpis >= 3, `KPI 卡数量不足：${structure.kpis}`)
  assert.equal(structure.chart, 1, '缺少图表面板')
  assert.equal(structure.stageRail, 6, '重点活动阶段轨应包含六个阶段')
  assert.equal(overflow.scrollWidth - overflow.clientWidth, 0, '桌面无横向溢出')
  assert.equal(mobileOverflow.scrollWidth - mobileOverflow.clientWidth, 0, '手机无横向溢出')
  assert.equal(errors.length, 0, `页面错误：${errors.join('; ')}`)
  assert.ok(Object.values(checks.kpiMatchesApi).every(Boolean), `KPI 与接口不一致：${JSON.stringify(kpiValues)}`)

  console.log(JSON.stringify({
    check: '工作台视觉还原核对（Demo 阅读顺序：重点卡＋AI 卡 → KPI → 图表 → 明细／待办）',
    org: { id: org.id, name: org.name },
    structure,
    spotlightTitle,
    kpiValues,
    checks,
    overflowPx: overflow.scrollWidth - overflow.clientWidth,
    mobileOverflowPx: mobileOverflow.scrollWidth - mobileOverflow.clientWidth,
    pageErrors: errors,
    screenshots: ['验证截图/UI核对-工作台-1440.png', '验证截图/UI核对-工作台-390.png'],
  }, null, 1))
} finally {
  await browser.close()
}
