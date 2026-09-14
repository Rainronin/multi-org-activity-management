// 工作台视觉还原核对：登录演示账号，同视口截图并断言新结构存在、数值与接口一致、无横向溢出。
// 只读页面 + 一次登录，不写业务数据。
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
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

  // ---------- 活动详情：功能区进入页签，首屏只留状态与关键信息（U03） ----------
  const activities = await (await ctx.request.get(`/api/organizations/${org.id}/activities?pageSize=50`)).json()
  const detailTarget = activities.items.find((item) => item.status !== 'DRAFT') ?? activities.items[0]
  assert.ok(detailTarget, '组织没有可用于详情核对的活动')
  const detailPage = await ctx.newPage()
  const detailErrors = []
  detailPage.on('pageerror', (e) => detailErrors.push(e.message))
  await detailPage.setViewportSize({ width: 1440, height: 1000 })
  await detailPage.goto(`/org/${org.id}/activities/${detailTarget.id}`, { waitUntil: 'networkidle' })
  await detailPage.waitForSelector('.detail-tabs', { timeout: 20000 })
  await detailPage.waitForTimeout(500)
  const detail = {
    status: detailTarget.status,
    tabLabels: (await detailPage.locator('.detail-tabs .el-tabs__item').allInnerTexts()).map((x) => x.trim()),
    bodyHeight: await detailPage.evaluate(() => document.body.scrollHeight),
    statusHintShown: (await detailPage.locator('.detail-status__hint').count()) > 0,
  }
  const tabToSwitch = detail.tabLabels.find((label) => label !== '概览')
  if (tabToSwitch) {
    await detailPage.locator('.detail-tabs .el-tabs__item', { hasText: tabToSwitch }).first().click()
    await detailPage.waitForTimeout(600)
  }
  const tabSwitched = detail.tabLabels.length > 1
    ? (await detailPage.locator('.el-tabs__item.is-active').first().innerText()).trim()
    : '概览'
  const detailOverflow = await detailPage.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  const detailShot = fileURLToPath(new URL('../验证截图/UI核对-活动详情-1440.png', import.meta.url))
  await detailPage.screenshot({ path: detailShot, fullPage: true })
  assert.equal(detailOverflow.scrollWidth - detailOverflow.clientWidth, 0, '详情页无横向溢出')
  assert.equal(detailErrors.length, 0, `详情页错误：${detailErrors.join('; ')}`)
  assert.ok(detail.tabLabels.length >= 3, `详情页页签数量不足：${detail.tabLabels.length}`)
  assert.ok(detail.statusHintShown, '详情首屏缺少状态与下一步说明')

  // ---------- 侧栏折叠（图标岛）与减少动效 ----------
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(`/org/${org.id}`, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-focus-activity]', { timeout: 20000 })
  const collapseButton = page.getByRole('button', { name: /折叠侧栏|展开侧栏/ }).first()
  await collapseButton.click()
  await page.waitForTimeout(500)
  const collapsedAttr = await page.locator('[data-nav-collapsed]').first().getAttribute('data-nav-collapsed')
  const navShot = fileURLToPath(new URL('../验证截图/UI核对-侧栏折叠.png', import.meta.url))
  await page.screenshot({ path: navShot })
  await collapseButton.click()
  await page.waitForTimeout(300)

  const reducedPage = await ctx.newPage()
  await reducedPage.emulateMedia({ reducedMotion: 'reduce' })
  await reducedPage.setViewportSize({ width: 1440, height: 1000 })
  await reducedPage.goto(`/org/${org.id}`, { waitUntil: 'networkidle' })
  await reducedPage.waitForSelector('[data-focus-activity]', { timeout: 20000 })
  const reducedTransition = await reducedPage.locator('.spotlight').first()
    .evaluate((element) => getComputedStyle(element).transitionDuration)

  // R03：用独立匿名 context，避免已登录守卫把 /login 跳到账号页（上一版证据就拍错了页面）
  const anonymous = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const loginPage = await anonymous.newPage()
  const loginErrors = []
  loginPage.on('pageerror', (e) => loginErrors.push(e.message))
  await loginPage.goto(`${base}/login`, { waitUntil: 'networkidle' })
  await loginPage.waitForSelector('.auth-panel form, form', { timeout: 20000 })
  await loginPage.waitForTimeout(400)
  const loginUrl = loginPage.url()
  const loginFormVisible = (await loginPage.locator('input[type="password"]').count()) > 0
  assert.ok(loginUrl.includes('/login'), `登录页证据无效：最终路径 ${loginUrl}`)
  assert.ok(loginFormVisible, '登录页缺少密码输入框')
  const loginShot = fileURLToPath(new URL('../验证截图/UI核对-登录-1440.png', import.meta.url))
  await loginPage.screenshot({ path: loginShot })

  // R02：AI 卡定向入口（总结→执行与复盘、方案→方案页签）
  const aiPage = await ctx.newPage()
  const aiErrors = []
  aiPage.on('pageerror', (e) => aiErrors.push(e.message))
  await aiPage.setViewportSize({ width: 1440, height: 1000 })
  await aiPage.goto(`/org/${org.id}`, { waitUntil: 'networkidle' })
  await aiPage.waitForSelector('[data-ai-card]', { timeout: 20000 })
  const aiTargets = {}
  for (const mode of ['总结', '方案']) {
    await aiPage.locator('[data-ai-card] .assistant-card__modes button', { hasText: mode }).first().click()
    await aiPage.waitForTimeout(300)
    const enabled = await aiPage.locator('[data-ai-open]').first().isEnabled()
    if (!enabled) {
      aiTargets[mode] = { skipped: '当前组织没有该模式的候选活动' }
      continue
    }
    await aiPage.locator('[data-ai-open]').first().click()
    await aiPage.waitForSelector('.detail-tabs', { timeout: 20000 })
    await aiPage.waitForTimeout(500)
    aiTargets[mode] = {
      url: aiPage.url().replace(base, ''),
      activeTab: (await aiPage.locator('.el-tabs__item.is-active').first().innerText()).trim(),
    }
    await aiPage.goto(`/org/${org.id}`, { waitUntil: 'networkidle' })
    await aiPage.waitForSelector('[data-ai-card]', { timeout: 20000 })
  }
  assert.ok(
    Object.values(aiTargets).some((item) => item.activeTab === '执行与复盘' || item.activeTab === '方案'),
    `AI 定向入口未定位到编辑区：${JSON.stringify(aiTargets)}`,
  )

  // R04：手机首屏可用性（导航不得占据大半屏）与 320／768／1024 溢出
  const narrowChecks = []
  for (const width of [320, 390, 768, 1024]) {
    const shotPage = await ctx.newPage()
    await shotPage.setViewportSize({ width, height: 720 })
    await shotPage.goto(`/org/${org.id}`, { waitUntil: 'networkidle' })
    await shotPage.waitForSelector('[data-focus-activity]', { timeout: 20000 })
    await shotPage.waitForTimeout(400)
    const measured = await shotPage.evaluate(() => {
      const focus = document.querySelector('[data-focus-activity]')
      const nav = document.querySelector('.unified-sidebar')
      return {
        clientWidth: document.documentElement.clientWidth,
        scrollWidth: document.documentElement.scrollWidth,
        focusTop: focus ? Math.round(focus.getBoundingClientRect().top) : null,
        navWidth: nav ? Math.round(nav.getBoundingClientRect().width) : null,
        navHeight: nav ? Math.round(nav.getBoundingClientRect().height) : null,
      }
    })
    narrowChecks.push({ width, ...measured, overflowPx: measured.scrollWidth - measured.clientWidth })
    if (width <= 390) {
      await shotPage.screenshot({
        path: fileURLToPath(new URL(`../验证截图/UI核对-工作台-${width}.png`, import.meta.url)),
      })
    }
    await shotPage.close()
  }
  const mobileFirstScreen = narrowChecks.find((item) => item.width === 390)

  // ---------- 同视口演示对照（U06）----------
  await mkdir(new URL('../验证截图/UI独立审核/', import.meta.url), { recursive: true })
  const demoContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const demoPage = await demoContext.newPage()
  const demoErrors = []
  demoPage.on('pageerror', (e) => demoErrors.push(e.message))
  let demoReachable = true
  try {
    await demoPage.goto('http://127.0.0.1:5173/', { waitUntil: 'networkidle', timeout: 20000 })
    await demoPage.waitForTimeout(1500)
    await demoPage.screenshot({
      path: fileURLToPath(new URL('../验证截图/UI独立审核/演示-工作台-1440.png', import.meta.url)),
    })
    await demoPage.goto('http://127.0.0.1:5173/?view=login', { waitUntil: 'networkidle', timeout: 20000 })
    await demoPage.waitForTimeout(800)
    await demoPage.screenshot({
      path: fileURLToPath(new URL('../验证截图/UI独立审核/演示-登录-1440.png', import.meta.url)),
    })
  } catch (error) {
    demoReachable = false
    demoErrors.push(`演示服务不可达：${error.message}`)
  }
  await writeFile(new URL('../验证截图/UI独立审核/对照-正式与演示.html', import.meta.url), `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>UI 对照：正式实现 vs 已确认 Demo（1440×1000 同视口）</title>
<style>
  body { font: 14px/1.6 "Microsoft YaHei", sans-serif; margin: 0; padding: 24px; background: #f6f5f2; color: #1a1814; }
  h1 { font-size: 20px; } h2 { font-size: 16px; margin-top: 32px; }
  .pair { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
  figure { margin: 0; background: #fff; border: 1px solid #e6e4df; border-radius: 10px; padding: 10px; }
  figcaption { font-size: 12px; color: #6b665e; margin-bottom: 8px; }
  img { width: 100%; display: block; border-radius: 6px; }
  .note { background: #fff7f0; border: 1px solid #f0d5c4; border-radius: 10px; padding: 12px 16px; }
</style></head><body>
<h1>UI 对照：正式实现 vs 已确认 Demo（同视口 1440×1000）</h1>
<p class="note">比较布局与层级，不比较姓名与数值：正式实现使用真实业务数据，Demo 使用虚构样例数据。演示服务未启动时右侧参考图缺失。</p>
<h2>工作台</h2>
<div class="pair">
  <figure><figcaption>已确认 Demo（127.0.0.1:5173）</figcaption><img src="演示-工作台-1440.png" alt="演示工作台"></figure>
  <figure><figcaption>正式实现（127.0.0.1:5174，真实数据）</figcaption><img src="../UI核对-工作台-1440.png" alt="正式工作台"></figure>
</div>
<h2>登录</h2>
<div class="pair">
  <figure><figcaption>已确认 Demo</figcaption><img src="演示-登录-1440.png" alt="演示登录"></figure>
  <figure><figcaption>正式实现</figcaption><img src="../UI核对-登录-1440.png" alt="正式登录"></figure>
</div>
<h2>其他核对截图</h2>
<ul>
  <li>正式活动详情（页签化）：<a href="../UI核对-活动详情-1440.png">UI核对-活动详情-1440.png</a></li>
  <li>正式侧栏折叠（图标岛）：<a href="../UI核对-侧栏折叠.png">UI核对-侧栏折叠.png</a></li>
  <li>正式手机宽度（390）：<a href="../UI核对-工作台-390.png">UI核对-工作台-390.png</a></li>
</ul>
</body></html>
`, 'utf8')

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

  const report = {
    check: '工作台视觉还原核对（Demo 阅读顺序：重点卡＋AI 卡 → KPI → 图表 → 明细／待办）',
    org: { id: org.id, name: org.name },
    structure,
    spotlightTitle,
    kpiValues,
    checks,
    detail: {
      status: detail.status,
      tabLabels: detail.tabLabels,
      switchedTab: tabSwitched,
      statusHintShown: detail.statusHintShown,
      bodyHeightPx: detail.bodyHeight,
      overflowPx: detailOverflow.scrollWidth - detailOverflow.clientWidth,
      pageErrors: detailErrors,
    },
    shell: {
      navCollapsedAttr: collapsedAttr,
      reducedMotionTransitionDuration: reducedTransition,
      loginEvidence: { url: loginUrl, formVisible: loginFormVisible, pageErrors: loginErrors },
    },
    aiTargetedEntry: aiTargets,
    narrowWidths: narrowChecks,
    mobileFirstScreenOk: Boolean(mobileFirstScreen && mobileFirstScreen.focusTop !== null
      && mobileFirstScreen.focusTop < mobileFirstScreen.width),
    demoComparison: {
      demoReachable,
      demoErrors,
      html: '验证截图/UI独立审核/对照-正式与演示.html',
    },
    overflowPx: overflow.scrollWidth - overflow.clientWidth,
    mobileOverflowPx: mobileOverflow.scrollWidth - mobileOverflow.clientWidth,
    pageErrors: errors,
    screenshots: ['验证截图/UI核对-工作台-1440.png', '验证截图/UI核对-工作台-390.png'],
  }
  // 证据留档：文本化输出供审核引用（截图目录不入库，报告正文入库）
  await writeFile(new URL('../验证截图/UI独立审核/ui核对输出.json', import.meta.url),
    JSON.stringify(report, null, 1), 'utf8')
  console.log(JSON.stringify(report, null, 1))
} finally {
  await browser.close()
}
