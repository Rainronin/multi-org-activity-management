// P4 前端观察脚本：公开浏览 → 登录回跳 → 报名 → 管理者开关/审核/导出 → 我的报名。
// 仅可针对显式连接 activity_test 的后端运行，会新增专用测试数据。
import assert from 'node:assert/strict'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const shots = []
try {
  const stamp = Date.now()
  const ownerName = `p4owner${stamp}`
  const memberName = `p4member${stamp}`
  const password = `Pass${stamp}word`
  const base = 'http://127.0.0.1:5174'

  async function call(ctx, method, path, data, extraHeaders) {
    const headers = { ...(extraHeaders || {}) }
    if (method !== 'GET') {
      const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
      headers[csrf.headerName] = csrf.token
    }
    return ctx.request.fetch(path, { method, headers, ...(data === undefined ? {} : { data }) })
  }
  async function json(ctx, method, path, data, extraHeaders) {
    const res = await call(ctx, method, path, data, extraHeaders)
    if (!res.ok()) throw new Error(`${method} ${path} -> ${res.status()} ${await res.text()}`)
    return res.json()
  }
  // F05：方案保存与审批必须携带当前 version，调用前先读取
  async function currentVersion(activityId) {
    return (await json(ownerApi, 'GET', `/api/organizations/${org.id}/activities/${activityId}`)).version
  }
  async function login(ctx, username) {
    await json(ctx, 'POST', '/api/auth/register', { username, password })
    const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
    const res = await ctx.request.post('/api/auth/login', { form: { username, password }, headers: { [csrf.headerName]: csrf.token } })
    assert.equal(res.status(), 204, '准备流程：登录失败')
  }

  const ownerApi = await browser.newContext({ baseURL: base })
  const memberApi = await browser.newContext({ baseURL: base })
  await login(ownerApi, ownerName)
  await login(memberApi, memberName)
  const org = await json(ownerApi, 'POST', '/api/organizations', { name: `P4核对-${stamp}` })
  const invitation = await json(ownerApi, 'POST', `/api/organizations/${org.id}/invitations`, {})
  await json(memberApi, 'POST', '/api/invitations/accept', { code: invitation.code })
  const ownerMember = (await json(ownerApi, 'GET', `/api/organizations/${org.id}/members?pageSize=100`)).items
    .find((x) => x.username === ownerName).memberId

  const publicActivity = await json(ownerApi, 'POST', `/api/organizations/${org.id}/activities`, {
    name: 'P4 公开报名活动', description: '公开核对简介', location: '大礼堂', ownerMemberId: ownerMember,
    startTime: new Date(Date.now() + 10 * 86400000).toISOString(),
    endTime: new Date(Date.now() + 10 * 86400000 + 7200000).toISOString(),
    registrationScope: 'PUBLIC', capacity: 5,
    registrationStart: new Date(Date.now() - 86400000).toISOString(),
    registrationEnd: new Date(Date.now() + 9 * 86400000).toISOString(),
  }, { 'Idempotency-Key': `p4-${stamp}` })
  await json(ownerApi, 'PUT', `/api/organizations/${org.id}/activities/${publicActivity.id}/proposal`, { content: '方案', version: await currentVersion(publicActivity.id) })
  await json(ownerApi, 'POST', `/api/organizations/${org.id}/activities/${publicActivity.id}/submit`)
  await json(ownerApi, 'POST', `/api/organizations/${org.id}/activities/${publicActivity.id}/approve`, { version: await currentVersion(publicActivity.id) })
  await json(ownerApi, 'POST', `/api/organizations/${org.id}/activities/${publicActivity.id}/registration-open`, { open: true })
  await ownerApi.close()
  await memberApi.close()

  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
  const detailPath = `/public/activities/${publicActivity.id}`

  // 1. 匿名浏览公开活动：列表可见、详情只含公开字段、无内部导航
  const anon = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const anonPage = await anon.newPage()
  const anonErrors = []
  anonPage.on('pageerror', (e) => anonErrors.push(e.message))
  await anonPage.goto('/public/activities', { waitUntil: 'networkidle' })
  await anonPage.waitForSelector(`text=${'P4 公开报名活动'}`, { timeout: 15000 })
  const listShot = fileURLToPath(new URL('../验证截图/P4-公开活动列表.png', import.meta.url))
  await anonPage.screenshot({ path: listShot, fullPage: true })
  shots.push(listShot)
  const anonBody = await anonPage.locator('body').innerText()
  const anonChecks = {
    seesActivity: anonBody.includes('P4 公开报名活动'),
    hasInternalNav: anonBody.includes('成员管理') || anonBody.includes('我的任务'),
    leaksBudget: anonBody.includes('预算'),
  }
  await anonPage.goto(detailPath, { waitUntil: 'networkidle' })
  await anonPage.waitForSelector('text=报名范围', { timeout: 15000 })
  const detailShot = fileURLToPath(new URL('../验证截图/P4-公开活动详情.png', import.meta.url))
  await anonPage.screenshot({ path: detailShot, fullPage: true })
  shots.push(detailShot)
  const detailBody = await anonPage.locator('body').innerText()
  const detailChecks = {
    showsScope: detailBody.includes('公开报名'),
    showsCapacity: detailBody.includes('5'),
    internalNote: detailBody.includes('不在此展示'),
  }

  // 2. 未登录点报名 → 登录页 → 回跳公开详情（F01：回跳后留在公开页）
  await anonPage.getByRole('button', { name: '登录并报名' }).click()
  await anonPage.waitForURL(/\/login/, { timeout: 15000 })
  const loginUrl = anonPage.url()
  await anonPage.getByPlaceholder('注册时设置的用户名').fill(memberName)
  await anonPage.getByPlaceholder('请输入密码').fill(password)
  await anonPage.getByRole('button', { name: '进入工作区' }).click()
  await anonPage.waitForURL(new RegExp(`/public/activities/${publicActivity.id}`), { timeout: 15000 })
  const redirectWorked = anonPage.url().includes(`/public/activities/${publicActivity.id}`)

  // 3. 进入组织工作区报名
  const workspace = await anon.newPage()
  const wsErrors = []
  workspace.on('pageerror', (e) => wsErrors.push(e.message))
  await workspace.goto(`/org/${org.id}/activities/${publicActivity.id}`, { waitUntil: 'networkidle' })
  await workspace.waitForSelector('text=我的报名', { timeout: 15000 })
  await workspace.getByRole('button', { name: '我要报名' }).click()
  await workspace.waitForSelector('text=展示姓名')
  await workspace.locator('.el-dialog').getByRole('button', { name: '提交' }).click()
  await workspace.waitForSelector('text=待审核', { timeout: 15000 })
  const applyShot = fileURLToPath(new URL('../验证截图/P4-成员报名.png', import.meta.url))
  await workspace.screenshot({ path: applyShot, fullPage: true })
  shots.push(applyShot)
  const memberSeesRoster = (await workspace.getByRole('button', { name: '导出名单' }).count()) > 0
  const memberBody = await workspace.locator('body').innerText()

  // 4. 管理者：开关、审核、导出（页面登录后进入组织工作区）
  const ownerCtx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const ownerPage = await ownerCtx.newPage()
  const ownerErrors = []
  ownerPage.on('pageerror', (e) => ownerErrors.push(e.message))
  await ownerPage.goto('/login', { waitUntil: 'networkidle' })
  await ownerPage.getByPlaceholder('注册时设置的用户名').fill(ownerName)
  await ownerPage.getByPlaceholder('请输入密码').fill(password)
  await ownerPage.getByRole('button', { name: '进入工作区' }).click()
  await ownerPage.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15000 })
  await ownerPage.goto(`/org/${org.id}/activities/${publicActivity.id}`, { waitUntil: 'networkidle' })
  await ownerPage.waitForSelector('text=报名开关', { timeout: 15000 })
  const ownerSeesSwitch = await ownerPage.getByText('报名开关').count() > 0
  await ownerPage.getByRole('button', { name: '通过' }).first().click()
  await ownerPage.waitForSelector('text=已通过', { timeout: 15000 })
  const approveShot = fileURLToPath(new URL('../验证截图/P4-管理名单.png', import.meta.url))
  await ownerPage.screenshot({ path: approveShot, fullPage: true })
  shots.push(approveShot)
  const downloadPromise = ownerPage.waitForEvent('download', { timeout: 15000 }).catch(() => null)
  await ownerPage.getByRole('button', { name: '导出名单' }).click()
  const download = await downloadPromise
  const csvText = download ? await (await import('node:fs/promises')).readFile(await download.path(), 'utf8') : ''

  // 5. 我的报名（账号页）
  await workspace.goto('/', { waitUntil: 'networkidle' })
  await workspace.waitForSelector('text=我的报名', { timeout: 15000 })
  const accountBody = await workspace.locator('body').innerText()
  const myRegistrationsShot = fileURLToPath(new URL('../验证截图/P4-我的报名.png', import.meta.url))
  await workspace.screenshot({ path: myRegistrationsShot, fullPage: true })
  shots.push(myRegistrationsShot)

  console.log(JSON.stringify({
    check: 'P4 前端：公开浏览、报名、审核、导出、我的报名',
    anonChecks,
    detailChecks,
    loginRedirect: { loginUrlHasNext: loginUrl.includes('next='), redirectedToDetail: redirectWorked },
    member: {
      appliedPending: memberBody.includes('待审核'),
      seesExportButton: memberSeesRoster,
    },
    owner: { seesSwitch: ownerSeesSwitch, approved: true },
    exportedCsv: { downloaded: Boolean(download), hasHeader: csvText.includes('展示姓名'), hasApproved: csvText.includes('已通过') },
    myRegistrationsVisible: accountBody.includes('P4 公开报名活动') || accountBody.includes(memberName),
    browserErrors: [...anonErrors, ...wsErrors, ...ownerErrors],
    screenshots: shots.map((p) => p.split('验证截图').pop()),
  }))
} finally {
  await browser.close()
}
