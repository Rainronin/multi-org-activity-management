// P6 真机核对：启动执行（含待审报名前置）、执行记录、结束执行、反馈、总结与来源变化、
// 归档条件与归档后只读、取消活动与遗留说明、重新打开；并核对窄屏布局。
// 仅可针对显式连接 activity_test 的后端运行，会新增专用测试数据。
import assert from 'node:assert/strict'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const shots = []
try {
  const stamp = process.env.FLOW_RUN_STAMP || Date.now()
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
      if (process.env.FLOW_RUN_STAMP) break
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
  const ownerMemberId = members.find(x => x.username === ownerName).memberId

  if (process.argv.includes('--early-cancel')) {
    const page = await owner.newPage({ viewport: { width: 1440, height: 1000 } })
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    const results = []
    for (const pending of [false, true]) {
      const initial = pending ? 'PENDING_APPROVAL' : 'DRAFT'
      let current = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
        name: `初期取消-${initial}-${stamp}`, description: '尚未发布的内容', location: '线上', ownerMemberId,
        startTime: '2027-11-01T14:00:00+08:00', endTime: '2027-11-01T16:00:00+08:00',
        registrationScope: 'PUBLIC', capacity: 10, registrationStart: '2027-10-01T10:00:00+08:00',
        registrationEnd: '2027-11-01T13:00:00+08:00',
      }, { 'Idempotency-Key': `p6-early-${initial}-${stamp}` })
      const ap = `/api/organizations/${org.id}/activities/${current.id}`
      if (pending) {
        current = await json(owner, 'PUT', `${ap}/proposal`, { content: '未发布方案', version: current.version })
        current = await json(owner, 'POST', `${ap}/submit`)
      }
      assert.equal(current.status, initial)
      await page.setViewportSize({ width: 1440, height: 1000 })
      await page.goto(`/org/${org.id}/activities/${current.id}?tab=lifecycle`, { waitUntil: 'networkidle' })
      await page.getByRole('button', { name: '取消活动', exact: true }).waitFor()
      await page.screenshot({ path: fileURLToPath(new URL(`../验证截图/P6-初期取消-${initial}-桌面.png`, import.meta.url)), fullPage: true })
      const reason = `${initial}初期取消`
      await page.getByRole('button', { name: '取消活动', exact: true }).click()
      await page.locator('.el-message-box__input input').fill(reason)
      const cancelledResponse = page.waitForResponse(response => response.url().endsWith(`${ap}/cancel`)
        && response.request().method() === 'POST')
      await page.locator('.el-message-box').getByRole('button', { name: '确认取消活动', exact: true }).click()
      assert.equal((await cancelledResponse).status(), 200)
      await page.getByText('已取消：处理遗留事项后可归档', { exact: true }).waitFor()
      const cancelled = await json(owner, 'GET', ap)
      assert.equal(cancelled.status, 'CANCELLED')
      assert.equal(cancelled.confirmedAt, null)
      assert.equal(cancelled.cancelReason, reason)
      const retried = await json(owner, 'POST', `${ap}/cancel`, { reason: '原请求核对', version: current.version })
      assert.equal(retried.version, cancelled.version)
      assert.equal(retried.cancelReason, reason)
      assert.equal((await call(member, 'GET', ap)).status(), 404)
      assert.equal((await json(member, 'GET', `/api/organizations/${org.id}/activities?status=CANCELLED`)).total, 0)
      assert.equal((await call(owner, 'GET', `/api/public/activities/${current.id}`)).status(), 404)
      await page.setViewportSize({ width: 390, height: 844 })
      await page.waitForTimeout(500)
      await page.screenshot({ path: fileURLToPath(new URL(`../验证截图/P6-初期取消-${initial}-手机.png`, import.meta.url)), fullPage: true })
      const widths = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, viewport: window.innerWidth }))
      assert.ok(widths.scroll <= widths.viewport + 1, JSON.stringify(widths))
      current = await json(owner, 'PUT', `${ap}/summary`, { summary: '初期取消，无遗留事项', version: cancelled.version })
      current = await json(owner, 'POST', `${ap}/summary/confirm`, { version: current.version })
      current = await json(owner, 'POST', `${ap}/archive`, { version: current.version })
      assert.equal(current.status, 'ARCHIVED')
      assert.equal((await call(member, 'GET', ap)).status(), 404)
      assert.equal((await json(member, 'GET', `/api/organizations/${org.id}/activities?status=ARCHIVED`)).total, 0)
      assert.equal((await call(owner, 'GET', `/api/public/activities/${current.id}`)).status(), 404)
      results.push({ initial, id: current.id, cancelledThroughPage: true, recheckUnchanged: true,
        unpublishedHidden: true, archivedThroughApi: true, mobileNoOverflow: true })
    }
    assert.deepEqual(errors, [])
    const result = { database: 'activity_test', results, browserErrors: errors }
    await writeFile(new URL('../验证截图/P6-初期取消结果.json', import.meta.url), JSON.stringify(result, null, 2))
    console.log(JSON.stringify(result))
  } else {
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

  const detailPath = `/org/${org.id}/activities/${activity.id}?tab=lifecycle`
  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
  const page = await owner.newPage({ viewport: { width: 1440, height: 1000 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))

  // 关键内容变更必须撤回确认，页面保存后重新走确认，不能悄悄继续报名。
  await page.goto(detailPath, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '撤回确认', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '撤回', exact: true }).click()
  await page.getByRole('button', { name: '编辑信息', exact: true }).click()
  const edit = page.getByRole('dialog', { name: '编辑活动信息', exact: true })
  await edit.getByLabel('地点', { exact: true }).fill('雨天备用场地')
  await edit.getByRole('button', { name: '保存', exact: true }).click()
  await edit.waitFor({ state: 'hidden' })
  await page.getByRole('button', { name: '提交确认', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '提交', exact: true }).click()
  await page.getByRole('button', { name: '通过', exact: true }).click()
  await page.getByText('筹备中：分配任务、准备物资、审核报名', { exact: true }).waitFor()
  const changed = await json(owner, 'GET', ap)
  assert.equal(changed.location, '雨天备用场地')
  assert.equal(changed.registrationOpen, false, '重新确认后报名不能自动开放')

  // 1. 已确认活动：显示启动执行，待审报名未处理时被拒并给出原因
  await page.goto(detailPath, { waitUntil: 'networkidle' })
  await page.waitForSelector('text=执行与复盘', { timeout: 15000 })
  await page.getByRole('button', { name: '启动执行' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '启动执行' }).click()
  await page.waitForSelector('text=未处理', { timeout: 15000 })
  const blockedByPending = (await page.locator('body').innerText()).includes('待审核报名')
  const statusAfterBlockedStart = (await json(owner, 'GET', ap)).status

  // 2. 关闭报名不免除待审处理；正式页面明确驳回后才允许启动。
  await page.goto(`/org/${org.id}/activities/${activity.id}?tab=registration`, { waitUntil: 'networkidle' })
  assert.equal(changed.registrationOpen, false)
  await page.getByRole('button', { name: '驳回', exact: true }).click()
  const rejectDialog = page.getByRole('dialog', { name: '驳回报名', exact: true })
  await rejectDialog.locator('textarea').fill('本次执行安排不再接受此报名')
  await rejectDialog.getByRole('button', { name: '确认驳回', exact: true }).click()
  await rejectDialog.waitFor({ state: 'hidden' })
  const rejectedRegistration = (await json(owner, 'GET', `${ap}/registrations`)).items.find(r => r.registrationId === registrationId)
  assert.equal(rejectedRegistration.status, 'REJECTED')
  assert.equal((await json(owner, 'GET', ap)).registrationOpen, false)
  await page.goto(detailPath, { waitUntil: 'networkidle' })
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForSelector('text=启动执行', { timeout: 15000 })
  await page.getByRole('button', { name: '启动执行' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '启动执行' }).click()
  await page.waitForSelector('text=执行中', { timeout: 15000 })
  // 真实后端写入后替换一次 503，手动核对必须沿原请求，不能重复追加执行历史。
  let loseRecordResponse = true
  await page.route(`**${ap}/execution-records`, async route => {
    if (route.request().method() !== 'POST' || !loseRecordResponse) return route.continue()
    loseRecordResponse = false
    const response = await route.fetch()
    assert.equal(response.status(), 201)
    await route.fulfill({ status: 503, contentType: 'application/problem+json',
      body: JSON.stringify({ status: 503, code: 'RESULT_UNKNOWN', detail: '模拟执行记录响应丢失' }) })
  })
  await page.getByPlaceholder('进展、问题、临时安排、实际差异或处理结果').fill('上午完成场地布置，签到正常')
  await page.getByRole('button', { name: '记录', exact: true }).click()
  await page.locator('.lifecycle-section__error').filter({ hasText: '模拟执行记录响应丢失' }).waitFor()
  assert.equal((await json(owner, 'GET', `${ap}/execution-records`)).items.length, 1)
  const frozenRecord = await page.getByPlaceholder('进展、问题、临时安排、实际差异或处理结果').isDisabled()
  await page.getByRole('link', { name: '活动管理', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '继续编辑', exact: true }).click()
  assert.equal(new URL(page.url()).pathname, `/org/${org.id}/activities/${activity.id}`)
  await page.setViewportSize({ width: 390, height: 844 })
  const pendingRecordShot = new URL('../验证截图/P6-执行记录待核对-手机.png', import.meta.url)
  await page.screenshot({ path: fileURLToPath(pendingRecordShot), fullPage: true, animations: 'disabled' })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.getByRole('button', { name: /^(记录|核对记录结果)$/ }).click()
  await page.waitForSelector('text=上午完成场地布置', { timeout: 15000 })
  const afterStart = await json(owner, 'GET', ap)
  const records = (await json(owner, 'GET', `${ap}/execution-records`)).items
  assert.equal(records.length, 1, '执行记录响应丢失后核对不得重复追加')
  assert.equal(frozenRecord, true)
  await page.setViewportSize({ width: 1440, height: 1000 })

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
  assert.ok((await page.locator('body').innerText()).includes('来源已变化，需要重新核对并确认'))
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

  await page.goto(`/org/${org.id}/activities/${activity.id}?tab=expense`, { waitUntil: 'networkidle' })
  assert.equal(await page.getByRole('button', { name: '登记支出', exact: true }).count(), 0,
    '归档经费不能显示登记入口')
  await page.goto(detailPath, { waitUntil: 'networkidle' })

  // 6. 归档后普通写入关闭：界面无「记录」按钮，接口拒绝新增执行记录；反馈也不再接受修改
  const recordFormCount = await page.locator('.lifecycle-record-form').count()
  const recordAfterArchive = await call(owner, 'POST', `${ap}/execution-records`,
    { category: 'PROGRESS', content: '归档后补记' })
  const feedbackAfterArchive = await call(member, 'POST', `${ap}/feedback`,
    { rating: 1, comment: '归档后修改', version: feedbackAfterSubmit.myVersion })

  // 7. 重新打开回到复盘，但反馈不恢复；取消活动走遗留处理
  await page.getByRole('button', { name: '重新打开' }).click()
  await page.locator('.el-message-box__input input').fill('补充改进项')
  await page.locator('.el-message-box').getByRole('button', { name: '重新打开' }).click()
  await page.waitForSelector('text=复盘中', { timeout: 15000 })
  const reopened = await json(owner, 'GET', ap)
  const afterNormalReopen = await call(owner, 'POST', `${ap}/archive`, { version: reopened.version })
  assert.equal(afterNormalReopen.status(), 409, '重开后必须重新确认，旧确认不能直接归档')
  assert.equal(reopened.summaryConfirmedAt, null, '重开应清除当前确认')
  assert.equal((await afterNormalReopen.json()).code, 'ARCHIVE_BLOCKED')
  const feedbackAfterReopen = await call(member, 'POST', `${ap}/feedback`,
    { rating: 1, comment: '重开后', version: feedbackAfterSubmit.myVersion })
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

  // 确认快照：正常更正与取消更正均保留前次正文；旧版本不再用于重开后的归档。
  const beforeCancelConfirm = await json(owner, 'GET', `${ap}/confirmations`)
  assert.equal(beforeCancelConfirm.items.filter(item => item.kind === 'SUMMARY').length, 2)
  assert.ok(beforeCancelConfirm.items.some(item => item.kind === 'PROPOSAL' && item.content === '核对方案'))
  const originalSummary = beforeCancelConfirm.items.find(item => item.kind === 'SUMMARY').content
  async function saveAndConfirmThroughPage(content) {
    await page.locator('.lifecycle-block textarea').last().fill(content)
    const saveResponse = page.waitForResponse(r => r.url().endsWith(`${ap}/summary`) && r.request().method() === 'PUT')
    await page.getByRole('button', { name: '保存总结', exact: true }).click()
    assert.equal((await saveResponse).status(), 200)
    await page.getByRole('button', { name: '确认总结', exact: true }).waitFor()
    await page.waitForFunction(() => {
      const button = [...document.querySelectorAll('button')].find(el => el.textContent.trim() === '确认总结')
      return button && !button.disabled
    })
    const confirmed = page.waitForResponse(r => r.url().endsWith(`${ap}/summary/confirm`) && r.request().method() === 'POST')
    await page.getByRole('button', { name: '确认总结', exact: true }).click()
    assert.equal((await confirmed).status(), 200)
    await page.waitForTimeout(250)
  }
  async function archiveThroughPage() {
    await page.getByRole('button', { name: '归档活动', exact: true }).click()
    const response = page.waitForResponse(r => r.url().endsWith(`${ap}/archive`) && r.request().method() === 'POST')
    await page.locator('.el-message-box').getByRole('button', { name: '归档', exact: true }).click()
    assert.equal((await response).status(), 200)
    await page.waitForTimeout(250)
  }
  await saveAndConfirmThroughPage('取消说明初稿：场地临时不可用，费用已核对，遗留物资由负责人处理。')
  await archiveThroughPage()
  const beforeCancelReopen = await json(owner, 'GET', `${ap}/confirmations`)
  assert.equal(beforeCancelReopen.items[0].kind, 'CANCEL_NOTE')
  await page.getByRole('button', { name: '重新打开', exact: true }).click()
  await page.locator('.el-message-box__input input').fill('补充取消处置交接')
  const reopenedResponse = page.waitForResponse(r => r.url().endsWith(`${ap}/reopen`) && r.request().method() === 'POST')
  await page.locator('.el-message-box').getByRole('button', { name: '重新打开', exact: true }).click()
  assert.equal((await reopenedResponse).status(), 200)
  await page.waitForTimeout(250)
  const cancelReopened = await json(owner, 'GET', ap)
  assert.equal(cancelReopened.status, 'CANCELLED')
  assert.equal(cancelReopened.summaryConfirmedAt, null)
  assert.equal((await call(owner, 'POST', `${ap}/archive`, { version: cancelReopened.version })).status(), 409)
  await saveAndConfirmThroughPage('取消说明更正：补充处置交接，负责人已安排回收。')
  await archiveThroughPage()
  const finalConfirmations = await json(owner, 'GET', `${ap}/confirmations`)
  assert.equal(finalConfirmations.total, 6)
  assert.equal(finalConfirmations.items[0].kind, 'CANCEL_NOTE')
  assert.equal(finalConfirmations.items[0].reopenReason, '补充取消处置交接')
  assert.equal(finalConfirmations.items[1].content, '取消说明初稿：场地临时不可用，费用已核对，遗留物资由负责人处理。')
  assert.equal(finalConfirmations.items.find(item => item.kind === 'SUMMARY').content, originalSummary)
  await page.getByRole('button', { name: '查看文稿确认历史', exact: true }).click()
  await page.locator('.lifecycle-confirmation-content').filter({ hasText: '取消说明更正' }).waitFor()
  assert.equal(await page.locator('.lifecycle-confirmation-content').count(), 6)
  await page.waitForFunction(() => {
    const panel = document.querySelector('.lifecycle-section .el-collapse-item__wrap')
    return panel && panel.clientHeight >= panel.scrollHeight - 1 && document.querySelectorAll('.el-message').length === 0
  })
  const historyShot = fileURLToPath(new URL('../验证截图/P6-确认历史.png', import.meta.url))
  await page.screenshot({ path: historyShot, fullPage: true })
  shots.push(historyShot)
  // 只读期间草稿框与保存/确认应禁用，但仍可读取历史。
  assert.equal(await page.locator('.lifecycle-block textarea').last().isDisabled(), true)
  assert.equal(await page.getByRole('button', { name: '保存总结', exact: true }).isDisabled(), true)

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

  // 未报名的成员承担两项未完成任务，取消后仍须收到一条可读的活动变更提示。
  const noticeName = `未报名任务取消核对-${stamp}`
  const noticeActivity = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
    name: noticeName, description: '取消提示核对', location: '线上', ownerMemberId,
    startTime: '2027-11-02T14:00:00+08:00', endTime: '2027-11-02T16:00:00+08:00', registrationScope: 'INTERNAL',
  }, { 'Idempotency-Key': `p6-notice-${stamp}` })
  const noticeApi = `/api/organizations/${org.id}/activities/${noticeActivity.id}`
  await json(owner, 'PUT', `${noticeApi}/proposal`, { content: '取消提示核对方案', version: noticeActivity.version })
  await json(owner, 'POST', `${noticeApi}/submit`)
  await json(owner, 'POST', `${noticeApi}/approve`, { version: (await json(owner, 'GET', noticeApi)).version })
  const noticeTasks = []
  for (let i = 0; i < 2; i += 1) {
    const task = await json(owner, 'POST', `${noticeApi}/tasks`, { title: `未完成任务${i + 1}`, assigneeMemberId: memberMemberId })
    if (i === 1) await json(owner, 'POST', `${noticeApi}/tasks/${task.taskId}/transition`, { toStatus: 'IN_PROGRESS' })
    noticeTasks.push(task.taskId)
  }
  assert.equal((await json(owner, 'GET', `${noticeApi}/registrations`)).total, 0)
  const beforeNoticeCancel = await json(owner, 'GET', noticeApi)
  const noticeReason = '场地关闭-任务承担者核对'
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(`/org/${org.id}/activities/${noticeActivity.id}?tab=lifecycle`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '取消活动', exact: true }).click()
  await page.locator('.el-message-box__input input').fill(noticeReason)
  const noticeCancelledResponse = page.waitForResponse(r => r.url().endsWith(`${noticeApi}/cancel`) && r.request().method() === 'POST')
  await page.locator('.el-message-box').getByRole('button', { name: '确认取消活动', exact: true }).click()
  assert.equal((await noticeCancelledResponse).status(), 200)
  assert.equal((await json(owner, 'GET', noticeApi)).status, 'CANCELLED')
  assert.ok((await json(owner, 'GET', `${noticeApi}/tasks`)).items.every(task => task.status === 'CANCELLED'))
  await json(owner, 'POST', `${noticeApi}/cancel`, { reason: noticeReason, version: beforeNoticeCancel.version })
  const cancellationNotices = (await json(member, 'GET', '/api/me/notifications')).items
    .filter(item => item.activityId === noticeActivity.id && item.type === 'ACTIVITY_CANCELLED')
  assert.equal(cancellationNotices.length, 1)
  await memberPage.goto('/', { waitUntil: 'networkidle' })
  await memberPage.getByText(`活动「${noticeName}」已取消：${noticeReason}（负责人将处理遗留事项）`, { exact: true }).waitFor()
  const noticeShot = fileURLToPath(new URL('../验证截图/P6-未报名承担者取消提示.png', import.meta.url))
  await memberPage.screenshot({ path: noticeShot, fullPage: true })
  shots.push(noticeShot)
  const noticeLink = memberPage.locator('.notifications__list li').filter({ hasText: noticeReason }).getByRole('link')
  assert.equal(await noticeLink.getAttribute('href'), `/org/${org.id}/activities/${noticeActivity.id}`)
  await noticeLink.click()
  await memberPage.waitForURL(`**/org/${org.id}/activities/${noticeActivity.id}`)
  await memberPage.getByRole('heading', { name: noticeName, exact: true }).waitFor()
  await memberPage.getByText('已取消', { exact: true }).first().waitFor()

  assert.equal(statusAfterBlockedStart, 'APPROVED')
  assert.ok(blockedByPending && blockedBySummary && blockedBySource && summaryConfirmed)
  assert.equal(afterStart.status, 'EXECUTING')
  assert.equal(records.length, 1)
  assert.equal(feedbackAfterSubmit.myRating, 5)
  assert.equal(archived.status, 'ARCHIVED')
  assert.equal(recordFormCount, 0)
  assert.equal(recordAfterArchive.status(), 409)
  assert.equal(feedbackAfterArchive.status(), 409)
  assert.equal(reopened.status, 'REVIEWING')
  assert.equal(feedbackAfterReopen.status(), 409)
  assert.equal(registrationRows.find(r => r.registrationId === registrationId).status, 'REJECTED',
    '取消活动须保留此前的明确驳回结论')
  assert.ok(registrationRows.filter(r => r.registrationId !== registrationId).every(r => r.status === 'CANCELLED_ORG'))
  assert.ok(widths.every(w => w.overflowPx <= 0))
  assert.deepEqual(errors, [])
  assert.deepEqual(memberErrors, [])

  console.log(JSON.stringify({
    check: 'P6 执行、复盘与归档真机核对',
    activityId: activity.id,
    taskCancellationNotice: { activityId: noticeActivity.id, unregistered: true, tasks: noticeTasks.length,
      notices: cancellationNotices.length, originalVersionRetried: true, displayedThroughPage: true, internalDetailOpened: true },
    change: { location: changed.location, registrationOpen: changed.registrationOpen },
    startBlockedByPending: { blockedByPending, status: statusAfterBlockedStart },
    pendingRegistrationHandled: { registrationClosed: true, status: rejectedRegistration.status, rejectedThroughPage: true },
    execution: {
      status: afterStart.status,
      startedAt: afterStart.startedAt !== null,
      recordAdded: records.length === 1,
      unknownResultRecheckedOnce: true,
    },
    feedback: {
      canSubmitForActivityOwner: feedbackAfterSubmit.canSubmit,
      count: feedbackAfterSubmit.count,
      averageRating: feedbackAfterSubmit.averageRating,
      mineRating: feedbackAfterSubmit.myRating,
    },
    summary: { confirmed: summaryConfirmed, confirmationSnapshots: finalConfirmations.total,
      priorSummaryPreserved: true, priorCancelNotePreserved: true, historicalContentRendered: true },
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
      oldConfirmationRejected: afterNormalReopen.status(), cancelStatusPreserved: cancelReopened.status,
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
  }
} finally {
  await browser.close()
}
