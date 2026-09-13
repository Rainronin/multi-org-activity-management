// P6 真机核对：启动执行（含待审报名前置）、执行记录、结束执行、反馈、总结与来源变化、
// 归档条件与归档后只读、取消活动与遗留说明、重新打开；并核对窄屏布局。
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
  const ownerName = `p6owner${stamp}`
  const memberName = `p6member${stamp}`
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

  // 准备：组织、已确认活动（预算 100）、普通成员作为活动负责人
  const owner = await browser.newContext({ baseURL: base })
  await login(owner, ownerName)
  const member = await browser.newContext({ baseURL: base })
  await login(member, memberName)
  const org = await json(owner, 'POST', '/api/organizations', { name: `P6核对-${stamp}` })
  const invitation = await json(owner, 'POST', `/api/organizations/${org.id}/invitations`, {})
  await json(member, 'POST', '/api/invitations/accept', { code: invitation.code })
  const members = (await json(owner, 'GET', `/api/organizations/${org.id}/members?pageSize=100`)).items
  const memberMemberId = members.find((x) => x.username === memberName).memberId
  const activity = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
    name: 'P6 生命周期活动', description: '执行与复盘核对', location: '线上', ownerMemberId: memberMemberId,
    startTime: '2027-11-01T14:00:00+08:00', endTime: '2027-11-01T16:00:00+08:00',
    registrationScope: 'INTERNAL', capacity: 10, budget: '100.00',
  }, { 'Idempotency-Key': `p6-${stamp}` })
  const ap = `/api/organizations/${org.id}/activities/${activity.id}`
  await json(owner, 'PUT', `${ap}/proposal`, { content: '核对方案', version: activity.version })
  await json(owner, 'POST', `${ap}/submit`)
  const pendingVersion = (await json(owner, 'GET', ap)).version
  await json(owner, 'POST', `${ap}/approve`, { note: '核对确认', version: pendingVersion })
  await json(owner, 'POST', `${ap}/registration-open`, { open: true })
  // 一条待审报名：启动执行应被拒
  await json(member, 'POST', `/api/activities/${activity.id}/registrations`, { displayName: memberName })
  const registrationId = (await json(owner, 'GET', `${ap}/registrations`)).items[0].registrationId

  const detailPath = `/org/${org.id}/activities/${activity.id}`
  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
  const page = await owner.newPage({ viewport: { width: 1440, height: 1000 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))

  // 1. 已确认活动：显示启动执行，待审报名未处理时被拒并给出原因
  await page.goto(detailPath, { waitUntil: 'networkidle' })
  await page.waitForSelector('text=执行与复盘', { timeout: 15000 })
  await page.getByRole('button', { name: '启动执行' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '启动执行' }).click()
  await page.waitForSelector('text=未处理', { timeout: 15000 })
  const blockedByPending = (await page.locator('body').innerText()).includes('待审核报名')
  const statusAfterBlockedStart = (await json(owner, 'GET', ap)).status

  // 2. 处理待审报名后启动执行：活动负责人（普通成员）可以启动，执行记录可写
  await json(owner, 'POST', `${ap}/registrations/${registrationId}/approve`)
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForSelector('text=启动执行', { timeout: 15000 })
  await page.getByRole('button', { name: '启动执行' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '启动执行' }).click()
  await page.waitForSelector('text=执行中', { timeout: 15000 })
  await page.getByPlaceholder('进展、问题、临时安排、实际差异或处理结果').fill('上午完成场地布置，签到正常')
  await page.getByRole('button', { name: '记录' }).click()
  await page.waitForSelector('text=上午完成场地布置', { timeout: 15000 })
  const afterStart = await json(owner, 'GET', ap)
  const records = (await json(owner, 'GET', `${ap}/execution-records`)).items

  // 3. 结束执行进入复盘 → 反馈区开放（活动负责人本人有资格：执行结束时的活动负责人）
  await page.getByRole('button', { name: '结束执行' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '进入复盘' }).click()
  await page.waitForSelector('text=复盘中', { timeout: 15000 })
  const memberPage = await member.newPage({ viewport: { width: 1440, height: 1000 } })
  const memberErrors = []
  memberPage.on('pageerror', (e) => memberErrors.push(e.message))
  await memberPage.goto(detailPath, { waitUntil: 'networkidle' })
  await memberPage.waitForSelector('text=反馈', { timeout: 15000 })
  await memberPage.locator('.lifecycle-feedback-form .el-rate__item').nth(4).click()
  await memberPage.locator('.lifecycle-feedback-form textarea').fill('组织得不错，指示牌可以再多一些')
  await memberPage.getByRole('button', { name: '提交反馈' }).click()
  await memberPage.waitForTimeout(800)
  const feedbackAfterSubmit = await json(member, 'GET', `${ap}/feedback`)

  // 4. 总结：保存 → 未确认时归档被拒 → 确认后仍未结束任务被拒
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForSelector('text=总结', { timeout: 15000 })
  await page.locator('.lifecycle-block textarea').last().fill('目标达成；任务与报名汇总见各区块；改进项：下次提前 30 分钟布置。')
  await page.getByRole('button', { name: '保存总结' }).click()
  await page.waitForTimeout(800)
  await page.getByRole('button', { name: '归档活动' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '归档' }).click()
  await page.waitForSelector('text=总结尚未确认', { timeout: 15000 })
  const blockedBySummary = (await page.locator('body').innerText()).includes('总结尚未确认')

  await page.getByRole('button', { name: '确认总结' }).click()
  await page.waitForTimeout(800)
  const summaryConfirmed = (await json(owner, 'GET', ap)).summaryConfirmedAt !== null

  // 5. 来源变化（新增任务）→ 归档被拒要求重新确认 → 取消任务并重新确认后归档成功
  await json(owner, 'POST', `${ap}/tasks`, { title: '复盘期新增任务', assigneeMemberId: memberMemberId })
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForSelector('text=归档活动', { timeout: 15000 })
  await page.getByRole('button', { name: '归档活动' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '归档' }).click()
  await page.waitForSelector('text=来源已变化', { timeout: 15000 })
  const blockedBySource = (await page.locator('body').innerText()).includes('来源已变化')
  const taskId = (await json(owner, 'GET', `${ap}/tasks`)).items[0].taskId
  await json(owner, 'POST', `${ap}/tasks/${taskId}/transition`, { toStatus: 'CANCELLED' })
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForSelector('text=确认总结', { timeout: 15000 })
  await page.getByRole('button', { name: '确认总结' }).click()
  await page.waitForTimeout(800)
  await page.getByRole('button', { name: '归档活动' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '归档' }).click()
  await page.waitForSelector('text=已归档', { timeout: 15000 })
  const archived = await json(owner, 'GET', ap)
  const archivedShot = fileURLToPath(new URL('../验证截图/P6-归档.png', import.meta.url))
  await page.screenshot({ path: archivedShot, fullPage: true })
  shots.push(archivedShot)

  // 6. 归档后普通写入关闭：界面无「记录」按钮，接口拒绝新增执行记录；反馈也不再接受修改
  const recordFormCount = await page.locator('.lifecycle-record-form').count()
  const recordAfterArchive = await call(owner, 'POST', `${ap}/execution-records`,
    { category: 'PROGRESS', content: '归档后补记' })
  const feedbackAfterArchive = await call(member, 'POST', `${ap}/feedback`, { rating: 1, comment: '归档后修改' })

  // 7. 重新打开回到复盘，但反馈不恢复；取消活动走遗留处理
  await page.getByRole('button', { name: '重新打开' }).click()
  await page.locator('.el-message-box__input input').fill('补充改进项')
  await page.locator('.el-message-box').getByRole('button', { name: '重新打开' }).click()
  await page.waitForSelector('text=复盘中', { timeout: 15000 })
  const reopened = await json(owner, 'GET', ap)
  const feedbackAfterReopen = await call(member, 'POST', `${ap}/feedback`, { rating: 1, comment: '重开后' })
  await page.getByRole('button', { name: '取消活动' }).click()
  await page.locator('.el-message-box__input input').fill('场地临时不可用')
  await page.locator('.el-message-box').getByRole('button', { name: '确认取消活动' }).click()
  await page.waitForSelector('text=已取消', { timeout: 15000 })
  // 界面提示与接口状态可能相差一个节拍：轮询到终态再取快照
  let cancelled = await json(owner, 'GET', ap)
  for (let attempt = 0; attempt < 10 && cancelled.status !== 'CANCELLED'; attempt += 1) {
    await page.waitForTimeout(500)
    cancelled = await json(owner, 'GET', ap)
  }
  const cancelBanner = (await page.locator('body').innerText()).includes('取消原因')
  assert.equal(cancelled.status, 'CANCELLED', `取消失败：${JSON.stringify(cancelled)}`)
  const registrationRows = (await json(owner, 'GET', `${ap}/registrations`)).items
  const cancelShot = fileURLToPath(new URL('../验证截图/P6-取消活动.png', import.meta.url))
  await page.screenshot({ path: cancelShot, fullPage: true })
  shots.push(cancelShot)

  // 8. 窄屏：生命周期区块在各宽度下不横向溢出
  const widths = []
  for (const width of [1440, 1024, 768, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    await page.waitForTimeout(200)
    const measured = await page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }))
    widths.push({ width, overflowPx: measured.scrollWidth - measured.clientWidth })
  }
  const narrowShot = fileURLToPath(new URL('../验证截图/P6-窄屏.png', import.meta.url))
  await page.setViewportSize({ width: 390, height: 900 })
  await page.waitForTimeout(200)
  await page.screenshot({ path: narrowShot, fullPage: true })
  shots.push(narrowShot)

  console.log(JSON.stringify({
    check: 'P6 执行、复盘与归档真机核对',
    startBlockedByPending: { blockedByPending, status: statusAfterBlockedStart },
    execution: {
      status: afterStart.status,
      startedAt: afterStart.startedAt !== null,
      recordAdded: records.length === 1,
    },
    feedback: {
      canSubmitForActivityOwner: feedbackAfterSubmit.canSubmit,
      count: feedbackAfterSubmit.count,
      averageRating: feedbackAfterSubmit.averageRating,
      mineRating: feedbackAfterSubmit.myRating,
    },
    summary: { confirmed: summaryConfirmed },
    archive: {
      blockedBySummary,
      blockedBySource,
      status: archived.status,
      archivedAt: archived.archivedAt !== null,
      reopenCount: archived.reopenCount,
    },
    afterArchive: {
      recordFormHidden: recordFormCount === 0,
      recordRejected: recordAfterArchive.status(),
      feedbackRejected: feedbackAfterArchive.status(),
    },
    reopen: {
      status: reopened.status,
      feedbackStillClosed: feedbackAfterReopen.status(),
    },
    cancel: {
      status: cancelled.status,
      reason: cancelled.cancelReason,
      bannerShown: cancelBanner,
      registrationStates: registrationRows.map((r) => r.status),
    },
    narrowWidths: widths,
    browserErrors: errors,
    memberViewErrors: memberErrors,
    screenshots: shots.map((p) => p.split('验证截图').pop()),
  }, null, 1))
} finally {
  await browser.close()
}
