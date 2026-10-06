// F01 外部参与者全链路观察脚本：无组织账号在公开活动页报名／取消；调整期仍可读本人状态。
// 仅可针对显式连接 activity_test 的后端运行，会新增专用测试数据。
import assert from 'node:assert/strict'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const shots = []
async function auditReadStates() {
  const base = 'http://127.0.0.1:5174'
  const checks = []
  const errors = []
  const output = new URL('../验证截图/公开读取状态/', import.meta.url)
  await mkdir(output, { recursive: true })
  for (const failed of ['public', 'mine', 'feedback']) {
    const context = await browser.newContext({ baseURL: base, reducedMotion: 'reduce' })
    let failing = true
    let writes = 0
    await context.route('**/api/**', async route => {
      const url = new URL(route.request().url())
      const path = url.pathname
      if (route.request().method() !== 'GET') writes++
      let kind = path === '/api/public/activities/5' ? 'public'
        : path === '/api/me/registrations' ? 'mine'
        : path === '/api/public/activities/5/feedback' ? 'feedback' : ''
      let value = { items: [] }
      if (path === '/api/auth/me') value = { username: 'reader', id: '1', recoveryCodeIssued: true }
      if (kind === 'public') value = { id: '5', name: '公开读取核对活动', description: '公开介绍保持可读',
        organizationName: '核对组织', status: 'REVIEWING', registrationScope: 'PUBLIC', registrationOpen: false,
        startTime: '2027-11-01T06:00:00Z', endTime: '2027-11-01T08:00:00Z', location: '线上', capacity: 10 }
      if (kind === 'mine') value = { items: [{ activityId: '5', registrationId: '7', status: 'APPROVED',
        displayName: 'reader', note: '', version: 1, activityName: '公开读取核对活动', organizationName: '核对组织' }] }
      if (kind === 'feedback') value = { count: 1, averageRating: '5.0', canSubmit: true, mineSubmitted: true,
        myRating: 5, myComment: '原建议', myVersion: 1, submitBlockedReason: null }
      if (kind === failed && failing) {
        await route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({
          type: 'about:blank', title: '暂时不可用', status: 503, code: 'TEST_UNAVAILABLE', detail: '模拟读取503',
        }) })
      } else await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(value) })
    })
    const page = await context.newPage()
    page.on('pageerror', error => errors.push(error.message))
    await page.goto(`${base}/public/activities/5`, { waitUntil: 'networkidle' })
    const body = await page.locator('body').innerText()
    function check(name, passed) { checks.push({ name: `${failed}:${name}`, passed }) }
    check('noFalseMissing', !body.includes('活动不存在或未公开'))
    if (failed === 'public') {
      check('failureVisible', body.includes('加载失败'))
      check('loadingFinished', await page.locator('[data-public-detail-skeleton]').count() === 0)
      failing = false
      await page.getByRole('button', { name: '重新加载', exact: true }).click()
    } else {
      check('publicContentKept', await page.getByRole('heading', { name: '公开读取核对活动。', exact: true }).count() === 1)
      const section = page.locator(failed === 'mine' ? '.public-apply' : '.public-feedback')
      check('failureVisible', await section.getByRole('alert').count() > 0)
      const retry = section.getByRole('button', { name: '重新加载', exact: true })
      const retryShown = await retry.count() > 0
      check('retryVisible', retryShown)
      failing = false
      if (retryShown) await retry.click()
    }
    await page.waitForTimeout(250)
    check('retryKeepsPublic', await page.getByRole('heading', { name: '公开读取核对活动。', exact: true }).count() === 1)
    if (failed !== 'public') {
      check('retryShowsRecord', failed === 'mine'
        ? await page.locator('.public-apply').count() > 0 && (await page.locator('.public-apply').innerText()).includes('已通过')
        : await page.locator('.public-feedback textarea').count() === 1 && await page.locator('.public-feedback textarea').inputValue() === '原建议')
    }
    check('noWrites', writes === 0)
    for (const width of [1440, 390, 320]) {
      await page.setViewportSize({ width, height: 900 })
      check(`width${width}`, await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth))
    }
    await page.screenshot({ path: fileURLToPath(new URL(`${failed}.png`, output)), fullPage: true })
    await context.close()
  }
  checks.push({ name: 'browserErrors', passed: errors.length === 0 })
  const result = { check: '公开活动/本人报名/反馈读取状态', api: '全部GET替身，不写库', checks, errors }
  await writeFile(new URL('结果.json', output), JSON.stringify(result, null, 2))
  console.log(JSON.stringify(result, null, 2))
  assert.ok(checks.every(item => item.passed), checks.filter(item => !item.passed).map(item => item.name).join(', '))
}

