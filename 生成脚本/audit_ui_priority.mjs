// UI 专项：接口替身仅用于布局和导航检查，不证明真实权限或业务提交通过。
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

const output = new URL('../验证截图/UI优先级优化/', import.meta.url)
await mkdir(output, { recursive: true })
const org = { id: '1', name: '星河团队 · UI 测试样例', role: 'OWNER', memberCount: 28, createdAt: '2026-09-22', joinedAt: '2026-09-22', description: '分工清楚，进展有数。' }
const activity = { id: '1', name: '秋日团建 · 一起向山野出发', description: '半日户外交流与团队协作，场地和物料由各负责人跟进。', ownerMemberId: '1', ownerUsername: '林晓', status: 'APPROVED', budget: '5000.00', startTime: '2026-10-16T01:00:00Z', endTime: '2026-10-16T09:00:00Z', location: '城市森林公园', registrationScope: 'INTERNAL', capacity: 28, registrationOpen: true, version: 1, proposal: '活动目标：增进团队交流。', createdAt: '2026-09-22', updatedAt: '2026-09-22' }
const card = (key, label, value, hint = '') => ({ key, label, value, hint, detailPath: '/org/1/activities/1', detailLabel: '查看活动' })
const workbench = {
  role: 'OWNER',
  cards: [card('activities-in-progress', '进行中活动', '1', '筹备中 1 个'), card('tasks-open', '待处理任务', '2', '包含待处理与进行中'), card('registrations-effective', '有效报名', '18', '不代表到场人数'), card('expense-approved', '已确认支出', '1800.00', '仅计已确认费用'), card('expense-balance', '预算余额', '3200.00'), card('tasks-completion', '任务完成率', '50%'), card('registrations-capacity', '名额使用率', '64.3%')],
  myCards: [],
  todos: [{ kind: 'REGISTRATION_PENDING', title: '团建报名待审核', detail: '还有 3 份申请等待处理', path: '/org/1/activities/1?tab=registration' }, { kind: 'TASK_OVERDUE', title: '确认场地与雨天备选方案', detail: '小李负责 · 请核对当前进展', path: '/org/1/activities/1?tab=tasks' }],
}
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const results = []
try {
  const context = await browser.newContext({ reducedMotion: 'reduce' })
  const unexpected = []
  await context.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    let data
    if (path === '/api/auth/me') data = { id: '1', username: '林晓' }
    else if (path === '/api/me/organizations') data = { items: [org] }
    else if (path === '/api/organizations/1') data = org
    else if (path.endsWith('/workbench')) data = workbench
    else if (path.endsWith('/activities')) data = { items: [activity], total: 1, page: 1, pageSize: 50 }
    else if (path.endsWith('/activities/1')) data = activity
    else if (/\/(tasks|materials|attachments)$/.test(path)) data = { items: [] }
    else if (path.endsWith('/members')) data = { items: [{ memberId: '1', username: '林晓', role: 'OWNER' }], total: 1 }
    else if (path.includes('/ai/')) data = { available: false, availabilityHint: '测试样例：尚未配置模型，可手工编写。' }
    else { unexpected.push(path); return route.fulfill({ status: 500, json: { detail: '未定义的 UI 测试请求' } }) }
    assert.equal(route.request().method(), 'GET', 'UI 检查不得提交业务数据')
    await route.fulfill({ json: data })
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  for (const width of [1440, 1024, 768, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto(`${process.env.UI_BASE || 'http://127.0.0.1:5174'}/org/1`, { waitUntil: 'networkidle' })
    await page.locator('[data-todo-panel]').waitFor({ timeout: 10000 }).catch(async error => {
      console.error({ unexpected, errors, page: await page.locator('body').innerText() })
      throw error
    })
    const todo = await page.locator('[data-todo-panel]').boundingBox()
    const kpi = await page.locator('[data-kpi-band]').boundingBox()
    const ai = await page.locator('[data-ai-card]').boundingBox()
    assert.ok(todo.y < kpi.y && todo.y < ai.y, '待办应先于 KPI 和 AI 展示')
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${width}px 工作台溢出`)
    if (width <= 700) {
      assert.ok(todo.y < 350, '手机首屏应看见待办')
      assert.equal(await page.locator('.u-navigation').isVisible(), false, '手机导航默认收起')
      await page.getByRole('button', { name: '展开导航' }).click()
      assert.equal(await page.locator('.u-navigation').isVisible(), true)
      await page.getByRole('button', { name: '收起导航' }).press('Escape')
      assert.equal(await page.locator('.u-navigation').isVisible(), false)
      await page.getByRole('button', { name: '展开导航' }).click()
      await page.getByRole('link', { name: '活动管理', exact: true }).click()
      await page.waitForURL('**/activities')
      assert.equal(await page.locator('.u-navigation').isVisible(), false, '导航成功后收起')
      await page.goto(`${process.env.UI_BASE || 'http://127.0.0.1:5174'}/org/1`, { waitUntil: 'networkidle' })
    }
    await page.screenshot({ path: fileURLToPath(new URL(`工作台-${width}.png`, output)), fullPage: true })
    if (width === 390) await page.screenshot({ path: fileURLToPath(new URL('手机首屏.png', output)) })
    await page.goto(`${process.env.UI_BASE || 'http://127.0.0.1:5174'}/org/1/activities/1`, { waitUntil: 'networkidle' })
    assert.equal(await page.getByRole('button', { name: '查看任务与物资', exact: true }).count(), 1)
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${width}px 详情溢出`)
    await page.screenshot({ path: fileURLToPath(new URL(`活动详情-${width}.png`, output)), fullPage: true })
    await page.getByRole('button', { name: '查看任务与物资', exact: true }).click()
    await page.waitForURL('**?tab=tasks')
    await page.getByRole('button', { name: '新建任务', exact: true }).waitFor()
    assert.equal(await page.getByRole('tab', { name: '任务与物资', exact: true }).getAttribute('aria-selected'), 'true')
    await page.reload({ waitUntil: 'networkidle' })
    assert.equal(await page.getByRole('tab', { name: '任务与物资', exact: true }).getAttribute('aria-selected'), 'true')
    results.push({ width, todoTop: Math.round(todo.y), status: 'passed' })
  }
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.getByRole('button', { name: '折叠侧栏', exact: true }).click()
  assert.equal(await page.locator('.workspace').getAttribute('data-nav-collapsed'), 'true')
  await page.setViewportSize({ width: 390, height: 900 })
  await page.getByRole('button', { name: '展开导航' }).click()
  assert.equal(await page.getByRole('link', { name: '活动管理', exact: true }).isVisible(), true)
  await page.setViewportSize({ width: 1440, height: 900 })
  assert.equal(await page.getByRole('button', { name: '展开侧栏', exact: true }).isVisible(), true)

  let release
  const held = new Promise(resolve => { release = resolve })
  await page.route('**/workbench?*', async route => { await held; await route.fallback() })
  await page.setViewportSize({ width: 390, height: 900 })
  await page.goto(`${process.env.UI_BASE || 'http://127.0.0.1:5174'}/org/1`, { waitUntil: 'domcontentloaded' })
  await page.locator('.workbench__skeleton').waitFor()
  assert.deepEqual(await page.locator('[data-skeleton-block]').evaluateAll(nodes => nodes.map(n => n.dataset.skeletonBlock)), ['todos', 'chart', 'assistant', 'details'])
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '手机骨架不得溢出')
  const chartPlaceholder = await page.locator('[data-skeleton-block="chart"]').boundingBox()
  const assistantPlaceholder = await page.locator('[data-skeleton-block="assistant"]').boundingBox()
  assert.ok(assistantPlaceholder.y >= chartPlaceholder.y + chartPlaceholder.height, '手机加载骨架应与真实内容一样单列排列')
  await page.screenshot({ path: fileURLToPath(new URL('工作台-加载.png', output)), fullPage: true })
  release()
  await page.locator('[data-todo-panel]').waitFor()
  await page.unroute('**/workbench?*')

  workbench.role = 'MEMBER'
  workbench.cards = []
  workbench.todos = []
  org.role = 'MEMBER'
  await page.goto(`${process.env.UI_BASE || 'http://127.0.0.1:5174'}/org/1`, { waitUntil: 'networkidle' })
  assert.equal(await page.locator('[data-ai-card]').count(), 0, '普通成员不展示 AI 管理入口')
  assert.match(await page.locator('[data-todo-panel]').innerText(), /暂无待处理事项/)
  assert.deepEqual(errors, [], '页面运行错误')
  assert.deepEqual(unexpected, [], '未覆盖的请求')
  await writeFile(new URL('结果.json', output), JSON.stringify({ evidence: 'UI 接口替身，非业务验收', results }, null, 2))
  console.log(JSON.stringify(results))
} finally { await browser.close() }
