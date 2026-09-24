// S5 逐页核对：登录／注册／恢复／账号／组织设置／公开参与。
// 真实账号与数据；状态反例只在浏览器内拦截响应，不写业务库、不调用模型。
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
    '未执行注册、恢复、改密、重签与配置保存等写操作；未调用任何模型服务',
  ],
}
let release = null
const gate = () => new Promise((resolve) => { release = resolve })

/** 页头与状态：一页一个 h1、可见文案、以及四种状态的互斥关系。 */
const readPage = (target) => target.evaluate(() => ({
  h1: [...document.querySelectorAll('h1')].map((e) => e.textContent.trim()),
  h2: [...document.querySelectorAll('h2')].map((e) => e.textContent.trim()),
  overflow: document.documentElement.scrollWidth - window.innerWidth,
  skeleton: document.querySelectorAll('[data-public-skeleton], [data-public-detail-skeleton], [data-ai-config-skeleton]').length,
  empty: document.querySelectorAll('[data-public-empty], [data-public-detail-missing]').length,
  error: document.querySelectorAll('.public-error, .ai-config__error').length,
  cards: document.querySelectorAll('.public-card').length,
  loadingMask: document.querySelectorAll('.el-loading-mask').length,
}))

const shot = async (name, target, fullPage = false) => {
  await target.screenshot({ path: fileURLToPath(new URL(`${name}.png`, out)), fullPage })
  results.screenshots.push(`${name}.png`)
}