async function audit() {
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
  async function registrationVersion(ctx, activityId) {
    const items = (await json(ctx, 'GET', '/api/me/registrations')).items
    return items.find(item => item.activityId === activityId)?.version ?? 0
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
  // 仅针对本批显式设置 session.timeout=1m 的独立测试进程；真实等待而非修改数据库或伪造401。
  let naturalIdle = null
  if (process.argv.includes('--idle-expiry')) {
    const draft = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
      name: '自然闲置核对草稿', ownerMemberId: ownerMember, registrationScope: 'INTERNAL',
    }, { 'Idempotency-Key': `idle-${stamp}` })
    const ownerPage = await owner.newPage()
    const errors = []
    ownerPage.on('pageerror', error => errors.push(error.message))
    await ownerPage.goto(`/org/${org.id}/activities/${draft.id}?tab=proposal`, { waitUntil: 'networkidle' })
    const proposal = ownerPage.locator('textarea').first()
    await proposal.fill('自然闲置后应清理的未保存方案')
    await ownerPage.waitForLoadState('networkidle')
    let idleRequests = 0
    const countIdleRequest = request => { if (new URL(request.url()).pathname.startsWith('/api/')) idleRequests++ }
    ownerPage.on('request', countIdleRequest)
    const idleStart = Date.now()
    console.log(JSON.stringify({ phase: 'natural-idle', waitMs: 65000 }))
    await new Promise(resolve => setTimeout(resolve, 65000))
    ownerPage.off('request', countIdleRequest)
    assert.equal(idleRequests, 0, '闲置窗口内不应有API保活请求')
    const waitedMs = Date.now() - idleStart
    const expired = await ownerPage.evaluate(async () => (await fetch('/api/auth/me')).status)
    assert.equal(expired, 401, '真实闲置后的原Cookie应已失效，请核对测试进程1m配置')
    let redirects = 0
    const countRedirect = frame => { if (frame === ownerPage.mainFrame() && new URL(frame.url()).pathname === '/login') redirects++ }
    ownerPage.on('framenavigated', countRedirect)
    await ownerPage.evaluate(() => { for (let i = 0; i < 3; i++) document.dispatchEvent(new Event('visibilitychange')) })
    await ownerPage.waitForURL(url => url.pathname === '/login')
    await ownerPage.waitForLoadState('networkidle')
    ownerPage.off('framenavigated', countRedirect)
    assert.equal(redirects, 1, '并发失效检查只引导一次登录')
    assert.equal(await ownerPage.locator('.workspace').count(), 0)
    assert.equal(await ownerPage.getByRole('dialog', { name: '未保存的修改' }).count(), 0)
    assert.equal(new URL(ownerPage.url()).searchParams.get('next'), `/org/${org.id}/activities/${draft.id}?tab=proposal`)
    await ownerPage.getByPlaceholder('注册时设置的用户名').fill(ownerName)
    await ownerPage.getByPlaceholder('请输入密码').fill(password)
    await ownerPage.getByRole('button', { name: '进入工作区', exact: true }).click()
    await ownerPage.waitForURL(url => url.pathname === `/org/${org.id}/activities/${draft.id}`)
    await ownerPage.waitForLoadState('networkidle')
    assert.equal(await proposal.inputValue(), '')
    await proposal.fill('自然闲置重登后正常保存的方案')
    const saved = ownerPage.waitForResponse(response => response.url().endsWith('/proposal') && response.request().method() === 'PUT')
    await ownerPage.getByRole('button', { name: '保存方案', exact: true }).click()
    assert.equal((await saved).status(), 200, '重新登录后的真实写入须取得新CSRF')
    naturalIdle = { timeout: '测试进程1m，默认30m不变', waitedMs, idleRequests, oldCookieStatus: expired,
      redirects, clearedProtectedPageAndDraft: true, returnedToOriginalPage: true, freshCsrfWriteStatus: 200,
      visibilityTrigger: '闲置真实到期；切回事件由脚本触发' }
    assert.deepEqual(errors, [])
    console.log(JSON.stringify({ check: '自然闲置会话到期专项', naturalIdle, browserErrors: errors }))
    return
  }

  const activity = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
    name: 'F01 外部报名活动', description: '面向所有登录账号公开报名', location: '线上',
    ownerMemberId: ownerMember,
    startTime: new Date(Date.now() + 12 * 86400000).toISOString(),
    endTime: new Date(Date.now() + 12 * 86400000 + 7200000).toISOString(),
    registrationScope: 'PUBLIC', capacity: 8,
    registrationStart: new Date(Date.now() - 86400000).toISOString(),
    registrationEnd: new Date(Date.now() + 11 * 86400000).toISOString(),
  }, { 'Idempotency-Key': `f01-${stamp}` })
  // F05：编辑、方案保存与确认必须携带当前版本号（活动创建后版本自 1 起）
  const activityPath = `/api/organizations/${org.id}/activities/${activity.id}`
  const currentVersion = async () => (await json(owner, 'GET', activityPath)).version
  await json(owner, 'PUT', `${activityPath}/proposal`,
    { content: '方案', version: await currentVersion() })
  await json(owner, 'POST', `${activityPath}/submit`)
  await json(owner, 'POST', `${activityPath}/approve`, { version: await currentVersion() })
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

  // 报名确认框可跨浏览器历史保留；离开原页面后，旧确认不得写入原活动或新活动。
  const other = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
    name: 'F01 旧确认不应影响的活动', description: '核对旧报名确认的活动范围', location: '线上',
    ownerMemberId: ownerMember, registrationScope: 'PUBLIC',
    startTime: new Date(Date.now() + 13 * 86400000).toISOString(),
    endTime: new Date(Date.now() + 13 * 86400000 + 7200000).toISOString(),
    capacity: 8, registrationStart: new Date(Date.now() - 86400000).toISOString(),
    registrationEnd: new Date(Date.now() + 12 * 86400000).toISOString(),
  }, { 'Idempotency-Key': `f01-other-${stamp}` })
  const otherApiPath = `/api/organizations/${org.id}/activities/${other.id}`
  await json(owner, 'PUT', `${otherApiPath}/proposal`, { content: '独立活动方案', version: other.version })
  await json(owner, 'POST', `${otherApiPath}/submit`)
  await json(owner, 'POST', `${otherApiPath}/approve`, { version: (await json(owner, 'GET', otherApiPath)).version })
  await json(owner, 'POST', `${otherApiPath}/registration-open`, { open: true })
  for (const ctx of [participant, owner]) {
    for (const target of [activity.id, other.id]) {
      await json(ctx, 'POST', `/api/activities/${target}/registrations`, { displayName: '旧确认核对报名者' })
    }
  }
  const staleConfirmations = [], staleFailures = []
  const ownerRegistrationPage = await owner.newPage()
  ownerRegistrationPage.on('pageerror', e => errors.push(e.message))
  async function checkStaleRegistrationConfirmation(scope) {
    const internal = scope.startsWith('internal'), manager = scope === 'internal-manager'
    const ctx = internal ? owner : participant, targetPage = internal ? ownerRegistrationPage : page
    const otherPath = internal ? `/org/${org.id}/activities/${other.id}?tab=registration` : `/public/activities/${other.id}`
    await targetPage.goto(otherPath, { waitUntil: 'networkidle' })
    if (internal) {
      await targetPage.getByRole('link', { name: '活动管理', exact: true }).click()
      await targetPage.getByRole('button', { name: activity.name, exact: true }).click()
      await targetPage.getByRole('tab', { name: '报名', exact: true }).click()
    } else {
      await targetPage.getByRole('button', { name: '账号菜单', exact: true }).click()
      await targetPage.getByText('返回我的工作区', { exact: true }).click()
      if (scope !== 'account') {
        await targetPage.locator('li').filter({ has: targetPage.getByText(activity.name, { exact: true }) })
          .getByRole('button', { name: '查看活动', exact: true }).click()
      }
    }
    await targetPage.waitForLoadState('networkidle')
    function actionFor() {
      if (manager) return targetPage.locator('.el-table__row').filter({ hasText: outsiderName })
        .getByRole('button', { name: '管理取消', exact: true })
      if (scope === 'account') return targetPage.locator('li').filter({ has: targetPage.getByText(activity.name, { exact: true }) })
        .getByRole('button', { name: '取消报名', exact: true })
      return targetPage.locator(internal ? '.reg-mine' : '.public-apply').getByRole('button', { name: '取消报名', exact: true })
    }
    await actionFor().click()
    const confirmation = targetPage.locator('.el-message-box')
    await confirmation.waitFor()
    if (manager) await confirmation.locator('input').fill('旧确认原因')
    for (let step = 0; new URL(targetPage.url()).pathname !== new URL(otherPath, base).pathname && step < 5; step++) {
      await targetPage.goBack()
    }
    await targetPage.waitForURL(url => url.pathname === new URL(otherPath, base).pathname)
    await targetPage.waitForLoadState('networkidle')
    let writes = 0
    const count = request => { if (request.method() === 'POST' && /\/registrations\/(?:cancel-self|\d+\/cancel-org)$/.test(new URL(request.url()).pathname)) writes++ }
    targetPage.on('request', count)
    await confirmation.getByRole('button', { name: '取消报名', exact: true }).click()
    await confirmation.waitFor({ state: 'hidden' })
    await targetPage.waitForLoadState('networkidle')
    targetPage.off('request', count)
    const affectedCtx = manager ? participant : ctx
    const registrations = (await json(affectedCtx, 'GET', '/api/me/registrations')).items
    const states = [activity.id, other.id].map(id => registrations.find(x => x.activityId === id).status)
    staleConfirmations.push({ scope, writes, states })
    if (writes !== 0 || states.some(state => state !== 'PENDING')) staleFailures.push(scope)
    // 仅通过测试 API 恢复本轮反例数据，不删除记录，也不让反例状态影响后续原流程。
    for (const target of [activity.id, other.id]) {
      await json(affectedCtx, 'POST', `/api/activities/${target}/registrations`, { displayName: '旧确认核对报名者', version: await registrationVersion(affectedCtx, target) })
    }
    // 新打开的同页确认仍须成功，不能通过禁用取消入口让反例通过。
    await targetPage.goto(scope === 'account' ? '/' : internal
      ? `/org/${org.id}/activities/${activity.id}?tab=registration` : detailPath, { waitUntil: 'networkidle' })
    await actionFor().click()
    if (manager) await confirmation.locator('input').fill('当前确认原因')
    const freshResponse = targetPage.waitForResponse(response => response.request().method() === 'POST'
      && /\/registrations\/(?:cancel-self|\d+\/cancel-org)$/.test(new URL(response.url()).pathname))
    await confirmation.getByRole('button', { name: '取消报名', exact: true }).click()
    const fresh = await freshResponse
    assert.ok(fresh.ok(), `${scope} 当前确认应成功`)
    await targetPage.waitForLoadState('networkidle')
    const freshState = (await json(affectedCtx, 'GET', '/api/me/registrations')).items.find(x => x.activityId === activity.id).status
    assert.equal(freshState, manager ? 'CANCELLED_ORG' : 'CANCELLED_SELF')
    staleConfirmations.at(-1).freshConfirmation = { status: fresh.status(), state: freshState }
    await json(affectedCtx, 'POST', `/api/activities/${activity.id}/registrations`, { displayName: '旧确认核对报名者', version: await registrationVersion(affectedCtx, activity.id) })
  }
  for (const scope of ['public', 'internal-self', 'internal-manager', 'account']) await checkStaleRegistrationConfirmation(scope)
  console.log(JSON.stringify({ staleRegistrationConfirmations: staleConfirmations }))
  assert.deepEqual(staleFailures, [], '离开页面后的报名确认不得发送写请求，两个活动的报名都应保留待审状态')

  // 报名写请求的旧 401 不得清理后来重新登录的会话；响应由脚本延后并替换，未写入后端。
  let releaseOldResponse, observeOldRequest
  const oldResponseGate = new Promise(resolve => { releaseOldResponse = resolve })
  const oldRequestStarted = new Promise(resolve => { observeOldRequest = resolve })
  const cancelPath = `/api/activities/${activity.id}/registrations/cancel-self`
  await ownerRegistrationPage.route(`**${cancelPath}`, async route => {
    observeOldRequest()
    await oldResponseGate
    await route.fulfill({ status: 401, contentType: 'application/problem+json',
      body: JSON.stringify({ status: 401, code: 'AUTH_REQUIRED', detail: '延后的旧会话响应' }) })
  }, { times: 1 })
  await ownerRegistrationPage.locator('.reg-mine').getByRole('button', { name: '取消报名', exact: true }).click()
  await ownerRegistrationPage.locator('.el-message-box').getByRole('button', { name: '取消报名', exact: true }).click()
  await oldRequestStarted
  try {
    await ownerRegistrationPage.getByRole('button', { name: '账号菜单', exact: true }).click()
    await ownerRegistrationPage.getByText('退出登录', { exact: true }).click()
    await ownerRegistrationPage.waitForURL(url => url.pathname === '/login')
    await ownerRegistrationPage.getByPlaceholder('注册时设置的用户名').fill(ownerName)
    await ownerRegistrationPage.getByPlaceholder('请输入密码').fill(password)
    await ownerRegistrationPage.getByRole('button', { name: '进入工作区', exact: true }).click()
    await ownerRegistrationPage.waitForURL(url => url.pathname === '/')
    await ownerRegistrationPage.locator('li').filter({ has: ownerRegistrationPage.getByText(org.name, { exact: true }) })
      .getByRole('button', { name: '进入工作区', exact: true }).click()
    await ownerRegistrationPage.getByRole('link', { name: '活动管理', exact: true }).click()
    await ownerRegistrationPage.getByRole('button', { name: activity.name, exact: true }).click()
    await ownerRegistrationPage.getByRole('tab', { name: '报名', exact: true }).click()
    const delayed401 = ownerRegistrationPage.waitForResponse(response => response.url().endsWith(cancelPath))
    releaseOldResponse()
    assert.equal((await delayed401).status(), 401)
    await ownerRegistrationPage.waitForLoadState('networkidle')
    assert.equal(new URL(ownerRegistrationPage.url()).pathname, `/org/${org.id}/activities/${activity.id}`,
      '报名旧 401 不得把重新登录的新会话引导到登录页')
    assert.equal(await ownerRegistrationPage.locator('.workspace').count(), 1, '报名旧 401 不得卸载重新登录后的工作区')
    assert.equal((await call(owner, 'GET', '/api/auth/me')).status(), 200)
    assert.equal((await json(owner, 'GET', '/api/me/registrations')).items.find(x => x.activityId === activity.id).status, 'PENDING')
  } finally { releaseOldResponse() }
  await ownerRegistrationPage.close()
  // 内部本人取消的正向回归新增了负责人报名，原反馈流程只批准外部参与者。
  assert.ok((await call(owner, 'POST', `/api/activities/${activity.id}/registrations/cancel-self`, { version: await registrationVersion(owner, activity.id) })).ok())

  // 3. 我的报名：含活动名、安排与取消按钮
  await page.goto('/', { waitUntil: 'networkidle' })
  await page.waitForSelector('text=我的报名', { timeout: 15000 })
  const accountBody = await page.locator('body').innerText()
  const hasCancelInAccount = (await page.getByRole('button', { name: '取消报名' }).count()) > 0
  const myShot = fileURLToPath(new URL('../验证截图/F01-外部账号我的报名.png', import.meta.url))
  await page.screenshot({ path: myShot, fullPage: true })
  shots.push(myShot)

  // 4. 调整期：撤回活动后本人状态仍可读并显示调整提示
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/withdraw-approval`, { reason: '安排调整', version: await currentVersion() })
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
  const cancelApi = await call(outsider, 'POST', `/api/activities/${activity.id}/registrations/cancel-self`, { version: await registrationVersion(outsider, activity.id) })
  const myAfterCancel = (await json(outsider, 'GET', '/api/me/registrations')).items
    .find((x) => x.activityId === activity.id)

  assert.ok(anonHasLoginButton && landedOnPublicPage && hasCancelInAccount)
  assert.ok(adjustingBody.includes('活动安排调整中'))
  assert.equal(anonDetailStatus, 404)
  assert.equal(cancelApi.status(), 204)
  assert.equal(myAfterCancel.status, 'CANCELLED_SELF')

  // 恢复活动后，经页面再次报名并取消，再报名获批，验证真实外部参与者反馈。
  await json(owner, 'POST', `${activityPath}/submit`)
  await json(owner, 'POST', `${activityPath}/approve`, { version: await currentVersion() })
  await json(owner, 'POST', `${activityPath}/registration-open`, { open: true })
  await page.setViewportSize({ width: 390, height: 844 })
  async function applyFromPage() {
    await page.goto(detailPath, { waitUntil: 'networkidle' })
    await page.getByRole('button', { name: /我要报名|更新报名信息/ }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('展示姓名', { exact: true }).fill('外部参与者')
    await dialog.getByRole('button', { name: '提交', exact: true }).click()
    await dialog.waitFor({ state: 'hidden' })
    await page.getByText('待审核', { exact: true }).waitFor()
  }
  await applyFromPage()
  await page.getByRole('button', { name: '取消报名', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '取消报名', exact: true }).click()
  await page.getByText('本人取消', { exact: true }).waitFor()
  await applyFromPage()
  const registration = (await json(owner, 'GET', `${activityPath}/registrations`)).items.find(x => x.accountUsername === outsiderName)
  const approved = await call(owner, 'POST', `${activityPath}/registrations/${registration.registrationId}/approve`, { version: registration.version })
  assert.ok(approved.ok())
  await json(owner, 'POST', `${activityPath}/start-execution`, { version: await currentVersion() })
  await json(owner, 'POST', `${activityPath}/finish-execution`, { version: await currentVersion() })
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '活动反馈', exact: true }).waitFor()
  await page.locator('.el-rate__item').nth(4).click()
  await page.getByPlaceholder('文字建议（可选，不超过 2000 字符）').fill('希望下次增加指引')
  // 另一会话修改密码，让浏览器已有会话在服务端真实失效，而非替身 401。
  const newPassword = password + 'X'
  assert.ok((await call(outsider, 'POST', '/api/auth/password', { currentPassword: password, newPassword })).ok())
  await page.getByRole('button', { name: '提交反馈', exact: true }).click()
  await page.waitForURL(url => url.pathname === '/login', { timeout: 10000 })
  assert.equal(new URL(page.url()).searchParams.get('next'), detailPath)
  await page.getByPlaceholder('注册时设置的用户名').fill(outsiderName)
  await page.getByPlaceholder('请输入密码').fill(newPassword)
  await page.getByRole('button', { name: '进入工作区', exact: true }).click()
  await page.waitForURL(url => url.pathname === detailPath)
  await page.getByRole('button', { name: '提交反馈', exact: true }).waitFor()
  await page.locator('.el-rate__item').nth(4).click()
  await page.getByPlaceholder('文字建议（可选，不超过 2000 字符）').fill('重新登录后提交的反馈')
  await page.getByRole('button', { name: '提交反馈', exact: true }).click()
  await page.getByRole('button', { name: '更新反馈', exact: true }).waitFor()
  const feedback = await json(participant, 'GET', `/api/public/activities/${activity.id}/feedback`)
  assert.equal(feedback.myRating, 5)
  assert.equal(feedback.count, 1)
  assert.equal((await json(participant, 'GET', '/api/me/organizations')).items.length, 0)
  assert.equal((await call(participant, 'GET', activityPath)).status(), 404)
  await page.screenshot({ path: fileURLToPath(new URL('../验证截图/F01-手机反馈与重登.png', import.meta.url)), fullPage: true, animations: 'disabled' })
  assert.deepEqual(await page.evaluate(() => [...document.querySelectorAll('body *')]
    .filter(el => el.getBoundingClientRect().right > innerWidth + 1)
    .map(el => ({ tag: el.tagName, class: el.className }))), [], '手机布局不应溢出')
  for (const width of [320, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${width}px 页面溢出`)
  }
  assert.deepEqual(errors, [])

  // 切回标签页核对：401 来自真实后端；visibilitychange 由脚本触发，不模拟自然闲置超时。
  const ownerControl = await browser.newContext({ baseURL: base })
  const ownerCsrf = await json(ownerControl, 'GET', '/api/auth/csrf')
  assert.equal((await ownerControl.request.post('/api/auth/login', {
    form: { username: ownerName, password }, headers: { [ownerCsrf.headerName]: ownerCsrf.token },
  })).status(), 204)
  const draft = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
    name: '会话刷新内部草稿', ownerMemberId: ownerMember, registrationScope: 'INTERNAL',
  }, { 'Idempotency-Key': `session-${stamp}` })
  const draftPath = `/org/${org.id}/activities/${draft.id}?tab=proposal`
  const ownerPage = await owner.newPage()
  ownerPage.on('pageerror', error => errors.push(error.message))
  let ownerPassword = password
  for (const dirty of [false, true]) {
    await ownerPage.goto(draftPath, { waitUntil: 'networkidle' })
    const proposal = ownerPage.locator('textarea').first()
    await proposal.waitFor()
    if (dirty) await proposal.fill('会话失效前未保存的方案')
    const nextPassword = ownerPassword + 'X'
    assert.equal((await call(ownerControl, 'POST', '/api/auth/password', {
      currentPassword: ownerPassword, newPassword: nextPassword,
    })).status(), 204)
    const expiredMe = ownerPage.waitForResponse(response => response.url().endsWith('/api/auth/me'))
    await ownerPage.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
    assert.equal((await expiredMe).status(), 401, '切回检查必须使用真实失效会话')
    try {
      await ownerPage.waitForURL(url => url.pathname === '/login', { timeout: 10000 })
    } catch (error) {
      console.error(JSON.stringify({ visibilityRecoveryFailed: true, dirty,
        workspaceCount: await ownerPage.locator('.workspace').count(),
        unsavedDialogCount: await ownerPage.getByRole('dialog', { name: '未保存的修改' }).count() }))
      throw error
    }
    assert.equal(new URL(ownerPage.url()).searchParams.get('next'), draftPath)
    assert.equal(await ownerPage.locator('.workspace').count(), 0, '失效后必须清除组织页面')
    assert.equal(await ownerPage.getByRole('dialog', { name: '未保存的修改' }).count(), 0, '草稿保护不能阻止失效登录引导')
    await ownerPage.getByPlaceholder('注册时设置的用户名').fill(ownerName)
    await ownerPage.getByPlaceholder('请输入密码').fill(nextPassword)
    await ownerPage.getByRole('button', { name: '进入工作区', exact: true }).click()
    await ownerPage.waitForURL(url => url.pathname === `/org/${org.id}/activities/${draft.id}`)
    await ownerPage.waitForLoadState('networkidle')
    assert.equal(await proposal.inputValue(), '', '未保存方案不应在重登后回填')
    ownerPassword = nextPassword
  }
  // 网络中断不是失效证据，保留当前会话和页面，不引导登录。
  await ownerPage.route('**/api/auth/me', route => route.abort('failed'), { times: 1 })
  const failedMe = ownerPage.waitForEvent('requestfailed', { predicate: request => request.url().endsWith('/api/auth/me') })
  await ownerPage.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
  await failedMe
  await ownerPage.waitForLoadState('networkidle')
  assert.equal(new URL(ownerPage.url()).pathname, `/org/${org.id}/activities/${draft.id}`)
  assert.equal(await ownerPage.locator('.workspace').count(), 1)
  assert.deepEqual(errors, [])
  await ownerPage.screenshot({ path: fileURLToPath(new URL('../验证截图/会话刷新-重登内部草稿.png', import.meta.url)), fullPage: true, animations: 'disabled' })

  console.log(JSON.stringify({
    check: 'F01 外部参与者报名、取消、反馈与真实会话失效恢复',
    staleRegistrationConfirmations: staleConfirmations,
    staleRegistration401: { keptNewSession: true, simulatedDelayedResponse: true, backendMutation: false },
    sessionRecovery: { returnedToOriginalPage: true, feedbackCount: feedback.count, rating: feedback.myRating },
    visibilityRecovery: { cleanAndDirtyDrafts: true, protectedPageCleared: true, networkFailureKeepsSession: true,
      trigger: '脚本触发 visibilitychange；另一真实会话修改密码；非自然闲置超时' },
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
}
try {
  if (process.argv.includes('--read-states')) await auditReadStates()
  else await audit()
} finally {
  await browser.close()
}
