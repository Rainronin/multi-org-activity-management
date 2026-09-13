// S10 观察脚本：P3 区块的编辑／改派、物资编辑与角色按钮。
// 仅可针对显式连接 activity_test 的后端运行，会新增专用测试数据。
import assert from 'node:assert/strict'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')

const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const stamp = Date.now()
  // 用户名仅允许 3-32 位字母、数字或下划线
  const ownerName = `uiowner${stamp}`
  const memberName = `uimember${stamp}`
  const password = `Pass${stamp}word`

  // 准备：注册两个账号、建组织、造一个已确认活动并分派任务与物资
  const api = await browser.newContext({ baseURL: 'http://127.0.0.1:5174' })
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
  const memberCtx = await browser.newContext({ baseURL: 'http://127.0.0.1:5174' })
  await login(api, ownerName)
  await login(memberCtx, memberName)
  const org = await json(api, 'POST', '/api/organizations', { name: `界面核对-${stamp}` })
  const invitation = await json(api, 'POST', `/api/organizations/${org.id}/invitations`, {})
  await json(memberCtx, 'POST', '/api/invitations/accept', { code: invitation.code })
  const members = (await json(api, 'GET', `/api/organizations/${org.id}/members?pageSize=100`)).items
  const ownerMember = members.find((x) => x.username === ownerName).memberId
  const memberMember = members.find((x) => x.username === memberName).memberId

  const activity = await json(api, 'POST', `/api/organizations/${org.id}/activities`, {
    name: 'P3 界面核对活动', description: '核对编辑与改派', ownerMemberId: ownerMember,
    startTime: new Date(Date.now() + 7 * 86400000).toISOString(),
    endTime: new Date(Date.now() + 7 * 86400000 + 7200000).toISOString(),
    location: '会议室', registrationScope: 'INTERNAL', capacity: 10,
  }, { 'Idempotency-Key': `ui-${stamp}` })
  await json(api, 'PUT', `/api/organizations/${org.id}/activities/${activity.id}/proposal`, { content: '方案' })
  await json(api, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/submit`)
  await json(api, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/approve`, {})
  await json(api, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/tasks`,
    { title: '界面核对任务', assigneeMemberId: memberMember, priority: 'MEDIUM' })
  await json(api, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/materials`,
    { name: '界面核对物资', quantity: 2, unit: '条' })
  await api.close()

  const detailPath = `/org/${org.id}/activities/${activity.id}`
  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
  const shots = []

  async function loginAs(ctx, username, label) {
    await ctx.addInitScript(() => {})
    const page = await ctx.newPage()
    const errors = []
    page.on('pageerror', (e) => errors.push(e.message))
    await page.goto('/login', { waitUntil: 'networkidle' })
    await page.getByPlaceholder('注册时设置的用户名').fill(username)
    await page.getByPlaceholder('请输入密码').fill(password)
    await page.getByRole('button', { name: '进入工作区' }).click()
    // 登录成功后由路由守卫决定落点（账号页或组织页），无需固定目标路径
    await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15000 })
    await page.goto(detailPath, { waitUntil: 'networkidle' })
    await page.waitForSelector('text=任务', { timeout: 15000 })
    const file = fileURLToPath(new URL(`../验证截图/S10-${label}.png`, import.meta.url))
    await page.screenshot({ path: file, fullPage: true })
    shots.push(file)
    return { page, errors }
  }

  // 组织负责人视图：应看到编辑／改派、取消、物资编辑与上传入口
  const ownerCtx = await browser.newContext({ baseURL: 'http://127.0.0.1:5174', viewport: { width: 1440, height: 1000 } })
  const owner = await loginAs(ownerCtx, ownerName, '负责人视图')
  const ownerText = await owner.page.locator('body').innerText()
  const ownerChecks = {
    newTask: await owner.page.getByRole('button', { name: '新建任务' }).isVisible(),
    editTask: await owner.page.getByRole('button', { name: '编辑/改派' }).first().isVisible(),
    cancelTask: await owner.page.getByRole('button', { name: '取消' }).first().isVisible(),
    editMaterial: await owner.page.getByRole('button', { name: '编辑' }).first().isVisible(),
    upload: await owner.page.getByRole('button', { name: '上传资料' }).isVisible(),
    hasAdvanceButton: ownerText.includes('标记完成'),
  }

  // 负责人改派任务给另一个人（组织负责人自己），验证编辑对话框与版本保存
  await owner.page.getByRole('button', { name: '编辑/改派' }).first().click()
  await owner.page.waitForSelector('text=编辑任务')
  await owner.page.locator('.el-dialog').getByRole('textbox').first().fill('改派后的任务标题')
  await owner.page.locator('.el-dialog').getByRole('button', { name: '保存' }).click()
  await owner.page.waitForSelector('text=改派后的任务标题', { timeout: 10000 })
  const reassigned = await owner.page.locator('body').innerText()

  // 旧版本覆盖：直接用接口以过期版本保存，应返回 409
  const ownerApi = await ownerCtx.request
  const csrf = await (await ownerApi.get('/api/auth/csrf')).json()
  const currentTask = await (await ownerApi.get(`/api/organizations/${org.id}/activities/${activity.id}/tasks`)).json()
  const stale = await ownerApi.post(
    `/api/organizations/${org.id}/activities/${activity.id}/tasks/${currentTask.items[0].taskId}/update`,
    { headers: { [csrf.headerName]: csrf.token, 'Content-Type': 'application/json' },
      data: { title: '旧版本覆盖', assigneeMemberId: Number(ownerMember), version: 1 } })
  const staleBody = await stale.json()

  // 普通成员视图：只看到本人任务的推进按钮，没有管理入口
  const memberCtx2 = await browser.newContext({ baseURL: 'http://127.0.0.1:5174', viewport: { width: 1440, height: 1000 } })
  const member = await loginAs(memberCtx2, memberName, '成员视图')
  const memberChecks = {
    newTask: await member.page.getByRole('button', { name: '新建任务' }).count(),
    editTask: await member.page.getByRole('button', { name: '编辑/改派' }).count(),
    upload: await member.page.getByRole('button', { name: '上传资料' }).count(),
    advance: await member.page.getByRole('button', { name: '标记完成' }).count(),
  }

  console.log(JSON.stringify({
    check: 'S10 P3 页面操作与角色按钮',
    ownerChecks,
    memberChecks,
    reassignSaved: reassigned.includes('改派后的任务标题'),
    staleVersionSave: { status: stale.status(), code: staleBody.code },
    browserErrors: [...owner.errors, ...member.errors],
    screenshots: shots.map((p) => p.split('验证截图').pop()),
  }))
} finally {
  await browser.close()
}