try {
  // ---------- 1. 登录／注册／恢复（匿名会话） ----------
  const anon = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const authPage = await anon.newPage()
  const authErrors = []
  authPage.on('pageerror', (e) => authErrors.push(e.message))

  for (const [name, path, heading] of [
    ['登录', '/login', '继续，把事情做好。'],
    ['注册', '/register', '开始，一起做活动。'],
    ['恢复', '/recover', '用恢复码重新取得控制权。'],
  ]) {
    await authPage.goto(path, { waitUntil: 'networkidle' })
    const state = await readPage(authPage)
    assert.deepEqual(state.h1, [heading], `${name}页应只有一个 h1 且文案正确`)
    assert.equal(state.overflow, 0, `${name}页桌面横向溢出`)
    // 左右分屏：品牌区与表单区各占一列且都可见
    const split = await authPage.evaluate(() => ({
      brand: Math.round(document.querySelector('.auth-brand').getBoundingClientRect().width),
      panel: Math.round(document.querySelector('.auth-panel').getBoundingClientRect().width),
      inputs: document.querySelectorAll('.auth-content input').length,
    }))
    assert.ok(split.brand > 0 && split.panel > 0, `${name}页应保持左右分屏`)
    assert.ok(split.inputs >= 2, `${name}页表单字段应可见`)
    await shot(`S5-${name}-1440`, authPage)
  }

  // N01 定点回归：品牌区必须有环形装饰与玻璃流程卡，标语与页边距按认证专用 token
  await authPage.setViewportSize({ width: 1440, height: 1000 })
  await authPage.goto('/login', { waitUntil: 'networkidle' })
  const brand = await authPage.evaluate(() => {
    const decor = document.querySelector('.auth-decor')
    const flow = document.querySelector('.auth-flow')
    const slogan = document.querySelector('.auth-slogan')
    const brandBox = document.querySelector('.auth-brand')
    return {
      decor: decor ? decor.getBoundingClientRect().width : 0,
      flow: flow ? Math.round(flow.getBoundingClientRect().width) : 0,
      sloganFont: parseFloat(getComputedStyle(slogan).fontSize),
      sloganLeft: Math.round(slogan.getBoundingClientRect().left),
      brandPadding: getComputedStyle(brandBox).padding,
      breakDisplay: getComputedStyle(document.querySelector('.auth-slogan__break')).display,
    }
  })
  assert.ok(brand.decor > 0, '品牌区应有环形装饰')
  assert.ok(brand.flow > 0, '品牌区应有玻璃流程卡')
  assert.ok(brand.sloganFont >= 64, `桌面标语应达到 Demo 量级，实际 ${brand.sloganFont}`)
  assert.ok(brand.sloganLeft >= 48, `品牌左边距应明显大于通用页边距，实际 ${brand.sloganLeft}`)
  assert.ok(brand.brandPadding.startsWith('45px'), `认证页应使用认证专用边距，实际 ${brand.brandPadding}`)
  // 手机：装饰与流程卡让位、标语允许自然折行、字段与按钮仍可见
  await authPage.setViewportSize({ width: 390, height: 844 })
  await authPage.reload({ waitUntil: 'networkidle' })
  const phone = await authPage.evaluate(() => ({
    decor: document.querySelectorAll('.auth-decor:not([hidden])').length && getComputedStyle(document.querySelector('.auth-decor')).display,
    flow: getComputedStyle(document.querySelector('.auth-flow')).display,
    breakDisplay: getComputedStyle(document.querySelector('.auth-slogan__break')).display,
    overflow: document.documentElement.scrollWidth - window.innerWidth,
    inputs: document.querySelectorAll('.auth-content input').length,
    button: Math.round(document.querySelector('.auth-submit').getBoundingClientRect().height),
  }))
  assert.equal(phone.decor, 'none', '手机应压缩环形装饰')
  assert.equal(phone.flow, 'none', '手机应压缩玻璃流程卡')
  assert.equal(phone.breakDisplay, 'none', '手机不应强制三行标语')
  assert.equal(phone.overflow, 0, '手机认证页不应横向溢出')
  assert.ok(phone.inputs >= 2, '手机不得隐藏字段')
  assert.ok(phone.button >= 40, `手机主按钮不应只有 32px，实际 ${phone.button}`)
  await shot('S5-登录-390', authPage)
  await authPage.setViewportSize({ width: 1440, height: 1000 })

  // 登录表单：首次校验在失焦触发，不在输入过程中逐字报错；失败给出可读原因
  await authPage.goto('/login', { waitUntil: 'networkidle' })
  const loginForm = authPage.locator('.auth-content')
  const usernameField = loginForm.getByPlaceholder('注册时设置的用户名')
  await usernameField.click()
  // 输入过程中不应出现错误提示（规范 7.1：首次校验在 blur）
  await usernameField.fill('不存在的账号_核对')
  assert.equal(await loginForm.locator('.el-form-item.is-error').count(), 0, '输入过程中不应逐字报错')
  await usernameField.fill('')
  await loginForm.getByPlaceholder('请输入密码').click()
  await loginForm.getByText('请输入用户名', { exact: true }).waitFor({ state: 'visible' })
  assert.equal(await loginForm.locator('.el-form-item.is-error').count(), 1, '清空后失焦应只报当前字段')
  await shot('S5-登录-失焦校验', authPage)
  // 真实提交一次错误凭据：应给出可读的失败原因，且不跳转
  await loginForm.getByPlaceholder('注册时设置的用户名').fill('不存在的账号_核对')
  await loginForm.getByPlaceholder('请输入密码').fill('WrongPass123')
  await loginForm.getByRole('button', { name: '进入工作区' }).click()
  await loginForm.getByText('账号或密码不正确。', { exact: true }).waitFor({ state: 'visible' })
  assert.ok(authPage.url().includes('/login'), '登录失败不应跳转')
  await shot('S5-登录-失败提示', authPage)
  // 匿名会话不得直接进入组织工作区
  await authPage.goto('/org/4922/activities', { waitUntil: 'networkidle' })
  assert.ok(authPage.url().includes('/login'), '未登录访问组织路由应回到登录页')
  assert.equal(await authPage.locator('.unified-sidebar').count(), 0, '未登录不得渲染组织导航')
  assert.deepEqual(authErrors, [], `认证页运行错误：${authErrors.join('；')}`)
  await anon.close()

  // ---------- 2. 公开参与（匿名） ----------
  const publicCtx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const publicPage = await publicCtx.newPage()
  const publicErrors = []
  publicPage.on('pageerror', (e) => publicErrors.push(e.message))
  const hold = gate()
  await publicPage.route('**/api/public/activities?*', async (route) => { await hold; await route.continue() })
  await publicPage.goto('/public/activities', { waitUntil: 'domcontentloaded' })
  await publicPage.locator('[data-public-skeleton]').waitFor({ state: 'visible' })
  const publicLoading = await readPage(publicPage)
  assert.equal(publicLoading.cards, 0, '首屏加载时不应有卡片')
  assert.equal(publicLoading.empty, 0, '首屏加载时不应同时显示空态')
  assert.equal(publicLoading.error, 0, '首屏加载时不应同时显示失败')
  release()
  await publicPage.waitForResponse((r) => r.url().includes('/api/public/activities?'))
  await publicPage.unrouteAll({ behavior: 'ignoreErrors' })
  await publicPage.locator('.public-card').first().waitFor({ state: 'visible' })
  const publicReady = await readPage(publicPage)
  assert.equal(publicReady.h1.length, 1, '公开列表应只有一个 h1')
  assert.equal(publicReady.skeleton, 0, '有数据时不应显示骨架')
  assert.equal(publicReady.overflow, 0, '公开列表桌面横向溢出')
  // 公开页不得出现内部信息：字段层核对响应，展示层核对标签（页脚说明里的“预算”是免责文字，不算泄露）
  const publicRaw = await (await publicCtx.request.get('/api/public/activities?page=1&pageSize=12')).json()
  const publicKeys = Object.keys(publicRaw.items[0])
  for (const internal of ['budget', 'proposal', 'ownerUsername', 'summary', 'reviewNote', 'tasks', 'materials']) {
    assert.equal(publicKeys.includes(internal), false, `公开接口不得下发内部字段：${internal}`)
  }
  const publicText = await publicPage.locator('main').innerText()
  for (const label of ['预算（元）', '已确认支出', '活动负责人', '组织负责人', '方案', '任务分工', '附件']) {
    assert.equal(publicText.includes(label), false, `公开列表不得出现内部字段：${label}`)
  }
  await shot('S5-公开活动-1440', publicPage)
  await shot('S5-公开活动-完整', publicPage, true)

  // 空态与失败态互斥
  await publicPage.route('**/api/public/activities?*', (route) => route.fulfill({
    json: { items: [], page: 1, pageSize: 12, total: 0 },
  }))
  await publicPage.reload({ waitUntil: 'networkidle' })
  await publicPage.locator('[data-public-empty]').waitFor({ state: 'visible' })
  const publicEmpty = await readPage(publicPage)
  assert.equal(publicEmpty.cards, 0, '空态不应显示卡片')
  assert.equal(publicEmpty.skeleton, 0, '空态不应同时显示骨架')
  assert.equal(publicEmpty.error, 0, '空态不应同时显示失败')
  await shot('S5-公开活动-空态', publicPage)
  await publicPage.unrouteAll({ behavior: 'ignoreErrors' })
  await publicPage.route('**/api/public/activities?*', (route) => route.fulfill({
    status: 503,
    contentType: 'application/problem+json',
    body: JSON.stringify({ status: 503, code: 'UNAVAILABLE', detail: '状态核对' }),
  }))
  await publicPage.reload({ waitUntil: 'networkidle' })
  await publicPage.locator('.public-error').waitFor({ state: 'visible' })
  const publicFailed = await readPage(publicPage)
  assert.equal(publicFailed.cards, 0, '失败不应残留卡片')
  assert.equal(publicFailed.skeleton, 0, '失败不应同时显示骨架')
  assert.equal(publicFailed.empty, 0, '失败不应同时显示空态')
  assert.equal(await publicPage.getByRole('button', { name: '重新加载' }).count(), 1, '失败应提供重试入口')
  await shot('S5-公开活动-失败', publicPage)
  await publicPage.unrouteAll({ behavior: 'ignoreErrors' })
  await publicPage.reload({ waitUntil: 'networkidle' })
  await publicPage.locator('.public-card').first().waitFor({ state: 'visible' })

  // 公开详情：真实活动 + 失败终态
  const publicList = (await (await publicCtx.request.get('/api/public/activities?page=1&pageSize=12')).json()).items
  assert.ok(publicList.length > 0, '需要既有公开活动样例')
  const detailUrl = `/public/activities/${publicList[0].id}`
  await publicPage.goto(detailUrl, { waitUntil: 'networkidle' })
  await publicPage.locator('.public-facts').waitFor({ state: 'visible' })
  const detailState = await readPage(publicPage)
  assert.equal(detailState.h1.length, 1, '公开详情应只有一个 h1')
  assert.equal(detailState.overflow, 0, '公开详情桌面横向溢出')
  assert.equal(await publicPage.locator('.public-facts div').count(), 7, '公开详情应展示 7 项公开事实')
  const detailKeys = Object.keys((await (await publicCtx.request.get(`/api/public/activities/${publicList[0].id}`)).json()))
  assert.equal(detailKeys.includes('budget'), false, '公开详情接口不得下发预算')
  const detailText = await publicPage.locator('.public-detail').innerText()
  for (const label of ['预算（元）', '已确认支出', '活动负责人', '组织负责人']) {
    assert.equal(detailText.includes(label), false, `公开详情不得出现内部字段：${label}`)
  }
  await shot('S5-公开详情-1440', publicPage, true)

  await publicPage.route('**/api/public/activities/*', (route) => route.fulfill({
    status: 503,
    contentType: 'application/problem+json',
    body: JSON.stringify({ status: 503, code: 'UNAVAILABLE', detail: '状态核对' }),
  }))
  await publicPage.reload({ waitUntil: 'networkidle' })
  await publicPage.locator('[data-public-detail-missing]').waitFor({ state: 'visible' })
  const detailFailed = await readPage(publicPage)
  assert.equal(detailFailed.h1.length, 0, '详情失败态不保留标题')
  assert.equal(detailFailed.skeleton, 0, '详情失败不应同时显示骨架')
  const detailMissingText = await publicPage.locator('[data-public-detail-missing]').innerText()
  assert.ok(detailMissingText.trim().length > 0, '详情失败必须说明原因，不能留空白页')
  assert.equal(await publicPage.getByRole('button', { name: '重新加载' }).count(), 1, '详情失败应提供重试入口')
  assert.equal(await publicPage.getByRole('link', { name: '返回公开活动' }).count(), 1, '详情失败应给出返回入口')
  await shot('S5-公开详情-失败', publicPage)
  await publicPage.unrouteAll({ behavior: 'ignoreErrors' })
  assert.deepEqual(publicErrors, [], `公开页运行错误：${publicErrors.join('；')}`)
  await publicCtx.close()

  // ---------- 3. 账号页与组织设置（已登录负责人） ----------
  const ctx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
  assert.equal((await ctx.request.post('/api/auth/login', {
    form: { username: process.env.UI_AUDIT_USER || 'demo_owner_yrnn', password: process.env.UI_AUDIT_PASS || 'Demo12345!' },
    headers: { [csrf.headerName]: csrf.token },
  })).status(), 204, '演示负责人登录失败')
  const org = (await (await ctx.request.get('/api/me/organizations')).json()).items[0]
  const page = await ctx.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))

  await page.goto('/', { waitUntil: 'networkidle' })
  const account = await readPage(page)
  assert.deepEqual(account.h1, ['我的工作区'], '账号页应只有一个 h1')
  assert.equal(account.overflow, 0, '账号页桌面横向溢出')
  assert.equal(await page.getByRole('button', { name: '加入组织' }).count(), 1, '账号页应提供加入组织入口')
  assert.equal(await page.getByRole('button', { name: '创建组织' }).count(), 1, '账号页应提供创建组织入口')
  await page.getByRole('button', { name: '加入组织' }).click()
  await page.getByRole('dialog', { name: '加入组织' }).waitFor({ state: 'visible' })
  await page.getByRole('dialog', { name: '加入组织' }).getByRole('button', { name: /关闭|Close/ }).click()
  await shot('S5-账号-1440', page, true)

  await page.goto(`/org/${org.id}/settings`, { waitUntil: 'networkidle' })
  await page.locator('.ai-config__form').waitFor({ state: 'visible' })
  const settings = await readPage(page)
  assert.deepEqual(settings.h1, ['组织设置'], '设置页应只有一个 h1')
  assert.equal(settings.overflow, 0, '设置页桌面横向溢出')
  assert.equal(settings.skeleton, 0, '配置已加载时不应显示骨架')
  assert.equal(settings.error, 0, '正常读取不应显示失败')
  const config = (await (await ctx.request.get(`/api/organizations/${org.id}/ai/config`)).json())
  // 字段定位沿用 audit_p8_ai.mjs 的写法：属性可能落在输入框或包装元素上
  const fieldInput = (name) => page.locator(`input[data-ai-field="${name}"], [data-ai-field="${name}"] input`).first()
  assert.equal(await fieldInput('endpoint').inputValue(), config.endpoint ?? '', '端点字段应与接口一致')
  assert.equal(await fieldInput('model').inputValue(), config.model ?? '', '模型字段应与接口一致')
  assert.equal(await fieldInput('apiKey').inputValue(), '', '已配置的密钥不得回显到输入框')
  // N04：字段必须在白色内容面板里，不再直接排在页面底色上
  const settingsPanel = await page.evaluate(() => {
    const panel = document.querySelector('[data-ai-config-panel]')
    const style = getComputedStyle(panel)
    return { bg: style.backgroundColor, border: style.borderTopWidth, radius: style.borderTopLeftRadius, padding: style.padding }
  })
  assert.notEqual(settingsPanel.bg, 'rgba(0, 0, 0, 0)', '设置字段应落在有底色的面板内')
  assert.ok(parseFloat(settingsPanel.border) > 0, '设置面板应有边框')
  assert.ok(parseFloat(settingsPanel.padding) >= 16, '设置面板应有卡片内边距')
  // N03：地址失焦校验（输入非法值后离开字段即报错，并同步 aria-invalid）
  await fieldInput('endpoint').click()
  await fieldInput('endpoint').clear()
  await fieldInput('endpoint').fill('not-a-url')
  await page.locator('.ai-config__group').click()
  await page.locator('.el-form-item__error').first().waitFor({ state: 'visible' })
  assert.ok(await page.locator('.el-form-item__error').first().innerText().then((t) => t.includes('完整地址')), '非法地址应给出可读错误')
  assert.equal(await fieldInput('endpoint').getAttribute('aria-invalid'), 'true', '错误字段应标记 aria-invalid')
  await shot('S5-组织设置-失焦校验', page, true)
  await page.reload({ waitUntil: 'networkidle' })
  await page.locator('.ai-config__form').waitFor({ state: 'visible' })
  await shot('S5-组织设置-1440', page, true)

  // 配置骨架与失败互斥
  const settingsHold = gate()
  await page.route('**/api/organizations/*/ai/config', async (route) => { await settingsHold; await route.continue() })
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.locator('[data-ai-config-skeleton]').waitFor({ state: 'visible' })
  const settingsLoading = await readPage(page)
  assert.equal(settingsLoading.error, 0, '配置加载时不应同时显示失败')
  assert.equal(settingsLoading.loadingMask, 0, '配置加载不应叠加整页遮罩')
  await shot('S5-组织设置-加载', page)
  release()
  await page.waitForResponse((r) => r.url().includes('/ai/config'))
  await page.unrouteAll({ behavior: 'ignoreErrors' })
  await page.locator('.ai-config__form').waitFor({ state: 'visible' })
  await page.route('**/api/organizations/*/ai/config', (route) => route.fulfill({
    status: 503,
    contentType: 'application/problem+json',
    body: JSON.stringify({ status: 503, code: 'UNAVAILABLE', detail: '状态核对' }),
  }))
  await page.reload({ waitUntil: 'networkidle' })
  await page.locator('.ai-config__error').waitFor({ state: 'visible' })
  const settingsFailed = await readPage(page)
  assert.equal(settingsFailed.skeleton, 0, '配置失败不应同时显示骨架')
  assert.equal(await page.locator('.ai-config__form').count(), 0, '配置失败不应显示表单')
  // N03：初次读取失败必须给出可操作的重试
  assert.equal(await page.locator('.ai-config').getByRole('button', { name: /重试|重新加载/ }).count(), 1, '配置失败应提供重试入口')
  await shot('S5-组织设置-失败', page)
  await page.unrouteAll({ behavior: 'ignoreErrors' })
  await page.locator('.ai-config').getByRole('button', { name: /重试|重新加载/ }).click()
  await page.locator('.ai-config__form').waitFor({ state: 'visible' })
  assert.equal(await page.locator('.ai-config__error').count(), 0, '重试成功后应移除错误')
  assert.deepEqual(errors, [], `S5 页面运行错误：${errors.join('；')}`)

  // 普通成员：设置页只读，看不到表单与保存入口
  const memberCtx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const memberCsrf = await (await memberCtx.request.get('/api/auth/csrf')).json()
  assert.equal((await memberCtx.request.post('/api/auth/login', {
    form: { username: process.env.UI_AUDIT_MEMBER || 'demo_member_yrnn', password: process.env.UI_AUDIT_PASS || 'Demo12345!' },
    headers: { [memberCsrf.headerName]: memberCsrf.token },
  })).status(), 204, '演示成员登录失败')
  const memberPage = await memberCtx.newPage()
  await memberPage.goto(`/org/${org.id}/settings`, { waitUntil: 'networkidle' })
  await memberPage.locator('[data-ai-role="member"]').waitFor({ state: 'visible' })
  assert.equal(await memberPage.locator('.ai-config__form').count(), 0, '普通成员不应看到配置表单')
  assert.equal(await memberPage.locator('[data-ai-action="save"]').count(), 0, '普通成员不应看到保存入口')
  assert.equal(await memberPage.locator('[data-ai-action="test"]').count(), 0, '普通成员不应看到测试入口')
  await shot('S5-组织设置-普通成员', memberPage)
  await memberCtx.close()

  // ---------- 3b. N02 触控控件尺寸 ----------
  const touchCtx = await browser.newContext({
    baseURL: base, viewport: { width: 390, height: 720 }, isMobile: true, hasTouch: true,
  })
  const touchPage = await touchCtx.newPage()
  await touchPage.goto('/login', { waitUntil: 'networkidle' })
  const touchTargets = await touchPage.evaluate(() => {
    const size = (selector) => Math.round(document.querySelector(selector).getBoundingClientRect().height)
    return {
      coarse: matchMedia('(pointer: coarse)').matches,
      button: size('.auth-submit'),
      input: size('.auth-content .el-input'),
      minTouch: getComputedStyle(document.documentElement).getPropertyValue('--ui-control-min-touch').trim(),
    }
  })
  assert.equal(touchTargets.coarse, true, '触控上下文应命中 coarse 指针')
  assert.ok(touchTargets.button >= 44, `触控主按钮高度应达到 44px，实际 ${touchTargets.button}`)
  assert.ok(touchTargets.input >= 44, `触控输入框高度应达到 44px，实际 ${touchTargets.input}`)
  await shot('S5-登录-触控', touchPage)
  await touchCtx.close()

  // ---------- 3c. N05 详情业务区加载状态 ----------
  const detailActivities = (await (await ctx.request.get(`/api/organizations/${org.id}/activities?pageSize=50`)).json()).items
  const detailTarget = detailActivities.find((item) => item.status === 'APPROVED') ?? detailActivities[0]
  assert.ok(detailTarget, '需要既有活动用于详情加载核对')
  const detailPage = await ctx.newPage()
  const directiveWarnings = []
  detailPage.on('console', (m) => { if (m.text().includes('Failed to resolve directive')) directiveWarnings.push(m.text().split('\n')[0]) })
  let releaseTasks
  const tasksHeld = new Promise((resolve) => { releaseTasks = resolve })
  await detailPage.route(
    (url) => url.pathname === `/api/organizations/${org.id}/activities/${detailTarget.id}/tasks`,
    async (route) => { await tasksHeld; await route.continue() },
  )
  await detailPage.goto(`/org/${org.id}/activities/${detailTarget.id}?tab=tasks`, { waitUntil: 'domcontentloaded' })
  await detailPage.locator('[data-p3-skeleton="tasks"]').waitFor({ state: 'visible' })
  const detailLoading = await detailPage.evaluate(() => ({
    taskSkeleton: document.querySelectorAll('[data-p3-skeleton="tasks"]').length,
    materialSkeleton: document.querySelectorAll('[data-p3-skeleton="materials"]').length,
    emptyTexts: [...document.querySelectorAll('.el-table__empty-text')].map((e) => e.textContent.trim()),
    overlays: document.querySelectorAll('.el-loading-mask').length,
  }))
  assert.ok(detailLoading.taskSkeleton > 0, '任务首次读取应显示骨架')
  assert.ok(detailLoading.materialSkeleton > 0, '同批读取的物资也应显示骨架')
  assert.equal(detailLoading.overlays, 0, '不应叠加整表遮罩')
  assert.deepEqual(detailLoading.emptyTexts, [], '加载期间不得显示“暂无任务／暂无物资”')
  await shot('S5-详情-任务慢响应', detailPage, true)
  releaseTasks()
  await detailPage.unrouteAll({ behavior: 'ignoreErrors' })
  await detailPage.locator('[data-p3-skeleton="tasks"]').waitFor({ state: 'detached' })
  assert.ok(await detailPage.locator('.el-table').count() >= 1, '数据到达后应渲染表格')
  assert.deepEqual(directiveWarnings, [], `详情区出现指令解析警告：${directiveWarnings.join('；')}`)
  await shot('S5-详情-任务完成', detailPage, true)
  await detailPage.close()

  // ---------- 3d. 骨架延迟：真实限速下可见，快速请求不闪一帧（UI 规范第 10 节） ----------
  const listCount = (await (await ctx.request.get(`/api/organizations/${org.id}/activities?pageSize=50`)).json()).items.length
  const throttleCtx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const throttleCsrf = await (await throttleCtx.request.get('/api/auth/csrf')).json()
  assert.equal((await throttleCtx.request.post('/api/auth/login', {
    form: { username: process.env.UI_AUDIT_USER || 'demo_owner_yrnn', password: process.env.UI_AUDIT_PASS || 'Demo12345!' },
    headers: { [throttleCsrf.headerName]: throttleCsrf.token },
  })).status(), 204, '限速核对登录失败')
  const throttlePage = await throttleCtx.newPage()
  const cdp = await throttleCtx.newCDPSession(throttlePage)
  await cdp.send('Network.enable')
  // Fast 3G：真实链路延迟，不用响应替身
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false, latency: 400, downloadThroughput: 400 * 1024, uploadThroughput: 400 * 1024,
  })
  await throttlePage.goto(`/org/${org.id}/activities`, { waitUntil: 'domcontentloaded' })
  await throttlePage.locator('[data-list-skeleton]').waitFor({ state: 'visible', timeout: 15000 })
  const throttleSkeleton = await throttlePage.evaluate(() => ({
    skeleton: document.querySelectorAll('[data-list-skeleton]').length,
    rows: document.querySelectorAll('.list-table .el-table__body tr').length,
    empty: document.querySelectorAll('[data-list-empty]').length,
    placeholders: document.querySelectorAll('[data-list-skeleton] .el-skeleton__item').length,
  }))
  assert.ok(throttleSkeleton.skeleton > 0, '限速下应显示骨架')
  assert.equal(throttleSkeleton.rows, 0, '骨架期间不应有数据行')
  assert.equal(throttleSkeleton.empty, 0, '骨架期间不应显示空态')
  assert.ok(throttleSkeleton.placeholders >= 6, '骨架应与真实表格同构（含表头占位）')
  await shot('S5-活动列表-限速骨架', throttlePage)
  await throttlePage.locator('.list-table .el-table__body tr').first().waitFor({ state: 'visible', timeout: 20000 })
  assert.equal(await throttlePage.locator('.list-table .el-table__body tr').count(), listCount, '限速下数据最终应到达')
  // 解除限速后本地快响应：骨架不应闪现（leading 延迟生效）
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1,
  })
  await throttlePage.goto(`/org/${org.id}/activities`, { waitUntil: 'domcontentloaded' })
  const fastFrames = []
  for (let i = 0; i < 10; i += 1) {
    fastFrames.push(await throttlePage.evaluate(() => document.querySelectorAll('[data-list-skeleton]').length))
    await throttlePage.waitForTimeout(20)
  }
  assert.equal(fastFrames.some((n) => n > 0), false, `快速请求不应闪现骨架，实际采样：${fastFrames.join(',')}`)
  await throttlePage.locator('.list-table .el-table__body tr').first().waitFor({ state: 'visible' })
  await throttleCtx.close()

  // ---------- 4. 五档宽度（S5 代表页，各用匹配的会话） ----------
  const widthCtx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const widthPage = await widthCtx.newPage()
  for (const [name, path, anchor] of [
    ['登录', '/login', '.auth-content'],
    ['公开活动', '/public/activities', '.public-card'],
    ['账号', '/', '.account-page'],
    ['组织设置', `/org/${org.id}/settings`, '.ai-config'],
  ]) {
    const anonymous = path === '/login' || path === '/public/activities'
    if (anonymous) {
      await widthCtx.clearCookies()
    } else if (!(await widthCtx.cookies()).some((cookie) => cookie.name === 'SESSION')) {
      // 组织路由需要登录；复用一次真实登录，不伪造会话
      const widthCsrf = await (await widthCtx.request.get('/api/auth/csrf')).json()
      assert.equal((await widthCtx.request.post('/api/auth/login', {
        form: { username: process.env.UI_AUDIT_USER || 'demo_owner_yrnn', password: process.env.UI_AUDIT_PASS || 'Demo12345!' },
        headers: { [widthCsrf.headerName]: widthCsrf.token },
      })).status(), 204, '宽度核对登录失败')
    }
    for (const width of [320, 390, 768, 1024, 1440]) {
      await widthPage.setViewportSize({ width, height: 1000 })
      await widthPage.goto(path, { waitUntil: 'networkidle' })
      await widthPage.locator(anchor).first().waitFor({ state: 'visible' })
      const state = await readPage(widthPage)
      assert.equal(state.overflow, 0, `${name} ${width} 出现整页横向溢出`)
      assert.equal(state.h1.length, 1, `${name} ${width} 应只有一个 h1`)
      results.widths.push({ page: name, width, overflow: state.overflow })
      if (width === 390) {
        await shot(`S5-${name}-390`, widthPage, true)
      }
    }
  }
  await widthPage.setViewportSize({ width: 390, height: 1000 })
  await widthPage.goto('/public/activities', { waitUntil: 'networkidle' })
  await widthPage.locator('.public-card').first().waitFor({ state: 'visible' })
  assert.equal(await readPage(widthPage).then((s) => s.overflow), 0, '公开列表 390 横向溢出')
  await shot('S5-公开活动-390', widthPage, true)
  await widthCtx.close()

  results.checks.push(
    '登录／注册／恢复：左右分屏、一页一个 h1、字段可见、输入不逐字报错而失焦才报错、错误凭据给出可读提示且不跳转、未登录访问组织路由回到登录页',
    'N01 认证构图：品牌区有环形装饰与玻璃流程卡，桌面标语与左边距达到 Demo 量级且用认证专用边距；手机压缩装饰、允许自然折行、字段与按钮保留',
    'N02 控件尺寸：触控上下文下主按钮与输入框高度均 ≥ 44px（桌面默认按钮按 control-height 收敛）',
    'N03 设置校验：非法地址失焦即报错并标记 aria-invalid；初次读取失败给出可操作重试，重试成功后错误消失',
    'N04 设置面板：字段落在有底色、边框与内边距的内容面板内，不再直接排在页面底色上',
    'N05 详情状态：首次读取显示骨架、不显示“暂无任务／物资”、无整表遮罩、无指令解析警告，数据到达后渲染表格',
    '骨架延迟：真实 Fast 3G 限速下骨架可见且与表格同构、期间无数据行与空态；解除限速后本地快响应不闪现骨架（leading／trailing 来自集中 token）',
    '公开活动：加载骨架与空态／失败态互斥、失败有重试、不含内部字段、五档宽度无溢出',
    '公开详情：真实公开活动 7 项事实、隐藏内部字段、失败给出原因与返回入口且不残留骨架',
    '账号页：单一 h1、加入与创建组织入口可用、桌面与手机无溢出',
    '组织设置：端点与模型与接口一致、密钥不回显、骨架与失败互斥、普通成员只读且无保存入口',
  )
  results.orgId = org.id
  await writeFile(new URL('S5结果.json', out), JSON.stringify(results, null, 2))
  const cards = [
    ['登录 1440', 'S5-登录-1440.png'],
    ['登录 失焦校验', 'S5-登录-失焦校验.png'],
    ['登录 失败提示', 'S5-登录-失败提示.png'],
    ['登录 手机 390', 'S5-登录-390.png'],
    ['登录 触控尺寸', 'S5-登录-触控.png'],
    ['注册 1440', 'S5-注册-1440.png'],
    ['恢复 1440', 'S5-恢复-1440.png'],
    ['账号 1440', 'S5-账号-1440.png'],
    ['账号 手机 390', 'S5-账号-390.png'],
    ['组织设置 1440', 'S5-组织设置-1440.png'],
    ['组织设置 失焦校验', 'S5-组织设置-失焦校验.png'],
    ['组织设置 加载', 'S5-组织设置-加载.png'],
    ['组织设置 失败', 'S5-组织设置-失败.png'],
    ['组织设置 普通成员', 'S5-组织设置-普通成员.png'],
    ['详情 任务慢响应', 'S5-详情-任务慢响应.png'],
    ['详情 任务完成', 'S5-详情-任务完成.png'],
    ['公开活动 1440', 'S5-公开活动-1440.png'],
    ['公开活动 空态', 'S5-公开活动-空态.png'],
    ['公开活动 失败', 'S5-公开活动-失败.png'],
    ['公开活动 手机 390', 'S5-公开活动-390.png'],
    ['公开详情 1440', 'S5-公开详情-1440.png'],
    ['公开详情 失败', 'S5-公开详情-失败.png'],
  ]
  await writeFile(new URL('S5逐页对照.html', out), `<!doctype html><html lang="zh-CN"><meta charset="utf-8">`
    + `<meta name="viewport" content="width=device-width"><title>S5 逐页还原对照</title>`
    + '<style>body{font:15px system-ui;margin:24px;background:#f3f3ef;color:#1a1814}'
    + 'main{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}figure{margin:0}'
    + 'img{width:100%;vertical-align:top;border:1px solid #ddd}figcaption{padding:8px 0;font-weight:600}'
    + '@media(max-width:800px){main{grid-template-columns:1fr}}</style>'
    + '<h1>S5 登录／账号／设置／公开参与</h1>'
    + '<p>1440×1000 与 390 宽真实数据截图；加载、空态、失败为浏览器响应替身，已在 S5结果.json 标注。</p>'
    + `<main>${cards.map(([label, file]) => `<figure><figcaption>${label}</figcaption><img src="${file}" alt="${label}"></figure>`).join('')}</main></html>`)
  console.log(JSON.stringify({ checks: results.checks, widths: results.widths.length, screenshots: results.screenshots.length }, null, 2))
} finally {
  await browser.close()
}
