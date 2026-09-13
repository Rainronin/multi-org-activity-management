// F01 外部参与者全链路观察脚本：无组织账号在公开活动页报名／取消；调整期仍可读本人状态。
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
  const ownerName = `f1owner${stamp}`
  const outsiderName = `f1outsider${stamp}`
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
  async function login(ctx, username) {
    await json(ctx, 'POST', '/api/auth/register', { username, password })
    const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
    const res = await ctx.request.post('/api/auth/login', { form: { username, password }, headers: { [csrf.headerName]: csrf.token } })
    assert.equal(res.status(), 204, '准备流程：登录失败')
  }

  const owner = await browser.newContext({ baseURL: base })
  await login(owner, ownerName)
  const org = await json(owner, 'POST', '/api/organizations', { name: `F01核对-${stamp}` })
  const ownerMember = (await json(owner, 'GET', `/api/organizations/${org.id}/members?pageSize=100`)).items
    .find((x) => x.username === ownerName).memberId
  const activity = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
    name: 'F01 外部报名活动', description: '面向所有登录账号公开报名', location: '线上',
    ownerMemberId: ownerMember,
    startTime: new Date(Date.now() + 12 * 86400000).toISOString(),
    endTime: new Date(Date.now() + 12 * 86400000 + 7200000).toISOString(),
    registrationScope: 'PUBLIC', capacity: 8,
    registrationStart: new Date(Date.now() - 86400000).toISOString(),
    registrationEnd: new Date(Date.now() + 11 * 86400000).toISOString(),
  }, { 'Idempotency-Key': `f01-${stamp}` })
  await json(owner, 'PUT', `/api/organizations/${org.id}/activities/${activity.id}/proposal`, { content: '方案' })
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/submit`)
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/approve`, {})
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/registration-open`, { open: true })

  // 参与者账号：注册但不加入任何组织。API 会话校验接口行为，浏览器会话走真实页面流程。
  const outsider = await browser.newContext({ baseURL: base })
  await login(outsider, outsiderName)
  const memberOfAnyOrg = await json(outsider, 'GET', '/api/me/organizations')
  assert.equal(memberOfAnyOrg.items.length, 0, '准备流程：参与者不应属于任何组织')

  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
  const detailPath = `/public/activities/${activity.id}`
  const participant = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const page = await participant.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))

  // 1. 未登录：公开页只读 + 登录入口
  await page.goto(detailPath, { waitUntil: 'networkidle' })
  await page.waitForSelector('text=我的报名', { timeout: 15000 })
  const anonBody = await page.locator('body').innerText()
  const anonHasLoginButton = (await page.getByRole('button', { name: /登录并报名|登录后报名/ }).count()) > 0

  // 2. 登录后回到公开页，直接在本页报名
  await page.goto(`/login?next=${encodeURIComponent(detailPath)}`, { waitUntil: 'networkidle' })
  await page.getByPlaceholder('注册时设置的用户名').fill(outsiderName)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '进入工作区' }).click()
  await page.waitForURL(new RegExp(`/public/activities/${activity.id}`), { timeout: 15000 })
  const landedOnPublicPage = page.url().includes(`/public/activities/${activity.id}`)
  await page.waitForSelector('text=我要报名', { timeout: 15000 })
  await page.getByRole('button', { name: '我要报名' }).click()
  await page.waitForSelector('text=展示姓名')
  await page.locator('.el-dialog').getByRole('button', { name: '提交' }).click()
  await page.waitForSelector('text=待审核', { timeout: 15000 })
  const appliedShot = fileURLToPath(new URL('../验证截图/F01-外部账号报名.png', import.meta.url))
  await page.screenshot({ path: appliedShot, fullPage: true })
  shots.push(appliedShot)
  const afterApplyBody = await page.locator('body').innerText()

  // 3. 我的报名：含活动名、安排与取消按钮
  await page.goto('/', { waitUntil: 'networkidle' })
  await page.waitForSelector('text=我的报名', { timeout: 15000 })
  const accountBody = await page.locator('body').innerText()
  const hasCancelInAccount = (await page.getByRole('button', { name: '取消报名' }).count()) > 0
  const myShot = fileURLToPath(new URL('../验证截图/F01-外部账号我的报名.png', import.meta.url))
  await page.screenshot({ path: myShot, fullPage: true })
  shots.push(myShot)

  // 4. 调整期：撤回活动后本人状态仍可读并显示调整提示
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/withdraw-approval`, { reason: '安排调整' })
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForSelector('text=我的报名', { timeout: 15000 })
  const adjustingBody = await page.locator('body').innerText()
  const adjustingShot = fileURLToPath(new URL('../验证截图/F01-调整期本人状态.png', import.meta.url))
  await page.screenshot({ path: adjustingShot, fullPage: true })
  shots.push(adjustingShot)

  // 5. 公开详情在调整期不可匿名访问（未确认内容不公开）
  const anonCheck = await browser.newContext({ baseURL: base })
  const anonDetailStatus = (await anonCheck.request.get(`/api/public/activities/${activity.id}`)).status()

  // 6. 取消报名（撤回草稿后仍允许本人取消：活动未开始）
  const cancelApi = await call(outsider, 'POST', `/api/activities/${activity.id}/registrations/cancel-self`)
  const myAfterCancel = (await json(outsider, 'GET', '/api/me/registrations')).items
    .find((x) => x.activityId === activity.id)

  console.log(JSON.stringify({
    check: 'F01 外部参与者报名与取消',
    outsiderHasNoOrganization: memberOfAnyOrg.items.length === 0,
    anonymous: { seesApplyPanel: anonBody.includes('我的报名'), hasLoginEntry: anonHasLoginButton },
    afterLoginStayedOnPublicPage: landedOnPublicPage,
    applied: { statusShownPending: afterApplyBody.includes('待审核'), canCancelInPage: afterApplyBody.includes('取消报名') },
    myRegistrations: {
      showsActivityName: accountBody.includes('F01 外部报名活动'),
      showsSchedule: accountBody.includes('开始'),
      hasViewLink: accountBody.includes('查看活动'),
      hasCancelButton: hasCancelInAccount,
    },
    adjustingPeriod: { showsAdjustNotice: adjustingBody.includes('活动安排调整中'), stillSeesStatus: adjustingBody.includes('待审核') },
    adjustingNotPublic: { anonymousDetailStatus: anonDetailStatus },
    cancelSelf: { status: cancelApi.status(), resultingState: myAfterCancel?.status },
    browserErrors: errors,
    screenshots: shots.map((p) => p.split('验证截图').pop()),
  }))
} finally {
  await browser.close()
}
