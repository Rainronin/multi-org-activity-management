// P7 真机核对：分身份工作台卡片与待办、待办跳转、站内提示列表/已读、撤权后旧提示不再下发；窄屏布局。
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
  const ownerName = `p7owner${stamp}`
  const memberName = `p7member${stamp}`
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
  async function login(ctx, name) {
    for (let attempt = 1; ; attempt += 1) {
      const res = await call(ctx, 'POST', '/api/auth/register', { username: name, password })
      if (res.ok()) break
      if (res.status() !== 429 || attempt >= 4) throw new Error(`注册失败 ${res.status()} ${await res.text()}`)
      await new Promise((resolve) => setTimeout(resolve, 60000))
    }
    const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
    assert.equal((await ctx.request.post('/api/auth/login', {
      form: { username: name, password }, headers: { [csrf.headerName]: csrf.token },
    })).status(), 204, '准备流程：登录失败')
  }

  // 准备：组织、已确认活动（成员为活动负责人）、一条待审报名、一个逾期任务
  const owner = await browser.newContext({ baseURL: base })
  await login(owner, ownerName)
  const member = await browser.newContext({ baseURL: base })
  await login(member, memberName)
  const org = await json(owner, 'POST', '/api/organizations', { name: `P7核对-${stamp}` })
  const invitation = await json(owner, 'POST', `/api/organizations/${org.id}/invitations`, {})
  await json(member, 'POST', '/api/invitations/accept', { code: invitation.code })
  const members = (await json(owner, 'GET', `/api/organizations/${org.id}/members?pageSize=100`)).items
  const ownerMemberId = members.find((x) => x.username === ownerName).memberId
  const memberMemberId = members.find((x) => x.username === memberName).memberId
  const activity = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
    name: 'P7 核对活动', description: '工作台核对', location: '线上', ownerMemberId,
    startTime: '2027-12-01T14:00:00+08:00', endTime: '2027-12-01T16:00:00+08:00',
    registrationScope: 'INTERNAL', capacity: 10, budget: '100.00',
  }, { 'Idempotency-Key': `p7-${stamp}` })
  const ap = `/api/organizations/${org.id}/activities/${activity.id}`
  await json(owner, 'PUT', `${ap}/proposal`, { content: '核对方案', version: activity.version })
  await json(owner, 'POST', `${ap}/submit`)
  await json(owner, 'POST', `${ap}/approve`,
    { note: '核对确认', version: (await json(owner, 'GET', ap)).version })
  await json(owner, 'POST', `${ap}/registration-open`, { open: true })
  await json(member, 'POST', `/api/activities/${activity.id}/registrations`, { displayName: memberName })
  await json(owner, 'POST', `${ap}/tasks`, {
    title: '逾期核对任务', assigneeMemberId: memberMemberId, dueAt: '2020-01-01T00:00:00+08:00',
  })

  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })

  // 1. 组织负责人工作台：进行中活动、待确认立项、待办（待审报名 / 逾期任务）
  const ownerPage = await owner.newPage({ viewport: { width: 1440, height: 1000 } })
  const errors = []
  ownerPage.on('pageerror', (e) => errors.push(e.message))
  await ownerPage.goto(`/org/${org.id}`, { waitUntil: 'networkidle' })
  await ownerPage.waitForSelector('text=工作台', { timeout: 15000 })
  await ownerPage.waitForSelector('text=进行中活动', { timeout: 15000 })
  const ownerBody = await ownerPage.locator('body').innerText()
  const ownerWorkbench = await json(owner, 'GET', `/api/organizations/${org.id}/workbench`)
  const shot1 = fileURLToPath(new URL('../验证截图/P7-负责人工作台.png', import.meta.url))
  await ownerPage.screenshot({ path: shot1, fullPage: true })
  shots.push(shot1)

  // 2. 待办跳转：点击「处理」进入对应活动详情
  const todoLink = ownerPage.getByRole('button', { name: '处理' }).first()
  const todoCount = await ownerPage.getByRole('button', { name: '处理' }).count()
  await todoLink.click()
  await ownerPage.waitForURL(new RegExp(`/org/${org.id}/activities/`), { timeout: 15000 })
  const todoJumped = ownerPage.url().includes(`/activities/${activity.id}`)

  // 3. 普通成员工作台：只有本人卡片（我的待处理任务/逾期/报名）
  const memberPage = await member.newPage({ viewport: { width: 1440, height: 1000 } })
  const memberErrors = []
  memberPage.on('pageerror', (e) => memberErrors.push(e.message))
  await memberPage.goto(`/org/${org.id}`, { waitUntil: 'networkidle' })
  await memberPage.waitForSelector('text=工作台', { timeout: 15000 })
  const memberWorkbench = await json(member, 'GET', `/api/organizations/${org.id}/workbench`)
  const memberBody = await memberPage.locator('body').innerText()

  // 4. 站内提示：成员收到任务分派与报名结果；账号页展示、未读与标记已读
  const pendingRegistration = (await json(owner, 'GET', `${ap}/registrations`)).items[0]
  await json(owner, 'POST', `${ap}/registrations/${pendingRegistration.registrationId}/approve`,
    { version: pendingRegistration.version })
  await memberPage.goto('/', { waitUntil: 'networkidle' })
  await memberPage.waitForSelector('text=站内提示', { timeout: 15000 })
  const beforeRead = await json(member, 'GET', '/api/me/notifications')
  const notificationBody = await memberPage.locator('body').innerText()
  const shot2 = fileURLToPath(new URL('../验证截图/P7-站内提示.png', import.meta.url))
  await memberPage.screenshot({ path: shot2, fullPage: true })
  shots.push(shot2)
  const unreadShown = notificationBody.includes(`未读 ${beforeRead.unread}`)
  await memberPage.getByRole('button', { name: '全部标为已读' }).click()
  await memberPage.waitForTimeout(800)
  const afterRead = await json(member, 'GET', '/api/me/notifications')

  // 保留一条真正未读的提示再撤权，避免先全部已读掩盖隐藏提示的计数错误。
  await json(owner, 'POST', `${ap}/tasks`, { title: '撤权未读核对', assigneeMemberId: memberMemberId })
  const unreadBeforePermissionChange = await json(member, 'GET', '/api/me/notifications')
  assert.equal(unreadBeforePermissionChange.unread, 1)

  // 5. 撤权后旧提示不再下发：成员退出（先结束职责）后列表为空
  for (const task of (await json(owner, 'GET', `${ap}/tasks`)).items) {
    if (['PENDING', 'IN_PROGRESS'].includes(task.status)) {
      await json(owner, 'POST', `${ap}/tasks/${task.taskId}/transition`, { toStatus: 'CANCELLED' })
    }
  }
  await json(owner, 'PATCH', '/api/organizations/' + org.id + '/activities/' + activity.id,
    { ownerMemberId: ownerMemberId })
    .catch(async () => {
      // 已确认活动的负责人变更需先撤回到草稿：按规则走一遍
      await json(owner, 'POST', `${ap}/withdraw-approval`,
        { reason: '改派负责人', version: (await json(owner, 'GET', ap)).version })
      await json(owner, 'PATCH', ap,
        { ownerMemberId: ownerMemberId, version: (await json(owner, 'GET', ap)).version })
    })
  const leave = await call(member, 'DELETE', `/api/organizations/${org.id}/members/me`)
  const afterLeave = await json(member, 'GET', '/api/me/notifications')
  assert.equal(leave.status(), 204)
  assert.equal(afterLeave.total, 0)
  assert.equal(afterLeave.unread, 0)
  await memberPage.reload({ waitUntil: 'networkidle' })
  await memberPage.getByText('暂无提示。', { exact: true }).waitFor()
  assert.equal(await memberPage.locator('.notifications__tools .el-tag').count(), 0)
  assert.equal(await memberPage.getByRole('button', { name: '全部标为已读', exact: true }).count(), 0)
  const permissionShot = fileURLToPath(new URL('../验证截图/P7-撤权未读.png', import.meta.url))
  await memberPage.screenshot({ path: permissionShot, fullPage: true })
  shots.push(permissionShot)

  // 6. 窄屏：工作台与提示区块在各宽度下不横向溢出
  const widths = []
  for (const width of [1440, 1024, 768, 390, 320]) {
    await ownerPage.setViewportSize({ width, height: 900 })
    await ownerPage.goto(`/org/${org.id}`, { waitUntil: 'networkidle' })
    await ownerPage.waitForSelector('text=工作台', { timeout: 15000 })
    await ownerPage.waitForTimeout(200)
    const initialOverflowPx = await ownerPage.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    await ownerPage.waitForTimeout(800)
    const measured = await ownerPage.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
      overflowing: [...document.querySelectorAll('body *')].filter(el => el.getBoundingClientRect().right > document.documentElement.clientWidth + 2)
        .slice(0, 8).map(el => ({ tag: el.tagName, class: el.className, right: el.getBoundingClientRect().right })),
    }))
    widths.push({ width, initialOverflowPx, overflowPx: measured.scrollWidth - measured.clientWidth,
      ...(measured.scrollWidth > measured.clientWidth + 2 ? { overflowing: measured.overflowing } : {}) })
  }
  const smallestShot = fileURLToPath(new URL('../验证截图/P7-320px.png', import.meta.url))
  await ownerPage.screenshot({ path: smallestShot, fullPage: true })
  shots.push(smallestShot)
  const shot3 = fileURLToPath(new URL('../验证截图/P7-窄屏.png', import.meta.url))
  await ownerPage.setViewportSize({ width: 390, height: 900 })
  await ownerPage.waitForTimeout(200)
  await ownerPage.screenshot({ path: shot3, fullPage: true })
  shots.push(shot3)

  assert.equal(todoJumped, true)
  assert.equal(unreadShown, true)
  assert.equal(afterRead.unread, 0)
  assert.equal(ownerWorkbench.role, 'OWNER')
  assert.equal(memberWorkbench.role, 'MEMBER')
  assert.ok(widths.every((item) => item.overflowPx <= 2), JSON.stringify(widths))
  assert.deepEqual(errors, [])
  assert.deepEqual(memberErrors, [])

  console.log(JSON.stringify({
    check: 'P7 工作台与站内提示真机核对',
    ownerWorkbench: {
      role: ownerWorkbench.role,
      cardKeys: ownerWorkbench.cards.map((c) => c.key),
      todoKinds: ownerWorkbench.todos.map((t) => t.kind),
      todoCount,
    },
    ownerView: {
      showsWorkbench: ownerBody.includes('工作台'),
      showsInProgress: ownerBody.includes('进行中活动'),
      showsHint: ownerBody.includes('筹备中'),
    },
    todoJumped,
    memberWorkbench: {
      role: memberWorkbench.role,
      managementCards: memberWorkbench.cards.length,
      myCardKeys: memberWorkbench.myCards.map((c) => c.key),
      showsMyTasks: memberBody.includes('我的待处理任务'),
    },
    notifications: {
      total: beforeRead.total,
      unreadBefore: beforeRead.unread,
      unreadAfter: afterRead.unread,
      unreadShownInUi: unreadShown,
      unreadBeforePermissionChange: unreadBeforePermissionChange.unread,
      types: beforeRead.items.map((i) => i.type),
      bodyHasSummary: notificationBody.includes('任务分派') || notificationBody.includes('报名通过'),
    },
    afterLeave: {
      leaveStatus: leave.status(),
      total: afterLeave.total,
      items: afterLeave.items.length,
      unread: afterLeave.unread,
    },
    narrowWidths: widths,
    browserErrors: errors,
    memberViewErrors: memberErrors,
    screenshots: shots.map((p) => p.split('验证截图').pop()),
  }, null, 1))
} finally {
  await browser.close()
}
