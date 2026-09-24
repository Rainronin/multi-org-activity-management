// S4 逐页还原核对（活动列表／我的任务／成员管理）：真实账号与数据，状态反例只在浏览器内拦截响应，不写业务库。
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { mkdir, writeFile } from 'node:fs/promises'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

const base = process.env.UI_BASE || 'http://127.0.0.1:5174'
const out = new URL('../验证截图/UI标准页/', import.meta.url)
await mkdir(out, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const results = {
  checks: [],
  screenshots: [],
  widths: [],
  limitations: [
    '加载／空／失败反例为浏览器响应替身，不代表业务验收',
    '未执行后端测试、真实模型调用或业务写入；成员页未改动任何成员关系',
  ],
}
let release = null
const gate = () => new Promise((resolve) => { release = resolve })

try {
  const ctx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
  assert.equal((await ctx.request.post('/api/auth/login', {
    form: { username: process.env.UI_AUDIT_USER || 'demo_owner_yrnn', password: process.env.UI_AUDIT_PASS || 'Demo12345!' },
    headers: { [csrf.headerName]: csrf.token },
  })).status(), 204, '演示负责人登录失败')
  const org = (await (await ctx.request.get('/api/me/organizations')).json()).items[0]
  const activities = (await (await ctx.request.get(`/api/organizations/${org.id}/activities?pageSize=50`)).json()).items
  const members = (await (await ctx.request.get(`/api/organizations/${org.id}/members?pageSize=50`)).json()).items
  assert.ok(org && activities.length > 0 && members.length > 0, '需要既有组织、活动与成员样例')
  const home = `/org/${org.id}`
  const pages = {
    activities: `${home}/activities`,
    tasks: `${home}/my-tasks`,
    members: `${home}/members`,
  }

  const page = await ctx.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const shot = async (name, target = page, fullPage = false) => {
    await target.screenshot({ path: fileURLToPath(new URL(`${name}.png`, out)), fullPage })
    results.screenshots.push(`${name}.png`)
  }
  const count = (selector, target = page) => target.locator(selector).count()
  /** 解除所有拦截；等待中的 handler 已被本次解除处理，按 ignoreErrors 收尾。 */
  const dropRoutes = () => page.unrouteAll({ behavior: 'ignoreErrors' })

  /** 读列表页的状态与结构：用于逐条断言互斥关系与最终显示尺寸。 */
  const readList = async (target = page) => target.evaluate(() => {
    const box = (selector) => {
      const node = document.querySelector(selector)
      return node ? node.getBoundingClientRect().toJSON() : null
    }
    return {
      heading: document.querySelector('h1')?.textContent?.trim() ?? '',
      panelHeading: document.querySelector('.list-panel__head h2')?.textContent?.trim() ?? '',
      tables: document.querySelectorAll('.list-table').length,
      rows: document.querySelector('.list-table')?.querySelectorAll('.el-table__body tr').length ?? 0,
      rowHeights: [...(document.querySelector('.list-table')?.querySelectorAll('.el-table__body tr') ?? [])].map((tr) => Math.round(tr.getBoundingClientRect().height)),
      headerHeight: box('.list-table .el-table__header th')?.height ?? 0,
      skeleton: document.querySelectorAll('[data-list-skeleton]').length,
      empty: document.querySelectorAll('[data-list-empty]').length,
      error: document.querySelectorAll('.list-panel__error').length,
      loadingMask: document.querySelectorAll('.el-loading-mask').length,
      updating: document.body.innerText.includes('正在更新'),
      tableVisible: Boolean(document.querySelector('.list-table .el-table__body')),
      total: document.querySelector('[data-list-total]')?.textContent?.trim() ?? '',
      placeholder: document.querySelector('.activities-status .el-select__placeholder')?.textContent?.trim() ?? '',
      overflow: document.documentElement.scrollWidth - window.innerWidth,
      tableScroll: (() => {
        const wrap = document.querySelector('.list-table .el-scrollbar__wrap')
        return wrap ? wrap.scrollWidth > wrap.clientWidth : false
      })(),
    }
  })

  // ---------- 1. 活动列表 ----------
  const slowActivities = '**/api/organizations/*/activities?*'
  let hold = gate()
  await page.route(slowActivities, async (route) => { await hold; await route.continue() })
  await page.goto(pages.activities, { waitUntil: 'domcontentloaded' })
  await page.locator('[data-list-skeleton]').first().waitFor({ state: 'visible' })
  const loading = await readList()
  assert.equal(loading.rows, 0, '首次加载不能显示数据行')
  assert.equal(loading.empty, 0, '首次加载不能同时显示空态')
  assert.equal(loading.error, 0, '首次加载不能同时显示失败态')
  assert.equal(loading.loadingMask, 0, '首次加载不能叠加整表遮罩')
  assert.ok(loading.skeleton > 0, '首次加载应显示骨架')
  await shot('S4-活动列表-加载')
  release()
  await page.waitForResponse((r) => r.url().includes('/activities?'))
  await dropRoutes()

  const ready = await (async () => {
    await page.reload({ waitUntil: 'networkidle' })
    await page.locator('.list-table .el-table__body tr').first().waitFor({ state: 'visible' })
    return readList()
  })()
  assert.equal(ready.heading, '活动管理')
  assert.equal(ready.panelHeading, '活动明细 3', `面板标题应为“活动明细 3”，实际 ${ready.panelHeading}`)
  assert.equal(ready.rows, activities.length, '活动列表行数应与接口一致')
  assert.ok(ready.rowHeights.every((h) => h < 96), `活动行高应保持紧凑，实际 ${ready.rowHeights.join('/')}`)
  assert.ok(ready.headerHeight <= 48, `表头高度应克制，实际 ${ready.headerHeight}`)
  assert.equal(ready.skeleton, 0, '有数据时不应显示骨架')
  assert.equal(ready.empty, 0, '有数据时不应显示空态')
  assert.equal(ready.total, `共 ${activities.length} 条`, `页脚统计应为“共 ${activities.length} 条”`)
  assert.equal(ready.placeholder, '全部状态', `状态筛选默认应显示“全部状态”，实际 ${ready.placeholder}`)
  assert.equal(ready.overflow, 0, '活动列表桌面不应横向溢出')
  await shot('S4-活动列表-1440')
  await shot('S4-活动列表-完整', page, true)

  // 关键词与状态筛选：真实请求，结果与接口口径一致
  await page.locator('.activities-status').click()
  await page.getByRole('option', { name: '草稿' }).click()
  await page.waitForResponse((r) => r.url().includes('/activities?') && r.url().includes('status=DRAFT'))
  await page.locator('.list-table .el-table__body tr').first().waitFor({ state: 'visible' })
  assert.equal(await count('.list-table .el-table__body tr'), 1, '草稿筛选应只剩一条')
  await page.getByRole('button', { name: '清除筛选' }).click()
  await page.waitForTimeout(400)
  assert.equal(await count('.list-table .el-table__body tr'), activities.length, '清除筛选应恢复全部活动')

  // 已有数据刷新：保留内容 + 就地提示，不出现骨架或空态
  hold = gate()
  await page.route(slowActivities, async (route) => { await hold; await route.continue() })
  await page.getByRole('button', { name: '搜索' }).click()
  await page.locator('.list-panel__status').waitFor({ state: 'visible' })
  const refreshing = await readList()
  assert.equal(refreshing.rows, activities.length, '刷新时应保留已有行')
  assert.equal(refreshing.skeleton, 0, '刷新时不应退回骨架')
  assert.equal(refreshing.empty, 0, '刷新时不应显示空态')
  await shot('S4-活动列表-刷新')
  release()
  await page.waitForResponse((r) => r.url().includes('/activities?'))
  await dropRoutes()
  await page.locator('.list-panel__status').waitFor({ state: 'detached' })

  // 空态：真实请求返回空集合时只显示空态
  await page.route(slowActivities, (route) => route.fulfill({
    json: { items: [], page: 1, pageSize: 20, total: 0 },
  }))
  await page.getByRole('button', { name: '搜索' }).click()
  await page.locator('[data-list-empty]').waitFor({ state: 'visible' })
  const empty = await readList()
  assert.equal(empty.rows, 0, '空态不应显示数据行')
  assert.equal(empty.skeleton, 0, '空态不应同时显示骨架')
  assert.equal(empty.error, 0, '空态不应同时显示失败')
  assert.ok(await page.locator('[data-list-empty]').innerText().then((t) => t.includes('尚未创建活动')), '负责人空态应说明原因')
  await shot('S4-活动列表-空态')
  await dropRoutes()

  // 失败态：区段错误 + 重试，且不残留骨架／空态
  await page.route(slowActivities, (route) => route.fulfill({
    status: 503,
    contentType: 'application/problem+json',
    body: JSON.stringify({ status: 503, code: 'UNAVAILABLE', detail: '状态核对' }),
  }))
  await page.getByRole('button', { name: '搜索' }).click()
  await page.locator('.list-panel__error').waitFor({ state: 'visible' })
  const failed = await readList()
  assert.equal(failed.rows, 0, '失败态不应显示数据行')
  assert.equal(failed.skeleton, 0, '失败态不应同时显示骨架')
  assert.equal(failed.empty, 0, '失败态不应同时显示空态')
  await shot('S4-活动列表-失败')
  await dropRoutes()
  await page.getByRole('button', { name: '重新加载' }).click()
  await page.locator('.list-table .el-table__body tr').first().waitFor({ state: 'visible' })
  assert.equal(await count('.list-table .el-table__body tr'), activities.length, '重试后应恢复数据')

  // 名称进入详情，主操作入口按权限展示
  await page.locator('.list-table__link').first().click()
  await page.waitForURL(/\/activities\/\d+/)
  assert.ok(page.url().includes(`/org/${org.id}/activities/`), '活动名称应进入详情路由')
  await page.goBack({ waitUntil: 'networkidle' })
  await page.locator('.list-table .el-table__body tr').first().waitFor({ state: 'visible' })
  assert.equal(await page.getByRole('button', { name: '创建活动' }).count(), 1, '负责人应看到创建入口')

  // ---------- 2. 我的任务 ----------
  await page.goto(pages.tasks, { waitUntil: 'networkidle' })
  await page.locator('.list-panel').waitFor({ state: 'visible' })
  const tasksReady = await readList()
  const tasks = (await (await ctx.request.get(`/api/organizations/${org.id}/my-tasks`)).json()).items
  assert.equal(tasksReady.heading, '我的任务')
  assert.equal(tasksReady.rows, tasks.length, '任务行数应与本人任务接口一致')
  assert.equal(tasksReady.skeleton, 0, '有结果时不应显示骨架')
  assert.equal(tasksReady.error, 0, '正常读取不应显示失败')
  if (tasks.length === 0) {
    assert.equal(tasksReady.empty, 1, '本人无任务时应显示空态而不是空表')
    assert.ok(await page.locator('[data-list-empty]').innerText().then((t) => t.includes('暂无分配给你的未完成任务')))
  }
  // 状态筛选：切到“进行中”只显示进行中的任务，数量与接口口径一致
  const inProgress = tasks.filter((t) => t.status === 'IN_PROGRESS').length
  const filterBy = (label) => page.locator('.el-radio-button__inner', { hasText: label }).click()
  await filterBy('进行中')
  await page.waitForTimeout(200)
  assert.equal(await count('.list-table .el-table__body tr'), inProgress, '状态筛选结果应与接口口径一致')
  await filterBy('待处理')
  await page.waitForTimeout(200)
  const pendingFiltered = await page.locator('[data-list-empty]').count()
  if (pendingFiltered === 1) {
    assert.ok(await page.locator('[data-list-empty]').innerText().then((t) => t.includes('当前筛选下没有任务')), '筛选空态应给出返回全部的指引')
  }
  await filterBy('全部')
  await page.waitForTimeout(200)
  assert.equal(await count('.list-table .el-table__body tr'), tasks.length, '切回全部应恢复全部任务')
  await shot('S4-我的任务-1440')
  await shot('S4-我的任务-完整', page, true)

  // 任务加载失败：只显示区段错误与重试
  await page.route('**/api/organizations/*/my-tasks', (route) => route.fulfill({
    status: 503,
    contentType: 'application/problem+json',
    body: JSON.stringify({ status: 503, code: 'UNAVAILABLE', detail: '状态核对' }),
  }))
  await page.reload({ waitUntil: 'networkidle' })
  await page.locator('.list-panel__error').waitFor({ state: 'visible' })
  const tasksFailed = await readList()
  assert.equal(tasksFailed.rows, 0, '任务失败不应显示旧行')
  assert.equal(tasksFailed.skeleton, 0, '任务失败不应同时显示骨架')
  assert.equal(tasksFailed.empty, 0, '任务失败不应同时显示空态')
  await dropRoutes()

  // ---------- 3. 成员管理 ----------
  await page.goto(pages.members, { waitUntil: 'networkidle' })
  await page.locator('.list-table .el-table__body tr').first().waitFor({ state: 'visible' })
  const membersReady = await readList()
  assert.equal(membersReady.heading, '成员管理')
  assert.equal(membersReady.rows, members.length, '成员行数应与成员接口一致')
  assert.equal(membersReady.tables, 2, '负责人应同时看到成员表与已发邀请表')
  assert.equal(membersReady.skeleton, 0, '有数据时不应显示骨架')
  assert.equal(membersReady.error, 0, '正常读取不应显示失败')
  assert.equal(await count('[data-invitations-panel]'), 1, '负责人应看到已发邀请区段')
  assert.equal(await count('[data-invitations-panel] .el-loading-mask'), 0, '邀请区段不叠加遮罩')
  assert.equal(membersReady.overflow, 0, '成员页桌面不应横向溢出')
  await shot('S4-成员管理-1440')
  await shot('S4-成员管理-完整', page, true)

  // 邀请区段独立加载：成员表已出数据时邀请仍在读取，两者状态互不覆盖
  const invitationsHold = gate()
  await page.route('**/api/organizations/*/invitations*', async (route) => { await invitationsHold; await route.continue() })
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.locator('.list-table .el-table__body tr').first().waitFor({ state: 'visible' })
  await page.locator('[data-invitations-panel] [role="status"]').waitFor({ state: 'visible' })
  const split = await readList()
  assert.equal(split.rows, members.length, '邀请读取期间成员表应保持可用')
  assert.equal(split.skeleton, 0, '邀请读取不应把成员表退回骨架')
  await shot('S4-成员管理-邀请加载')
  release()
  await dropRoutes()
  await page.waitForTimeout(500)

  // 搜索无结果：保留筛选条件并给出清除入口
  await page.getByPlaceholder('按用户名搜索').fill('不存在的成员名')
  await page.getByRole('button', { name: '搜索' }).click()
  await page.locator('[data-list-empty]').waitFor({ state: 'visible' })
  const memberEmpty = await readList()
  assert.equal(memberEmpty.rows, 0, '无匹配成员时不应显示旧行')
  assert.equal(memberEmpty.skeleton, 0, '空态不应同时显示骨架')
  assert.equal(memberEmpty.error, 0, '空态不应同时显示失败')
  assert.ok(await page.locator('[data-list-empty]').innerText().then((t) => t.includes('没有匹配的成员')))
  assert.equal(await page.getByRole('button', { name: '清除筛选' }).count(), 1, '筛选无结果应提供清除入口')
  await shot('S4-成员管理-无结果')
  await page.getByRole('button', { name: '清除筛选' }).click()
  await page.locator('.list-table .el-table__body tr').first().waitFor({ state: 'visible' })

  // 搜索自己：负责人身份不能因为“结果里没有当前账号”而消失（审核反馈 2）
  await page.getByPlaceholder('按用户名搜索').fill('demo_owner')
  await page.getByRole('button', { name: '搜索' }).click()
  await page.waitForResponse((r) => r.url().includes('/members?') && r.url().includes('query='))
  const selfSearch = await readList()
  assert.equal(selfSearch.rows, 1, '按自己用户名搜索应只返回一条')
  assert.equal(await page.getByRole('button', { name: '创建邀请' }).count(), 1, '搜索结果里只有自己时管理入口不能消失')
  assert.equal(await page.locator('.list-table').first().getByRole('columnheader', { name: '操作' }).count(), 1, '搜索结果里只有自己时操作列不能消失')
  assert.equal(await page.locator('[data-invitations-panel]').count(), 1, '已发邀请区段不应随搜索结果消失')

  // 搜索其他成员：同一身份下管理入口仍然保留
  await page.getByPlaceholder('按用户名搜索').fill('demo_member')
  await page.getByRole('button', { name: '搜索' }).click()
  await page.waitForResponse((r) => r.url().includes('/members?') && r.url().includes('demo_member'))
  assert.equal(await page.getByRole('button', { name: '创建邀请' }).count(), 1, '搜索其他成员时管理入口不能消失')
  assert.equal(await page.locator('.list-table').first().getByRole('columnheader', { name: '操作' }).count(), 1, '搜索其他成员时操作列不能消失')
  await page.getByRole('button', { name: '清除筛选' }).click()
  await page.locator('.list-table .el-table__body tr').first().waitFor({ state: 'visible' })

  // 邀请加载失败：必须显示区段错误与重试，不能冒充“暂无有效邀请”（审核反馈 3）
  await page.route('**/api/organizations/*/invitations*', (route) => route.fulfill({
    status: 503,
    contentType: 'application/problem+json',
    body: JSON.stringify({ status: 503, code: 'UNAVAILABLE', detail: '状态核对' }),
  }))
  await page.reload({ waitUntil: 'networkidle' })
  await page.locator('.invitations-error').waitFor({ state: 'visible' })
  const invitationsFailed = await readList()
  assert.equal(invitationsFailed.rows, members.length, '邀请失败不应影响成员表')
  assert.equal(invitationsFailed.error, 0, '邀请失败不应把整页变成失败态')
  assert.ok(await page.locator('.invitations-error').innerText().then((t) => t.includes('邀请列表加载失败')), '邀请失败应说明是哪一段失败')
  assert.equal(await page.locator('[data-invitations-panel]').getByRole('button', { name: '重新加载' }).count(), 1, '邀请失败应提供自己的重试入口')
  assert.equal(await page.locator('[data-invitations-panel]').innerText().then((t) => t.includes('暂无有效邀请')), false, '失败不能显示为空态')
  await shot('S4-成员管理-邀请失败')
  await dropRoutes()
  await page.locator('[data-invitations-panel]').getByRole('button', { name: '重新加载' }).click()
  await page.locator('[data-invitations-panel] .el-table__body tr').first().waitFor({ state: 'visible' })
  assert.equal(await page.locator('.invitations-error').count(), 0, '重试成功后应移除错误')
  // 普通成员：没有负责人入口，成员页仍可读
  const memberCtx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const memberCsrf = await (await memberCtx.request.get('/api/auth/csrf')).json()
  const memberLogin = await memberCtx.request.post('/api/auth/login', {
    form: { username: process.env.UI_AUDIT_MEMBER || 'demo_member_yrnn', password: process.env.UI_AUDIT_PASS || 'Demo12345!' },
    headers: { [memberCsrf.headerName]: memberCsrf.token },
  })
  assert.equal(memberLogin.status(), 204, '演示成员登录失败')
  const memberPage = await memberCtx.newPage()
  await memberPage.goto(pages.members, { waitUntil: 'networkidle' })
  await memberPage.locator('.list-table .el-table__body tr').first().waitFor({ state: 'visible' })
  assert.equal(await memberPage.getByRole('button', { name: '创建邀请' }).count(), 0, '普通成员不应看到创建邀请')
  assert.equal(await memberPage.locator('[data-invitations-panel]').count(), 0, '普通成员不应看到已发邀请')
  assert.equal(await memberPage.locator('.list-table').first().getByRole('columnheader', { name: '操作' }).count(), 0, '普通成员不应看到成员操作列')
  assert.ok(await memberPage.getByRole('button', { name: '退出组织' }).count() === 1, '普通成员仍可退出组织')
  const memberRows = await memberPage.locator('.list-table .el-table__body tr').count()
  assert.ok(memberRows > 0, '普通成员应能读取成员列表')
  await shot('S4-成员管理-普通成员', memberPage)
  await memberCtx.close()

  // ---------- 4. 公共样式的作用范围（审核反馈 1 的定点回归） ----------
  // 新增的页头排版只能留在 ListPage 的 scoped 样式里：一旦写成全局 .page-heading，
  // 工作台等标准页会在窄屏跟着换行。这里既查规则归属，也量标准页的实际构图。
  await page.setViewportSize({ width: 390, height: 720 })
  await page.goto(home, { waitUntil: 'networkidle' })
  await page.locator('[data-focus-activity]').waitFor({ state: 'visible' })
  const workbench = await page.evaluate(() => {
    // 只看全局样式表：带 Vue scoped 标记的样式表按设计只作用于自己组件。
    const globalRules = [...document.styleSheets]
      .filter((sheet) => !(sheet.ownerNode?.textContent || '').includes('data-v-'))
      .flatMap((sheet) => { try { return [...sheet.cssRules] } catch { return [] } })
      .filter((rule) => typeof rule.selectorText === 'string'
        && /\.page-(heading|eyebrow|description|actions)\b/.test(rule.selectorText))
      .map((rule) => rule.selectorText)
    const heading = document.querySelector('.page-heading')
    return {
      globalRules,
      headingWrap: heading ? getComputedStyle(heading).flexWrap : '',
      focusTop: Math.round(document.querySelector('[data-focus-activity]').getBoundingClientRect().top),
      threshold: Math.round(document.documentElement.clientHeight * 0.75),
      navHeight: Math.round(document.querySelector('.unified-sidebar').getBoundingClientRect().height),
    }
  })
  assert.deepEqual(workbench.globalRules, [], '页头排版不能定义为全局规则')
  assert.equal(workbench.headingWrap, 'nowrap', '标准页页头不应被公共样式改成换行')
  assert.ok(workbench.focusTop <= workbench.threshold,
    `390 宽工作台重点卡应在首屏内：${workbench.focusTop} > ${workbench.threshold}（导航高 ${workbench.navHeight}）`)
  await shot('S4-工作台-390-回归')
  await page.setViewportSize({ width: 1440, height: 1000 })

  // ---------- 5. 五档宽度与折叠往返（三页共用） ----------
  // 先恢复桌面展开形态，再缩到手机宽度：桌面折叠状态不能影响手机导航可读性结论。
  await page.goto(pages.activities, { waitUntil: 'networkidle' })
  await page.locator('.list-panel').first().waitFor({ state: 'visible' })
  if (await page.getByRole('button', { name: '展开侧栏', exact: true }).count() > 0) {
    await page.getByRole('button', { name: '展开侧栏', exact: true }).click()
    await page.waitForTimeout(300)
  }
  for (const [name, url] of [['活动列表', pages.activities], ['我的任务', pages.tasks], ['成员管理', pages.members]]) {
    await page.goto(url, { waitUntil: 'networkidle' })
    await page.locator('.list-panel').first().waitFor({ state: 'visible' })
    for (const width of [320, 390, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 1000 })
      await page.waitForTimeout(320)
      const state = await readList()
      assert.equal(state.overflow, 0, `${name} ${width} 出现整页横向溢出`)
      assert.ok(state.heading.length > 0 && state.panelHeading.length > 0, `${name} ${width} 标题应可读`)
      if (width <= 390) {
        const labels = await page.evaluate(() => [...document.querySelectorAll('.u-item .u-label')]
          .map((e) => ({ text: e.textContent.trim(), display: getComputedStyle(e).display, width: Math.round(e.getBoundingClientRect().width) })))
        const hidden = labels.filter((x) => x.display === 'none' || x.width === 0)
        assert.deepEqual(hidden, [], `${name} ${width} 手机导航文字应保留`)
        assert.ok(state.tableScroll || state.rows === 0, `${name} ${width} 宽表应在区段内横向滚动`)
      }
      results.widths.push({ page: name, width, overflow: state.overflow, rows: state.rows, tableScroll: state.tableScroll })
      if (width === 390) {
        await shot(`S4-${name}-390`, page, true)
      }
    }
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(pages.activities, { waitUntil: 'networkidle' })
  const desktopFirstRow = await page.locator('.list-table .el-table__body tr').first().boundingBox()
  await page.getByRole('button', { name: '折叠侧栏', exact: true }).click()
  await page.waitForTimeout(300)
  const collapsedFirstRow = await page.locator('.list-table .el-table__body tr').first().boundingBox()
  assert.ok(collapsedFirstRow.width > desktopFirstRow.width, '折叠侧栏后列表应用满宽度')
  await shot('S4-活动列表-折叠')
  await page.getByRole('button', { name: '展开侧栏', exact: true }).click()
  await page.waitForTimeout(300)

  assert.deepEqual(errors, [], `页面运行错误：${errors.join('；')}`)
  results.checks.push(
    '活动列表：加载骨架／空态／失败态互斥、旧数据刷新保留内容、状态筛选与清除、名称进详情、创建入口按权限',
    '我的任务：行数与接口一致、状态筛选口径、筛选空态指引、失败只显示区段错误',
    '成员管理：行数与接口一致、邀请区段独立加载与独立失败态、搜索自己／他人后管理入口不丢、搜索无结果可清除、普通成员无负责人入口',
    '公共样式作用范围：页头排版无全局规则，390 宽工作台重点卡仍在首屏内',
    '三页五档宽度无整页溢出、手机导航文字保留、宽表区段内滚动、折叠侧栏后列表铺满',
  )
  results.orgId = org.id
  results.activityCount = activities.length
  results.memberCount = members.length
  await writeFile(new URL('S4结果.json', out), JSON.stringify(results, null, 2))
  const cards = [
    ['活动列表 1440', 'S4-活动列表-1440.png'],
    ['活动列表 手机 390', 'S4-活动列表-390.png'],
    ['活动列表 首次加载', 'S4-活动列表-加载.png'],
    ['活动列表 刷新保留内容', 'S4-活动列表-刷新.png'],
    ['活动列表 空态', 'S4-活动列表-空态.png'],
    ['活动列表 失败态', 'S4-活动列表-失败.png'],
    ['我的任务 1440', 'S4-我的任务-1440.png'],
    ['成员管理 1440', 'S4-成员管理-1440.png'],
    ['成员管理 手机 390', 'S4-成员管理-390.png'],
    ['成员管理 邀请加载', 'S4-成员管理-邀请加载.png'],
    ['成员管理 邀请失败', 'S4-成员管理-邀请失败.png'],
    ['工作台 390 公共样式回归', 'S4-工作台-390-回归.png'],
  ]
  await writeFile(new URL('S4逐页对照.html', out), `<!doctype html><html lang="zh-CN"><meta charset="utf-8">`
    + `<meta name="viewport" content="width=device-width"><title>S4 逐页还原对照</title>`
    + '<style>body{font:15px system-ui;margin:24px;background:#f3f3ef;color:#1a1814}'
    + 'main{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}figure{margin:0}'
    + 'img{width:100%;vertical-align:top;border:1px solid #ddd}figcaption{padding:8px 0;font-weight:600}'
    + '@media(max-width:800px){main{grid-template-columns:1fr}}</style>'
    + '<h1>S4 活动列表／我的任务／成员管理</h1>'
    + '<p>1440×1000 与 390 宽真实数据截图；加载、刷新、空态、失败为浏览器响应替身，已在 S4结果.json 标注。</p>'
    + `<main>${cards.map(([label, file]) => `<figure><figcaption>${label}</figcaption><img src="${file}" alt="${label}"></figure>`).join('')}</main></html>`)
  console.log(JSON.stringify(results, null, 2))
} finally {
  await browser.close()
}
