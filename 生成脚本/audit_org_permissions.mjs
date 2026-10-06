// 真实测试后端的撤权刷新；切回事件和迟到响应由浏览器脚本控制，不代表推送或自然时序。
import assert from 'node:assert/strict'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const output = new URL('../验证截图/组织撤权/', import.meta.url)
const errors = []
let releaseOld
let phase = '既有权限与草稿核对'
try {
  await mkdir(output, { recursive: true })
  const stamp = process.env.FLOW_RUN_STAMP || Date.now()
  const password = `Pass${stamp}word`
  console.log(JSON.stringify({ runStamp: stamp, database: 'activity_test' }))
  async function call(context, method, path, data, headers = {}) {
    if (method !== 'GET') {
      const csrf = await (await context.request.get('/api/auth/csrf')).json()
      headers[csrf.headerName] = csrf.token
    }
    const response = await context.request.fetch(path, { method, headers, ...(data === undefined ? {} : { data }) })
    assert.ok(response.ok(), `${method} ${path}: ${response.status()}`)
    return response.status() === 204 ? null : response.json()
  }
  async function expectStatus(context, method, path, expected, data) {
    const headers = {}
    if (method !== 'GET') {
      const csrf = await call(context, 'GET', '/api/auth/csrf')
      headers[csrf.headerName] = csrf.token
    }
    const response = await context.request.fetch(path, { method, headers, ...(data === undefined ? {} : { data }) })
    assert.equal(response.status(), expected, `${method} ${path}`)
    return response
  }
  const pngBytes = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64')
  async function prepareAttachment(context, path, name, key) {
    const csrf = await call(context, 'GET', '/api/auth/csrf')
    const response = await context.request.post(path, {
      headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': key },
      multipart: { file: { name, mimeType: 'image/png', buffer: pngBytes } },
    })
    assert.equal(response.status(), 201, `准备资料 ${path}`)
    return response.json()
  }
  async function uiWrite(page, button, method, endpoint, expected = 200) {
    const completed = page.waitForResponse(response => response.request().method() === method && new URL(response.url()).pathname === endpoint)
    await button.click()
    const response = await completed
    assert.equal(response.status(), expected, `页面 ${method} ${endpoint}`)
    // 部分页面写入声明 response:none；成功以状态及随后页面/API读取为证据，避免等未消费的正文。
    return response.status() >= 400 ? response.json() : null
  }
  async function selectMember(page, dialog, label, username) {
    await dialog.locator('.el-form-item').filter({ hasText: label }).locator('.el-select').click()
    await page.locator('.el-select-dropdown:visible').getByRole('option', { name: username, exact: true }).click()
  }
  async function actor(role) {
    const context = await browser.newContext({ baseURL: 'http://127.0.0.1:5174', viewport: { width: 1440, height: 1000 } })
    const username = `perm_${role}_${stamp}`
    if (!process.env.FLOW_RUN_STAMP) await call(context, 'POST', '/api/auth/register', { username, password })
    const csrf = await call(context, 'GET', '/api/auth/csrf')
    assert.equal((await context.request.post('/api/auth/login', {
      form: { username, password }, headers: { [csrf.headerName]: csrf.token },
    })).status(), 204)
    return { context, username }
  }
  const owner = await actor('owner')
  const subject = await actor('subject')
  const leaver = await actor('leaver')
  const runStamp = Date.now()
  const createPage = await owner.context.newPage()
  createPage.setDefaultTimeout(6000)
  createPage.on('pageerror', error => errors.push(error.message))
  await createPage.goto('/', { waitUntil: 'networkidle' })
  await createPage.getByRole('button', { name: '创建组织', exact: true }).click()
  const createDialog = createPage.getByRole('dialog', { name: '创建组织', exact: true })
  const createName = `创建核对-${runStamp}`
  await createDialog.locator('input').fill(createName)
  let loseCreationResponse = true
  await createPage.route('**/api/organizations', async route => {
    if (!loseCreationResponse || route.request().method() !== 'POST') return route.continue()
    loseCreationResponse = false
    const response = await route.fetch()
    assert.equal(response.status(), 201)
    await route.fulfill({ status: 503, json: { status: 503, code: 'CREATE_RESPONSE_LOST', detail: '创建响应未收到，请核对结果。' } })
  })
  await createDialog.getByRole('button', { name: '创建', exact: true }).click()
  await createDialog.getByRole('alert').waitFor()
  assert.equal((await call(owner.context, 'GET', '/api/me/organizations')).items.filter(item => item.name === createName).length, 1)
  assert.equal(await createDialog.locator('input').isDisabled(), true)
  await createDialog.getByRole('button', { name: '取消', exact: true }).click()
  await createPage.getByRole('dialog', { name: '未保存的修改', exact: true }).getByRole('button', { name: '继续填写', exact: true }).click()
  assert.equal(await createDialog.locator('input').inputValue(), createName)
  await createPage.setViewportSize({ width: 390, height: 844 })
  await createPage.screenshot({ path: fileURLToPath(new URL('组织创建待核对-手机.png', output)), fullPage: true, animations: 'disabled' })
  assert.equal(await createPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  const creationChecked = createPage.waitForResponse(response => new URL(response.url()).pathname === '/api/organizations' && response.request().method() === 'POST')
  await createDialog.getByRole('button', { name: /^(创建|核对创建结果)$/ }).click()
  assert.equal((await creationChecked).status(), 201)
  assert.equal((await call(owner.context, 'GET', '/api/me/organizations')).items.filter(item => item.name === createName).length, 1, '创建已写入但响应未知时重试不能重复建组织')
  await createDialog.waitFor({ state: 'hidden' })
  await createPage.close()
  const org = await call(owner.context, 'POST', '/api/organizations', { name: `撤权核对-${runStamp}` })
  const other = await call(subject.context, 'POST', '/api/organizations', { name: `保留身份-${runStamp}` })
  const path = `/api/organizations/${org.id}`
  // 邀请已实际创建而页面响应丢失：按原键核对，不再次发出有效邀请码。
  const membersPage = await owner.context.newPage()
  membersPage.setDefaultTimeout(6000)
  membersPage.on('pageerror', error => errors.push(error.message))
  await membersPage.goto(`/org/${org.id}/activities`, { waitUntil: 'networkidle' })
  await membersPage.getByRole('link', { name: '成员管理', exact: true }).click()
  await membersPage.waitForURL(url => url.pathname === `/org/${org.id}/members`)
  await membersPage.getByRole('button', { name: '创建邀请', exact: true }).click()
  const inviteDialog = membersPage.getByRole('dialog', { name: '创建邀请', exact: true })
  let loseInviteResponse = true
  let originalInviteRequest
  await membersPage.route(`**${path}/invitations`, async route => {
    if (!loseInviteResponse || route.request().method() !== 'POST') return route.continue()
    loseInviteResponse = false
    originalInviteRequest = { key: route.request().headers()['idempotency-key'], input: route.request().postDataJSON() }
    const response = await route.fetch()
    assert.equal(response.status(), 201)
    await route.fulfill({ status: 503, json: { status: 503, code: 'INVITE_RESPONSE_LOST', detail: '邀请创建响应未收到，请核对结果。' } })
  })
  await inviteDialog.getByRole('button', { name: '生成邀请码', exact: true }).click()
  await membersPage.getByText('邀请创建响应未收到，请核对结果。', { exact: true }).waitFor()
  assert.equal((await call(owner.context, 'GET', `${path}/invitations`)).items.length, 1)
  assert.equal(await inviteDialog.locator('input').first().isDisabled(), true)
  await inviteDialog.getByRole('button', { name: '取消', exact: true }).click()
  await membersPage.getByRole('dialog', { name: '未保存的修改', exact: true })
    .getByRole('button', { name: '继续填写', exact: true }).click()
  await membersPage.setViewportSize({ width: 390, height: 844 })
  await membersPage.screenshot({ path: fileURLToPath(new URL('邀请待核对-手机.png', output)), fullPage: true, animations: 'disabled' })
  assert.equal(await membersPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await membersPage.setViewportSize({ width: 1440, height: 1000 })
  await membersPage.goBack()
  await membersPage.getByRole('dialog', { name: '未保存的修改', exact: true })
    .getByRole('button', { name: '继续填写', exact: true }).click()
  assert.equal(new URL(membersPage.url()).pathname, `/org/${org.id}/members`)
  const inviteChecked = membersPage.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === `${path}/invitations`)
  await inviteDialog.getByRole('button', { name: /^(生成邀请码|核对创建结果)$/ }).click()
  const inviteResponse = await inviteChecked
  assert.equal(inviteResponse.status(), 201)
  assert.ok(originalInviteRequest.key, '邀请创建必须生成防重键')
  assert.equal(inviteResponse.request().headers()['idempotency-key'], originalInviteRequest.key)
  assert.deepEqual(inviteResponse.request().postDataJSON(), originalInviteRequest.input)
  assert.equal((await call(owner.context, 'GET', `${path}/invitations`)).items.length, 1, '邀请已创建但响应未知时核对不能再发一份邀请')
  assert.equal((await inviteResponse.json()).code ?? null, null, '核对不能恢复只返回一次的邀请码明文')
  await inviteDialog.getByText('邀请码未收到，无法再次显示。请撤销此邀请后重新生成。', { exact: true }).waitFor()
  await inviteDialog.getByRole('button', { name: '撤销此邀请', exact: true }).click()
  await membersPage.getByRole('dialog', { name: '撤销邀请', exact: true }).getByRole('button', { name: '撤销', exact: true }).click()
  await membersPage.getByText('暂无有效邀请；创建邀请后可在此撤销。', { exact: true }).waitFor()
  await inviteDialog.getByRole('button', { name: '生成邀请码', exact: true }).click()
  await inviteDialog.getByRole('button', { name: '复制邀请码', exact: true }).waitFor()
  await inviteDialog.getByRole('button', { name: '我已保存', exact: true }).click()
  assert.equal((await call(owner.context, 'GET', `${path}/invitations`)).items.length, 1)
  await membersPage.close()
  const invitation = await call(owner.context, 'POST', `${path}/invitations`, {})
  await call(subject.context, 'POST', '/api/invitations/accept', { code: invitation.code })
  const members = (await call(owner.context, 'GET', `${path}/members`)).items
  const subjectMember = members.find(member => member.username === subject.username).memberId
  const ownerMember = members.find(member => member.username === owner.username).memberId
  // 离开成员页而组织不变时，全局旧确认也必须失效。
  const memberActionPage = await owner.context.newPage()
  memberActionPage.setDefaultTimeout(6000)
  memberActionPage.on('pageerror', error => errors.push(error.message))
  await memberActionPage.goto(`/org/${org.id}/activities`, { waitUntil: 'networkidle' })
  await memberActionPage.getByRole('link', { name: '成员管理', exact: true }).click()
  await memberActionPage.waitForURL(url => url.pathname === `/org/${org.id}/members`)
  await memberActionPage.locator('.list-table .el-table__row').filter({ hasText: subject.username })
    .getByRole('button', { name: '授予负责人', exact: true }).click()
  await memberActionPage.getByRole('dialog', { name: '授予负责人', exact: true }).waitFor()
  await memberActionPage.goBack()
  await memberActionPage.waitForURL(url => url.pathname === `/org/${org.id}/activities`)
  let oldMemberWrites = 0
  const countMemberWrite = request => { if (request.method() === 'POST' && request.url().endsWith('/promote-owner')) oldMemberWrites++ }
  memberActionPage.on('request', countMemberWrite)
  await memberActionPage.getByRole('dialog', { name: '授予负责人', exact: true })
    .getByRole('button', { name: '授予负责人', exact: true }).click()
  await memberActionPage.getByRole('dialog', { name: '授予负责人', exact: true }).waitFor({ state: 'hidden' })
  await memberActionPage.waitForLoadState('networkidle')
  assert.equal((await call(owner.context, 'GET', `${path}/members`)).items.find(member => member.memberId === subjectMember).role,
    'MEMBER', '离开成员页后旧确认不得授予负责人')
  assert.equal(oldMemberWrites, 0, '旧成员确认失效后不得发出写请求')
  memberActionPage.off('request', countMemberWrite)
  await memberActionPage.getByRole('link', { name: '成员管理', exact: true }).click()
  await memberActionPage.waitForURL(url => url.pathname === `/org/${org.id}/members`)
  await memberActionPage.locator('.list-table .el-table__row').filter({ hasText: subject.username })
    .getByRole('button', { name: '授予负责人', exact: true }).click()
  const promoted = memberActionPage.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `${path}/members/${subjectMember}/promote-owner`)
  await memberActionPage.getByRole('dialog', { name: '授予负责人', exact: true })
    .getByRole('button', { name: '授予负责人', exact: true }).click()
  assert.equal((await promoted).status(), 200, '重新打开当前页面的确认仍可正常授予负责人')
  assert.equal((await call(owner.context, 'GET', `${path}/members`)).items.find(member => member.memberId === subjectMember).role, 'OWNER')
  await memberActionPage.close()
  const draft = await call(owner.context, 'POST', `${path}/activities`, {
    name: '撤权前可见的其他负责人草稿', ownerMemberId: ownerMember, registrationScope: 'INTERNAL',
  }, { 'Idempotency-Key': `perm-${stamp}` })

  const detail = await subject.context.newPage()
  const settings = await subject.context.newPage()
  for (const page of [detail, settings]) {
    page.setDefaultTimeout(6000)
    page.on('pageerror', error => errors.push(error.message))
  }

  // 同身份切回不能把旧草稿的版本自动换成最新值，绕过后端冲突保护。
  let concurrent = await call(owner.context, 'POST', `${path}/activities`, {
    name: '并发草稿核对', description: '核对草稿版本及审批前置条件', ownerMemberId: ownerMember, registrationScope: 'INTERNAL', location: '线上',
    startTime: '2027-10-01T14:00:00+08:00', endTime: '2027-10-01T16:00:00+08:00',
  }, { 'Idempotency-Key': `conflict-${stamp}` })
  const concurrentPath = `${path}/activities/${concurrent.id}`
  const concurrentPage = await subject.context.newPage()
  concurrentPage.setDefaultTimeout(6000)
  concurrentPage.on('pageerror', error => errors.push(error.message))
  async function refreshConcurrent() {
    const response = concurrentPage.waitForResponse(response => new URL(response.url()).pathname === concurrentPath)
    await concurrentPage.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
    assert.equal((await response).status(), 200)
    await concurrentPage.waitForLoadState('networkidle')
  }
  const versionPrompt = concurrentPage.getByRole('dialog', { name: '活动已更新', exact: true })
  async function saveOrPrompt(button, endpoint, method) {
    const outcome = Promise.race([
      versionPrompt.waitFor().catch(() => undefined),
      concurrentPage.waitForResponse(response => new URL(response.url()).pathname === endpoint && response.request().method() === method)
        .then(response => response.finished()).catch(() => undefined),
    ])
    await button.click()
    await outcome
  }
  await concurrentPage.goto(`/org/${org.id}/activities/${concurrent.id}?tab=proposal`, { waitUntil: 'networkidle' })
  const proposal = concurrentPage.locator('.proposal-section textarea')
  await proposal.fill('本地旧版本方案')
  await call(owner.context, 'PUT', `${concurrentPath}/proposal`, { content: '另一负责人新方案', version: concurrent.version })
  await refreshConcurrent()
  assert.equal(await proposal.inputValue(), '本地旧版本方案')
  await saveOrPrompt(concurrentPage.getByRole('button', { name: '保存方案', exact: true }), `${concurrentPath}/proposal`, 'PUT')
  assert.equal((await call(owner.context, 'GET', concurrentPath)).proposal, '另一负责人新方案', '切回刷新不能使旧方案静默覆盖新方案')
  await versionPrompt.getByRole('button', { name: '保留草稿', exact: true }).click()
  assert.equal(await proposal.inputValue(), '本地旧版本方案')
  await concurrentPage.getByRole('button', { name: '保存方案', exact: true }).click()
  const acceptedProposal = concurrentPage.waitForResponse(response => response.url().endsWith('/proposal') && response.request().method() === 'PUT')
  await versionPrompt.getByRole('button', { name: '继续保存', exact: true }).click()
  assert.equal((await acceptedProposal).status(), 200)
  await concurrentPage.waitForLoadState('networkidle')
  concurrent = await call(owner.context, 'GET', concurrentPath)
  assert.equal(concurrent.proposal, '本地旧版本方案', '明确确认后可保存保留草稿')

  await concurrentPage.getByRole('button', { name: '编辑信息', exact: true }).click()
  const concurrentEdit = concurrentPage.getByRole('dialog', { name: '编辑活动信息', exact: true })
  await concurrentEdit.locator('input').first().fill('本地旧版本活动主题')
  concurrent = await call(owner.context, 'PATCH', concurrentPath, { name: '另一负责人新主题', version: concurrent.version })
  await refreshConcurrent()
  await saveOrPrompt(concurrentEdit.getByRole('button', { name: '保存', exact: true }), concurrentPath, 'PATCH')
  assert.equal((await call(owner.context, 'GET', concurrentPath)).name, '另一负责人新主题', '编辑弹窗不能跟随刷新换版本后覆盖新主题')
  await versionPrompt.getByRole('button', { name: '保留草稿', exact: true }).click()
  assert.equal(await concurrentEdit.locator('input').first().inputValue(), '本地旧版本活动主题')
  await concurrentEdit.getByRole('button', { name: '保存', exact: true }).click()
  concurrent = await call(owner.context, 'PATCH', concurrentPath, { name: '确认弹窗期间再次修改', version: concurrent.version })
  await refreshConcurrent()
  const staleEdit = concurrentPage.waitForResponse(response => new URL(response.url()).pathname === concurrentPath && response.request().method() === 'PATCH')
  await versionPrompt.getByRole('button', { name: '继续保存', exact: true }).click()
  assert.equal((await staleEdit).status(), 409, '确认弹窗期间变化也不能用更新版本覆盖')
  assert.equal((await call(owner.context, 'GET', concurrentPath)).name, '确认弹窗期间再次修改')
  assert.equal(await concurrentEdit.locator('input').first().inputValue(), '本地旧版本活动主题')
  await concurrentEdit.getByRole('button', { name: '取消', exact: true }).click()
  await concurrentPage.getByRole('dialog', { name: '未保存的修改', exact: true }).getByRole('button', { name: '放弃修改', exact: true }).click()

  await call(owner.context, 'POST', `${concurrentPath}/submit`)
  await concurrentPage.reload({ waitUntil: 'networkidle' })
  await concurrentPage.getByRole('button', { name: '驳回', exact: true }).click()
  const rejection = concurrentPage.getByRole('dialog', { name: '驳回活动', exact: true })
  await rejection.locator('textarea').fill('针对旧方案的驳回原因')
  await call(owner.context, 'POST', `${concurrentPath}/withdraw`)
  concurrent = await call(owner.context, 'GET', concurrentPath)
  await call(owner.context, 'PUT', `${concurrentPath}/proposal`, { content: '撤回重提后的新方案', version: concurrent.version })
  await call(owner.context, 'POST', `${concurrentPath}/submit`)
  await refreshConcurrent()
  const staleRejection = concurrentPage.waitForResponse(response => response.url().endsWith('/reject'))
  await rejection.getByRole('button', { name: '确认驳回', exact: true }).click()
  assert.equal((await staleRejection).status(), 409, '旧驳回弹窗须携被审阅的版本')
  assert.equal((await call(owner.context, 'GET', concurrentPath)).status, 'PENDING_APPROVAL')
  assert.equal(await rejection.locator('textarea').inputValue(), '针对旧方案的驳回原因')
  await rejection.getByRole('alert').waitFor()
  await rejection.getByRole('button', { name: '取消', exact: true }).click()
  await concurrentPage.getByRole('button', { name: '驳回', exact: true }).click()
  await rejection.locator('textarea').fill('复核新方案后驳回')
  const latestRejection = concurrentPage.waitForResponse(response => response.url().endsWith('/reject'))
  await rejection.getByRole('button', { name: '确认驳回', exact: true }).click()
  assert.equal((await latestRejection).status(), 200)
  await rejection.waitFor({ state: 'hidden' })

  concurrent = await call(owner.context, 'GET', concurrentPath)
  await call(owner.context, 'POST', `${concurrentPath}/submit`)
  concurrent = await call(owner.context, 'GET', concurrentPath)
  concurrent = await call(owner.context, 'POST', `${concurrentPath}/approve`, { version: concurrent.version })
  await concurrentPage.goto(`/org/${org.id}/activities/${concurrent.id}?tab=lifecycle`, { waitUntil: 'networkidle' })
  const lifecycleStart = concurrentPage.getByRole('tabpanel', { name: '执行与复盘', exact: true }).getByRole('button', { name: '启动执行', exact: true })
  await lifecycleStart.click()
  concurrent = await call(owner.context, 'PATCH', concurrentPath, { description: '启动确认期间更新的活动说明', version: concurrent.version })
  await refreshConcurrent()
  const staleStart = concurrentPage.waitForResponse(response => response.url().endsWith('/start-execution'))
  await concurrentPage.getByRole('dialog', { name: '启动执行', exact: true }).getByRole('button', { name: '启动执行', exact: true }).click()
  assert.equal((await staleStart).status(), 409)
  assert.equal((await call(owner.context, 'GET', concurrentPath)).status, 'APPROVED')
  await concurrentPage.getByRole('dialog', { name: '启动执行', exact: true }).waitFor({ state: 'hidden' })

  // 全局确认框可跨路由保留；旧活动的确认不能改用新活动参数发起写入。
  let untouched = await call(owner.context, 'POST', `${path}/activities`, {
    name: '确认框不能误启动的活动', description: '核对旧确认框的资源范围', ownerMemberId: ownerMember,
    registrationScope: 'INTERNAL', location: '线上', startTime: '2027-10-02T14:00:00+08:00', endTime: '2027-10-02T16:00:00+08:00',
  }, { 'Idempotency-Key': `untouched-${stamp}` })
  const untouchedPath = `${path}/activities/${untouched.id}`
  await call(owner.context, 'PUT', `${untouchedPath}/proposal`, { content: '独立活动方案', version: untouched.version })
  await call(owner.context, 'POST', `${untouchedPath}/submit`)
  untouched = await call(owner.context, 'GET', untouchedPath)
  untouched = await call(owner.context, 'POST', `${untouchedPath}/approve`, { version: untouched.version })
  while (untouched.version < concurrent.version) untouched = await call(owner.context, 'PATCH', untouchedPath, {
    description: `核对版本 ${untouched.version}`, version: untouched.version,
  })
  assert.equal(untouched.version, concurrent.version)
  await concurrentPage.goto(`/org/${org.id}/activities/${untouched.id}?tab=lifecycle`, { waitUntil: 'networkidle' })
  await concurrentPage.getByRole('link', { name: '活动管理', exact: true }).click()
  await concurrentPage.getByRole('button', { name: concurrent.name, exact: true }).click()
  await concurrentPage.getByRole('tab', { name: '执行与复盘', exact: true }).click()
  await lifecycleStart.click()
  for (let step = 0; new URL(concurrentPage.url()).pathname !== `/org/${org.id}/activities/${untouched.id}` && step < 5; step++) {
    await concurrentPage.goBack()
  }
  await concurrentPage.waitForURL(url => url.pathname === `/org/${org.id}/activities/${untouched.id}`)
  await concurrentPage.getByRole('heading', { name: untouched.name, exact: true }).waitFor()
  const abandonedStart = concurrentPage.getByRole('dialog', { name: '启动执行', exact: true })
  let abandonedWrites = 0
  const countAbandonedWrite = request => { if (request.url().endsWith('/start-execution') && request.method() === 'POST') abandonedWrites++ }
  concurrentPage.on('request', countAbandonedWrite)
  await abandonedStart.getByRole('button', { name: '启动执行', exact: true }).click()
  await abandonedStart.waitFor({ state: 'hidden' })
  await concurrentPage.waitForLoadState('networkidle')
  concurrentPage.off('request', countAbandonedWrite)
  assert.equal(abandonedWrites, 0, '已离开页面的旧确认不得发起写请求')
  assert.equal((await call(owner.context, 'GET', untouchedPath)).status, 'APPROVED', '旧确认框不能启动切回后的另一个活动')
  assert.equal((await call(owner.context, 'GET', concurrentPath)).status, 'APPROVED', '已离开活动的旧确认也不应写入原活动')
  await concurrentPage.goto(`/org/${org.id}/activities/${concurrent.id}?tab=lifecycle`, { waitUntil: 'networkidle' })
  await lifecycleStart.click()
  const freshStart = concurrentPage.waitForResponse(response => response.url().endsWith('/start-execution'))
  await concurrentPage.getByRole('dialog', { name: '启动执行', exact: true }).getByRole('button', { name: '启动执行', exact: true }).click()
  assert.equal((await freshStart).status(), 200)
  concurrent = await call(owner.context, 'GET', concurrentPath)
  concurrent = await call(owner.context, 'POST', `${concurrentPath}/finish-execution`, { version: concurrent.version })
  await call(owner.context, 'PUT', `${concurrentPath}/summary`, { summary: '总结初稿', version: concurrent.version })
  await concurrentPage.goto(`/org/${org.id}/activities/${concurrent.id}?tab=lifecycle`, { waitUntil: 'networkidle' })
  const summary = concurrentPage.locator('.lifecycle-block').filter({ has: concurrentPage.getByRole('heading', { name: '总结', exact: true }) }).locator('textarea').first()
  await summary.fill('本地旧版本总结')
  concurrent = await call(owner.context, 'GET', concurrentPath)
  await call(owner.context, 'PUT', `${concurrentPath}/summary`, { summary: '另一负责人新总结', version: concurrent.version })
  await refreshConcurrent()
  await saveOrPrompt(concurrentPage.getByRole('button', { name: '保存总结', exact: true }), `${concurrentPath}/summary`, 'PUT')
  assert.equal((await call(owner.context, 'GET', concurrentPath)).summary, '另一负责人新总结', '旧总结不能跟随详情刷新覆盖新总结')
  await concurrentPage.setViewportSize({ width: 390, height: 844 })
  await concurrentPage.screenshot({ path: fileURLToPath(new URL('旧草稿保存确认-手机.png', output)), fullPage: true, animations: 'disabled' })
  assert.equal(await concurrentPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await concurrentPage.setViewportSize({ width: 1440, height: 1000 })
  await versionPrompt.getByRole('button', { name: '保留草稿', exact: true }).click()
  assert.equal(await summary.inputValue(), '本地旧版本总结')
  await concurrentPage.getByRole('button', { name: '保存总结', exact: true }).click()
  const acceptedSummary = concurrentPage.waitForResponse(response => response.url().endsWith('/summary') && response.request().method() === 'PUT')
  await versionPrompt.getByRole('button', { name: '继续保存', exact: true }).click()
  assert.equal((await acceptedSummary).status(), 200)
  await concurrentPage.getByRole('button', { name: '保存总结', exact: true }).locator('.el-icon.is-loading').waitFor({ state: 'hidden' })
  await concurrentPage.waitForLoadState('networkidle')
  concurrent = await call(owner.context, 'GET', concurrentPath)
  assert.equal(concurrent.summary, '本地旧版本总结')
  await call(owner.context, 'PUT', `${concurrentPath}/summary`, { summary: '未编辑时应同步的新总结', version: concurrent.version })
  await refreshConcurrent()
  await concurrentPage.waitForFunction(() => document.querySelector('textarea[placeholder^="目标、执行概况"]')?.value === '未编辑时应同步的新总结')
  assert.equal(await summary.inputValue(), '未编辑时应同步的新总结')
  await concurrentPage.screenshot({ path: fileURLToPath(new URL('并发草稿核对后.png', output)), fullPage: true, animations: 'disabled' })
  await concurrentPage.close()

  // API 仅准备撤权前的费用、票据与署名；随后用旧会话验证权限及历史保留。
  const subjectAccount = await call(subject.context, 'GET', '/api/auth/me')
  const signedRecord = await call(subject.context, 'POST', `${concurrentPath}/execution-records`, {
    category: 'PROGRESS', content: '成员移除后必须保留原署名的执行记录',
  }, { 'Idempotency-Key': `history-record-${runStamp}` })
  let retainedExpense = await call(subject.context, 'POST', `${concurrentPath}/expenses`, {
    purpose: '撤权后保留的历史费用', amount: '12.50', incurredOn: '2027-10-01', category: 'MATERIAL',
    handledNote: '独立验收费用署名记录',
  }, { 'Idempotency-Key': `history-expense-${runStamp}` })
  const retainedVoucher = await prepareAttachment(subject.context, `${concurrentPath}/expenses/${retainedExpense.expenseId}/attachments`,
    '撤权费用凭证.png', `history-voucher-${runStamp}`)
  retainedExpense = await call(subject.context, 'POST', `${concurrentPath}/expenses/${retainedExpense.expenseId}/submit`, { version: retainedExpense.version })
  retainedExpense = await call(owner.context, 'POST', `${concurrentPath}/expenses/${retainedExpense.expenseId}/confirm`, {
    version: retainedExpense.version, overBudgetNote: '独立验收中确认保留历史费用',
  })
  assert.equal(retainedExpense.submittedBy, subjectAccount.id)
  assert.equal(signedRecord.recordedBy, subjectAccount.id)
  assert.equal(signedRecord.recordedByUsername, subject.username)
  const voucherDownload = `${concurrentPath}/attachments/${retainedVoucher.attachmentId}/download`
  assert.equal((await subject.context.request.get(voucherDownload)).status(), 200)

  await detail.goto(`/org/${org.id}/activities/${draft.id}?tab=proposal`, { waitUntil: 'networkidle' })
  await detail.locator('.proposal-section textarea').fill('撤权前未保存方案')
  await settings.goto(`/org/${org.id}/settings`, { waitUntil: 'networkidle' })
  await settings.locator('input[data-ai-field="model"], [data-ai-field="model"] input').fill('撤权前未保存模型')
  await call(owner.context, 'POST', `${path}/members/${subjectMember}/revoke-owner`)
  assert.equal((await call(subject.context, 'GET', '/api/auth/me')).username, subject.username)
  assert.equal((await call(subject.context, 'GET', path)).role, 'MEMBER')
  assert.equal((await subject.context.request.get(`${path}/activities/${draft.id}`)).status(), 404)
  assert.equal((await call(subject.context, 'GET', `${path}/ai/config`)).viewerRole, 'MEMBER')
  await expectStatus(subject.context, 'GET', `${concurrentPath}/expenses`, 404)
  await expectStatus(subject.context, 'GET', `${concurrentPath}/expenses/${retainedExpense.expenseId}/attachments`, 404)
  await expectStatus(subject.context, 'GET', voucherDownload, 404)
  await expectStatus(subject.context, 'POST', `${path}/members/${ownerMember}/promote-owner`, 403)

  for (const page of [detail, settings]) {
    await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
  }
  await settings.locator('[data-ai-role="member"]').waitFor()
  await detail.getByText('活动不存在或无权访问。', { exact: true }).waitFor()
  assert.equal(await settings.locator('[data-ai-field="model"]').count(), 0)
  assert.equal(await detail.locator('.proposal-section textarea').count(), 0)
  assert.equal(await detail.getByRole('button', { name: '删除草稿', exact: true }).count(), 0)
  assert.equal((await settings.locator('.u-meta span').last().innerText()).trim(), '成员')
  assert.equal(new URL(settings.url()).pathname, `/org/${org.id}/settings`)
  await settings.screenshot({ path: fileURLToPath(new URL('撤销负责人后.png', output)), fullPage: true, animations: 'disabled' })

  // 当前身份未变时刷新与网络失败保留草稿，不能把普通切回变成丢稿。
  await settings.goto(`/org/${other.id}/settings`, { waitUntil: 'networkidle' })
  const model = settings.locator('input[data-ai-field="model"], [data-ai-field="model"] input')
  await model.fill('身份不变保留草稿')
  const checked = settings.waitForResponse(response => new URL(response.url()).pathname === `/api/organizations/${other.id}`)
  await settings.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
  assert.equal((await checked).status(), 200)
  await settings.waitForLoadState('networkidle')
  assert.equal(await model.inputValue(), '身份不变保留草稿')
  await settings.route(`**/api/organizations/${other.id}`, route => route.abort('failed'), { times: 1 })
  const failed = settings.waitForEvent('requestfailed', { predicate: request => new URL(request.url()).pathname === `/api/organizations/${other.id}` })
  await settings.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
  await failed
  await settings.getByRole('alert').filter({ hasText: '组织权限核对失败' }).waitFor()
  assert.equal(await model.inputValue(), '身份不变保留草稿')
  await settings.setViewportSize({ width: 390, height: 844 })
  assert.equal(await settings.getByRole('button', { name: '重新核对权限', exact: true }).isVisible(), true)
  await settings.screenshot({ path: fileURLToPath(new URL('窄屏权限重试.png', output)), fullPage: true, animations: 'disabled' })
  assert.equal(await settings.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await settings.getByRole('button', { name: '重新核对权限', exact: true }).click()
  await settings.waitForLoadState('networkidle')
  assert.equal(await settings.getByRole('alert').filter({ hasText: '组织权限核对失败' }).count(), 0)
  assert.equal(await model.inputValue(), '身份不变保留草稿')
  await settings.setViewportSize({ width: 1440, height: 1000 })

  // 迟到的 OWNER 响应由真实后端生成并延后送达，不能覆盖后续 MEMBER 核对。
  await call(owner.context, 'POST', `${path}/members/${subjectMember}/promote-owner`)
  await detail.goto(`/org/${org.id}/activities`, { waitUntil: 'networkidle' })
  await detail.getByRole('button', { name: '创建活动', exact: true }).waitFor()
  let captured
  const oldCaptured = new Promise(resolve => { captured = resolve })
  const oldGate = new Promise(resolve => { releaseOld = resolve })
  let oldReturned
  const oldDone = new Promise(resolve => { oldReturned = resolve })
  let first = true
  await detail.route(`**/api/organizations/${org.id}`, async route => {
    if (!first) return route.continue()
    first = false
    const response = await route.fetch()
    assert.equal((await response.json()).role, 'OWNER')
    captured()
    await oldGate
    await route.fulfill({ response })
    oldReturned()
  })
  await detail.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
  await oldCaptured
  await call(owner.context, 'POST', `${path}/members/${subjectMember}/revoke-owner`)
  const latest = detail.waitForResponse(response => new URL(response.url()).pathname === path)
  await detail.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
  assert.equal((await latest).status(), 200)
  await detail.getByRole('button', { name: '创建活动', exact: true }).waitFor({ state: 'hidden' })
  releaseOld()
  await oldDone
  await detail.waitForLoadState('networkidle')
  assert.equal(await detail.getByRole('button', { name: '创建活动', exact: true }).count(), 0)
  assert.equal((await detail.locator('.u-meta span').last().innerText()).trim(), '成员')

  // 活动职责改派不会改变组织 MEMBER 角色；草稿失去可见性时也须关闭已有编辑弹窗。
  let managed = await call(owner.context, 'POST', `${path}/activities`, {
    name: '活动职责改派核对', description: '核对活动职责改派后的旧权限', ownerMemberId: subjectMember, registrationScope: 'INTERNAL',
    location: '线上', startTime: '2027-10-01T14:00:00+08:00', endTime: '2027-10-01T16:00:00+08:00',
  }, { 'Idempotency-Key': `duties-${stamp}` })
  const managedPath = `${path}/activities/${managed.id}`
  await detail.goto(`/org/${org.id}/activities/${managed.id}?tab=proposal`, { waitUntil: 'networkidle' })
  await detail.locator('.proposal-section textarea').fill('职责改派前未保存方案')
  await detail.getByRole('button', { name: '编辑信息', exact: true }).click()
  const edit = detail.getByRole('dialog', { name: '编辑活动信息', exact: true })
  await edit.locator('input').first().fill('职责改派前未保存主题')
  managed = await call(owner.context, 'PATCH', managedPath, { ownerMemberId: ownerMember, version: managed.version })
  assert.equal((await call(subject.context, 'GET', path)).role, 'MEMBER')
  assert.equal((await subject.context.request.get(managedPath)).status(), 404)
  await detail.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
  await detail.getByText('活动不存在或无权访问。', { exact: true }).waitFor()
  await edit.waitFor({ state: 'hidden' })
  assert.equal(await detail.locator('.proposal-section textarea').count(), 0)
  await detail.screenshot({ path: fileURLToPath(new URL('草稿职责改派后.png', output)), fullPage: true, animations: 'disabled' })
  await detail.getByRole('link', { name: '活动管理', exact: true }).click()
  await detail.waitForURL(url => url.pathname === `/org/${org.id}/activities`)
  assert.equal(await detail.locator('.el-message-box:visible').count(), 0)

  // 已发布活动仍可读；负责人改派后清除已打开的任务管理表单，不误伤未撤权时的草稿。
  managed = await call(owner.context, 'PATCH', managedPath, { ownerMemberId: subjectMember, version: managed.version })
  await call(owner.context, 'PUT', `${managedPath}/proposal`, { content: '职责改派方案', version: managed.version })
  await call(owner.context, 'POST', `${managedPath}/submit`)
  managed = await call(owner.context, 'GET', managedPath)
  managed = await call(owner.context, 'POST', `${managedPath}/approve`, { version: managed.version })
  // MEMBER 作为本活动负责人可以管理本活动，不能据此管理同组织另一已发布活动。
  assert.equal((await call(subject.context, 'GET', managedPath)).ownerMemberId, subjectMember)
  assert.equal((await call(subject.context, 'GET', untouchedPath)).status, 'APPROVED')
  await expectStatus(subject.context, 'POST', `${untouchedPath}/tasks`, 403, {
    title: '越界任务应被拒绝', assigneeMemberId: subjectMember, priority: 'MEDIUM',
  })
  await expectStatus(subject.context, 'POST', `${untouchedPath}/materials`, 403, {
    name: '越界物资应被拒绝', quantity: 1, unit: '个', assigneeMemberId: subjectMember,
  })
  await expectStatus(subject.context, 'POST', `${untouchedPath}/start-execution`, 403, { version: untouched.version })
  await detail.goto(`/org/${org.id}/activities/${managed.id}?tab=tasks`, { waitUntil: 'networkidle' })
  await detail.getByRole('button', { name: '新建任务', exact: true }).click()
  const taskDialog = detail.getByRole('dialog', { name: '新建任务', exact: true })
  await taskDialog.locator('input').first().fill('权限不变保留的任务草稿')
  const checkedActivity = detail.waitForResponse(response => new URL(response.url()).pathname === managedPath)
  await detail.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
  assert.equal((await checkedActivity).status(), 200)
  await detail.waitForLoadState('networkidle')
  assert.equal(await taskDialog.locator('input').first().inputValue(), '权限不变保留的任务草稿')
  await detail.route(`**${managedPath}`, route => route.abort('failed'), { times: 1 })
  await detail.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
  await detail.getByRole('alert').filter({ hasText: '活动权限核对失败' }).waitFor()
  assert.equal(await taskDialog.locator('input').first().inputValue(), '权限不变保留的任务草稿')
  await taskDialog.getByRole('button', { name: '取消', exact: true }).click()
  await detail.locator('.el-message-box').getByRole('button', { name: '继续填写' }).click()
  const retriedActivity = detail.waitForResponse(response => new URL(response.url()).pathname === managedPath)
  await detail.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
  assert.equal((await retriedActivity).status(), 200)
  await detail.waitForLoadState('networkidle')
  assert.equal(await taskDialog.locator('input').first().inputValue(), '权限不变保留的任务草稿')
  assert.equal(await detail.getByRole('alert').filter({ hasText: '活动权限核对失败' }).count(), 0)
  let activityCaptured
  const activityWasCaptured = new Promise(resolve => { activityCaptured = resolve })
  const activityGate = new Promise(resolve => { releaseOld = resolve })
  let activityReturned
  const activityOldDone = new Promise(resolve => { activityReturned = resolve })
  let firstActivity = true
  await detail.route(`**${managedPath}`, async route => {
    if (!firstActivity) return route.continue()
    firstActivity = false
    const response = await route.fetch()
    assert.equal((await response.json()).ownerMemberId, subjectMember)
    activityCaptured()
    await activityGate
    await route.fulfill({ response })
    activityReturned()
  })
  await detail.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
  await activityWasCaptured
  managed = await call(owner.context, 'POST', `${managedPath}/withdraw-approval`, { version: managed.version, reason: '职责改派' })
  managed = await call(owner.context, 'PATCH', managedPath, { ownerMemberId: ownerMember, version: managed.version })
  await call(owner.context, 'POST', `${managedPath}/submit`)
  managed = await call(owner.context, 'GET', managedPath)
  await call(owner.context, 'POST', `${managedPath}/approve`, { version: managed.version })
  assert.equal((await call(subject.context, 'GET', managedPath)).ownerMemberId, ownerMember)
  await detail.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
  await taskDialog.waitFor({ state: 'hidden' })
  await detail.getByRole('button', { name: '新建任务', exact: true }).waitFor({ state: 'hidden' })
  releaseOld()
  await activityOldDone
  await detail.waitForLoadState('networkidle')
  assert.equal(await detail.getByRole('button', { name: '新建任务', exact: true }).count(), 0)
  assert.equal((await detail.locator('.u-meta span').last().innerText()).trim(), '成员')
  assert.equal(new URL(detail.url()).pathname, `/org/${org.id}/activities/${managed.id}`)
  await detail.screenshot({ path: fileURLToPath(new URL('已发布职责改派后.png', output)), fullPage: true, animations: 'disabled' })

  // 任务、物资责任改派不改变活动负责人；详情列表与“我的任务”也须去掉旧执行入口。
  const assignedTask = await call(owner.context, 'POST', `${managedPath}/tasks`, {
    title: '责任改派任务', assigneeMemberId: subjectMember, priority: 'MEDIUM',
  }, { 'Idempotency-Key': `assigned-task-${stamp}` })
  const assignedMaterial = await call(owner.context, 'POST', `${managedPath}/materials`, {
    name: '责任改派物资', quantity: 1, unit: '个', assigneeMemberId: subjectMember,
  }, { 'Idempotency-Key': `assigned-material-${stamp}` })
  phase = '普通资料准备与页面交接'
  console.log(JSON.stringify({ phase }))
  const activityDoc = await prepareAttachment(owner.context, `${managedPath}/attachments`, '撤权与归档活动资料.png', `history-activity-doc-${runStamp}`)
  const taskDoc = await prepareAttachment(subject.context, `${managedPath}/tasks/${assignedTask.taskId}/attachments`,
    '撤权与归档任务资料.png', `history-task-doc-${runStamp}`)
  const oldDownloads = [activityDoc, taskDoc].map(item => `${managedPath}/attachments/${item.attachmentId}/download`)
  for (const url of oldDownloads) {
    const response = await subject.context.request.get(url)
    assert.equal(response.status(), 200)
    assert.deepEqual(await response.body(), pngBytes)
  }
  await detail.reload({ waitUntil: 'networkidle' })
  const taskRow = detail.getByRole('row').filter({ hasText: '责任改派任务' })
  const materialRow = detail.getByRole('row').filter({ hasText: '责任改派物资' })
  await taskRow.getByRole('button', { name: '开始处理', exact: true }).waitFor()
  await materialRow.getByText(subject.username, { exact: true }).waitFor()
  await taskRow.getByRole('button', { name: '资料', exact: true }).click()
  const attachments = detail.getByRole('dialog', { name: '任务资料：责任改派任务', exact: true })
  await attachments.getByRole('button', { name: '上传资料', exact: true }).waitFor()
  const mine = await subject.context.newPage()
  mine.setDefaultTimeout(6000)
  mine.on('pageerror', error => errors.push(error.message))
  await mine.goto(`/org/${org.id}/my-tasks`, { waitUntil: 'networkidle' })
  const myTaskRow = mine.getByRole('row').filter({ hasText: '责任改派任务' })
  await myTaskRow.waitFor()
  const ownerWork = await owner.context.newPage()
  ownerWork.setDefaultTimeout(6000)
  ownerWork.on('pageerror', error => errors.push(error.message))
  await ownerWork.goto(`/org/${org.id}/members`, { waitUntil: 'networkidle' })
  await ownerWork.getByRole('row').filter({ hasText: subject.username }).getByRole('button', { name: '移除', exact: true }).click()
  const blockedRemoval = await uiWrite(ownerWork, ownerWork.getByRole('dialog', { name: '移除成员', exact: true })
    .getByRole('button', { name: '移除成员', exact: true }), 'DELETE', `${path}/members/${subjectMember}`, 409)
  assert.equal(blockedRemoval.code, 'MEMBER_HAS_OPEN_DUTIES')
  console.log(JSON.stringify({ phase: '未结束职责的页面移除已拒绝' }))
  assert.ok((await call(owner.context, 'GET', `${path}/members`)).items.some(item => item.memberId === subjectMember))
  await ownerWork.goto(`/org/${org.id}/activities/${managed.id}?tab=tasks`, { waitUntil: 'networkidle' })
  const ownerTaskRow = ownerWork.getByRole('row').filter({ hasText: '责任改派任务' })
  await ownerTaskRow.getByRole('button', { name: '编辑/改派', exact: true }).click()
  const handoffTask = ownerWork.getByRole('dialog', { name: '编辑任务', exact: true })
  await selectMember(ownerWork, handoffTask, '任务负责人', owner.username)
  await uiWrite(ownerWork, handoffTask.getByRole('button', { name: '保存', exact: true }), 'POST', `${managedPath}/tasks/${assignedTask.taskId}/update`)
  console.log(JSON.stringify({ phase: '页面任务改派已完成' }))
  const ownerMaterialRow = ownerWork.getByRole('row').filter({ hasText: '责任改派物资' })
  phase = '打开物资编辑表单'
  console.log(JSON.stringify({ phase }))
  await ownerMaterialRow.getByRole('button', { name: '编辑', exact: true }).click()
  const handoffMaterial = ownerWork.getByRole('dialog', { name: '编辑物资', exact: true })
  phase = '选择物资接手成员'
  console.log(JSON.stringify({ phase }))
  await selectMember(ownerWork, handoffMaterial, '负责人', owner.username)
  phase = '保存物资改派'
  console.log(JSON.stringify({ phase }))
  await uiWrite(ownerWork, handoffMaterial.getByRole('button', { name: '保存', exact: true }), 'POST', `${managedPath}/materials/${assignedMaterial.materialId}/update`)
  console.log(JSON.stringify({ phase: '页面物资改派已完成' }))
  assert.equal((await call(owner.context, 'GET', `${managedPath}/tasks`)).items.find(item => item.taskId === assignedTask.taskId).assigneeMemberId, ownerMember)
  assert.equal((await call(owner.context, 'GET', `${managedPath}/materials`)).items.find(item => item.materialId === assignedMaterial.materialId).assigneeMemberId, ownerMember)
  await ownerWork.screenshot({ path: fileURLToPath(new URL('页面任务物资接手后.png', output)), fullPage: true, animations: 'disabled' })
  const csrf = await call(subject.context, 'GET', '/api/auth/csrf')
  assert.equal((await subject.context.request.post(`${managedPath}/tasks/${assignedTask.taskId}/advance`, {
    data: { toStatus: 'IN_PROGRESS' }, headers: { [csrf.headerName]: csrf.token },
  })).status(), 403)
  const myTasksChecked = mine.waitForResponse(response => new URL(response.url()).pathname === `${path}/my-tasks`)
  for (const page of [detail, mine]) await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
  assert.equal((await myTasksChecked).status(), 200)
  await taskRow.getByRole('button', { name: '开始处理', exact: true }).waitFor({ state: 'hidden' })
  await materialRow.getByText(subject.username, { exact: true }).waitFor({ state: 'hidden' })
  await materialRow.getByText(owner.username, { exact: true }).waitFor()
  await attachments.getByRole('button', { name: '上传资料', exact: true }).waitFor({ state: 'hidden' })
  await myTaskRow.waitFor({ state: 'hidden' })
  await attachments.locator('.el-dialog__headerbtn').click()
  await mine.close()
  await detail.screenshot({ path: fileURLToPath(new URL('任务物资责任改派后.png', output)), fullPage: true, animations: 'disabled' })

  // 普通成员在正式页面结束本人任务；物资由管理者标记就绪，再由本人页面退出。
  phase = '页面合法结束职责及本人退出'
  console.log(JSON.stringify({ phase }))
  const leaveInvite = await call(owner.context, 'POST', `${path}/invitations`, {})
  await call(leaver.context, 'POST', '/api/invitations/accept', { code: leaveInvite.code })
  const leaveMember = (await call(owner.context, 'GET', `${path}/members`)).items.find(item => item.username === leaver.username).memberId
  const leaveTask = await call(owner.context, 'POST', `${managedPath}/tasks`, {
    title: '合法完成后退出任务', assigneeMemberId: leaveMember, priority: 'MEDIUM',
  }, { 'Idempotency-Key': `leave-task-${runStamp}` })
  const leaveMaterial = await call(owner.context, 'POST', `${managedPath}/materials`, {
    name: '合法就绪后退出物资', quantity: 1, unit: '个', assigneeMemberId: leaveMember,
  }, { 'Idempotency-Key': `leave-material-${runStamp}` })
  const leavePage = await leaver.context.newPage()
  leavePage.setDefaultTimeout(6000)
  leavePage.on('pageerror', error => errors.push(error.message))
  async function leaveInPage(expected) {
    await leavePage.goto(`/org/${org.id}/members`, { waitUntil: 'networkidle' })
    await leavePage.getByRole('button', { name: '退出组织', exact: true }).click()
    return uiWrite(leavePage, leavePage.getByRole('dialog', { name: '退出组织', exact: true }).getByRole('button', { name: '退出', exact: true }),
      'DELETE', `${path}/members/me`, expected)
  }
  assert.equal((await leaveInPage(409)).code, 'MEMBER_HAS_OPEN_DUTIES')
  await leavePage.goto(`/org/${org.id}/activities/${managed.id}?tab=tasks`, { waitUntil: 'networkidle' })
  await uiWrite(leavePage, leavePage.getByRole('row').filter({ hasText: leaveTask.title }).getByRole('button', { name: '标记完成', exact: true }),
    'POST', `${managedPath}/tasks/${leaveTask.taskId}/advance`)
  assert.equal((await leaveInPage(409)).code, 'MEMBER_HAS_OPEN_DUTIES', '完成任务后待准备物资仍阻断退出')
  await ownerWork.reload({ waitUntil: 'networkidle' })
  const readyRow = ownerWork.getByRole('row').filter({ hasText: leaveMaterial.name })
  await uiWrite(ownerWork, readyRow.getByRole('button', { name: '开始准备', exact: true }), 'POST', `${managedPath}/materials/${leaveMaterial.materialId}/transition`)
  await uiWrite(ownerWork, readyRow.getByRole('button', { name: '标记就绪', exact: true }), 'POST', `${managedPath}/materials/${leaveMaterial.materialId}/transition`)
  await ownerWork.screenshot({ path: fileURLToPath(new URL('页面合法结束职责后.png', output)), fullPage: true, animations: 'disabled' })
  await leaveInPage(204)
  await leavePage.waitForURL(url => url.pathname === '/')
  assert.equal((await leaver.context.request.get(managedPath)).status(), 404)
  assert.equal((await call(owner.context, 'GET', `${managedPath}/tasks`)).items.find(item => item.taskId === leaveTask.taskId).status, 'COMPLETED')
  assert.equal((await call(owner.context, 'GET', `${managedPath}/materials`)).items.find(item => item.materialId === leaveMaterial.materialId).status, 'READY')
  await leavePage.screenshot({ path: fileURLToPath(new URL('页面合法退出后.png', output)), fullPage: true, animations: 'disabled' })
  await leavePage.close()

  // 保留一份属于被移除成员的已完成任务与反馈，不能用删除历史来满足退出条件。
  phase = '页面成员移除及历史保留'
  console.log(JSON.stringify({ phase }))
  const historyTask = await call(owner.context, 'POST', `${managedPath}/tasks`, {
    title: '移除后保留原承担者的任务', assigneeMemberId: subjectMember, priority: 'MEDIUM',
  }, { 'Idempotency-Key': `history-task-${runStamp}` })
  await detail.reload({ waitUntil: 'networkidle' })
  await uiWrite(detail, detail.getByRole('row').filter({ hasText: historyTask.title }).getByRole('button', { name: '标记完成', exact: true }),
    'POST', `${managedPath}/tasks/${historyTask.taskId}/advance`)
  await ownerWork.reload({ waitUntil: 'networkidle' })
  await ownerTaskRow.getByRole('button', { name: '取消', exact: true }).click()
  await uiWrite(ownerWork, ownerWork.getByRole('dialog', { name: '取消任务', exact: true }).getByRole('button', { name: '取消任务', exact: true }),
    'POST', `${managedPath}/tasks/${assignedTask.taskId}/transition`)
  await uiWrite(ownerWork, ownerMaterialRow.getByRole('button', { name: '开始准备', exact: true }), 'POST', `${managedPath}/materials/${assignedMaterial.materialId}/transition`)
  await uiWrite(ownerWork, ownerMaterialRow.getByRole('button', { name: '标记就绪', exact: true }), 'POST', `${managedPath}/materials/${assignedMaterial.materialId}/transition`)
  managed = await call(owner.context, 'GET', managedPath)
  managed = await call(owner.context, 'POST', `${managedPath}/start-execution`, { version: managed.version })
  managed = await call(owner.context, 'POST', `${managedPath}/finish-execution`, { version: managed.version })
  const retainedFeedback = await call(subject.context, 'POST', `${managedPath}/feedback`, { rating: 5, comment: '成员移除后保留的原作者反馈' })
  assert.equal(retainedFeedback.accountId, subjectAccount.id)
  const oldNotifications = (await call(subject.context, 'GET', '/api/me/notifications?pageSize=100')).items.filter(item => item.activityId === managed.id)
  assert.ok(oldNotifications.length > 0, '撤权前必须确有对应活动通知，避免空集合伪验收')

  // 被移除后组织 404 清理子页与目录，同时保留另一个组织和有效账号会话。
  await ownerWork.goto(`/org/${org.id}/members`, { waitUntil: 'networkidle' })
  await ownerWork.getByRole('row').filter({ hasText: subject.username }).getByRole('button', { name: '移除', exact: true }).click()
  await uiWrite(ownerWork, ownerWork.getByRole('dialog', { name: '移除成员', exact: true }).getByRole('button', { name: '移除成员', exact: true }),
    'DELETE', `${path}/members/${subjectMember}`, 204)
  for (const url of oldDownloads) await expectStatus(subject.context, 'GET', url, 404)
  const afterNotifications = (await call(subject.context, 'GET', '/api/me/notifications?pageSize=100')).items
  assert.equal(afterNotifications.some(item => item.activityId === managed.id || oldNotifications.some(old => old.notificationId === item.notificationId)), false)
  assert.deepEqual((await call(owner.context, 'GET', `${concurrentPath}/expenses`)).items.find(item => item.expenseId === retainedExpense.expenseId), retainedExpense)
  assert.deepEqual((await call(owner.context, 'GET', `${concurrentPath}/execution-records`)).items.find(item => item.recordId === signedRecord.recordId), signedRecord)
  assert.deepEqual((await call(owner.context, 'GET', `${managedPath}/feedback`)).items.find(item => item.feedbackId === retainedFeedback.feedbackId), retainedFeedback)
  const preservedTask = (await call(owner.context, 'GET', `${managedPath}/tasks`)).items.find(item => item.taskId === historyTask.taskId)
  assert.equal(preservedTask.assigneeMemberId, subjectMember)
  assert.equal(preservedTask.assigneeUsername, subject.username)
  assert.equal(preservedTask.status, 'COMPLETED')
  const directory = detail.waitForResponse(response => new URL(response.url()).pathname === '/api/me/organizations')
  await detail.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
  await detail.getByText('组织不存在或无权访问。', { exact: true }).waitFor()
  const remaining = (await (await directory).json()).items
  assert.equal(remaining.some(item => item.id === org.id), false)
  assert.equal(remaining.find(item => item.id === other.id).role, 'OWNER')
  assert.equal(await detail.getByRole('heading', { name: '活动管理', exact: true }).count(), 0)
  assert.equal((await call(subject.context, 'GET', '/api/auth/me')).username, subject.username)
  assert.equal(new URL(detail.url()).pathname, `/org/${org.id}/activities/${managed.id}`)
  await detail.waitForLoadState('networkidle')
  assert.equal((await detail.locator('.organization-select').innerText()).includes(org.name), false)
  await detail.locator('.organization-select').click()
  const options = detail.locator('.organization-popper .el-select-dropdown__item')
  assert.equal(await options.filter({ hasText: org.name }).count(), 0)
  assert.equal(await options.filter({ hasText: other.name }).count(), 1)
  await detail.keyboard.press('Escape')
  await detail.screenshot({ path: fileURLToPath(new URL('移除成员后.png', output)), fullPage: true, animations: 'disabled' })

  // 生命周期前置通过 API 准备；归档后的资料限制与授权下载在正式页面实际核对。
  phase = '归档资料页面下载与删除限制'
  console.log(JSON.stringify({ phase }))
  managed = await call(owner.context, 'GET', managedPath)
  managed = await call(owner.context, 'PUT', `${managedPath}/summary`, { summary: '已完成合法职责交接及退出，保留普通资料用于归档核对', version: managed.version })
  managed = await call(owner.context, 'POST', `${managedPath}/summary/confirm`, { version: managed.version })
  managed = await call(owner.context, 'POST', `${managedPath}/archive`, { version: managed.version })
  assert.equal(managed.status, 'ARCHIVED')
  await ownerWork.goto(`/org/${org.id}/activities/${managed.id}?tab=tasks`, { waitUntil: 'networkidle' })
  await ownerWork.getByRole('row').filter({ hasText: historyTask.title }).getByText(subject.username, { exact: true }).waitFor()
  const archivedActivityRow = ownerWork.getByRole('row').filter({ hasText: activityDoc.originalName })
  const archivedActivityDeleteDisabled = await archivedActivityRow.getByRole('button', { name: '删除', exact: true }).isDisabled()
  async function downloadFromPage(row, attachment, evidenceName) {
    const downloading = ownerWork.waitForEvent('download')
    await row.getByRole('button', { name: '下载', exact: true }).click()
    const download = await downloading
    const expectedPath = `${managedPath}/attachments/${attachment.attachmentId}/download`
    assert.equal(new URL(download.url()).pathname, expectedPath)
    assert.equal(download.suggestedFilename(), attachment.originalName)
    const saved = fileURLToPath(new URL(evidenceName, output))
    await download.saveAs(saved)
    assert.deepEqual(await readFile(saved), pngBytes, '授权页面下载必须保留原文件内容')
    // Chrome 的 <a download> 不保证触发 page response 事件，另读同受控端点核对状态。
    const response = await owner.context.request.get(expectedPath)
    assert.equal(response.status(), 200)
    assert.deepEqual(await response.body(), pngBytes)
  }
  await downloadFromPage(archivedActivityRow, activityDoc, '归档活动资料下载.png')
  await ownerWork.screenshot({ path: fileURLToPath(new URL('归档活动资料禁用删除.png', output)), fullPage: true, animations: 'disabled' })
  await ownerWork.getByRole('row').filter({ hasText: assignedTask.title }).getByRole('button', { name: '资料', exact: true }).click()
  const archivedTaskDialog = ownerWork.getByRole('dialog', { name: `任务资料：${assignedTask.title}`, exact: true })
  const archivedTaskRow = archivedTaskDialog.getByRole('row').filter({ hasText: taskDoc.originalName })
  const archivedTaskDeleteDisabled = await archivedTaskRow.getByRole('button', { name: '删除', exact: true }).isDisabled()
  await downloadFromPage(archivedTaskRow, taskDoc, '归档任务资料下载.png')
  await ownerWork.screenshot({ path: fileURLToPath(new URL('归档任务资料禁用删除.png', output)), fullPage: true, animations: 'disabled' })
  for (const item of [activityDoc, taskDoc]) {
    const rejected = await expectStatus(owner.context, 'DELETE', `${managedPath}/attachments/${item.attachmentId}`, 409)
    assert.equal((await rejected.json()).code, 'ACTIVITY_STATE_CONFLICT')
  }
  await archivedTaskDialog.locator('.el-dialog__headerbtn').click()
  await ownerWork.close()
  assert.deepEqual(errors, [])
  const result = { database: 'activity_test', orgId: org.id, preservedOrgId: other.id,
    roleChangeClearsDraftAndConfig: true, sameRoleKeepsDraft: true, networkFailureKeepsDraft: true,
    staleOwnerIgnored: true, removalClearsPageAndDirectory: true, sessionStillValid: true, mobileRetryFits: true,
    activityDutiesRefresh: true, activityNetworkFailureKeepsDraft: true, staleActivityOwnerIgnored: true,
    taskMaterialDutiesRefresh: true, taskAttachmentUploadPermissionRefreshed: true, errors,
    staleDraftRequiresConfirmation: true, changesDuringConfirmationRejected: true,
    staleRejectionRejected: true, cleanSummaryRefreshed: true,
    creationUnknownRecheckedOnce: true, creationMobileFits: true, staleLifecycleConfirmationRejected: true,
    abandonedConfirmationCannotRetarget: true,
    invitationUnknownRecheckedOnce: true, lostInvitationReplacedAfterRevocation: true,
    invitationPendingProtected: true, invitationMobileFits: true,
    abandonedMemberConfirmationRejected: true,
    activityOwnerCannotManageOtherActivity: true, demotedOwnerExpenseAndVoucherDenied: true,
    demotedOwnerMemberManagementDenied: true, pageTaskMaterialHandoffBeforeRemoval: true,
    pageTaskCompletionStillBlockedByMaterial: true, pageMaterialReadyThenLeave: true, pageRemovalAfterHandoff: true,
    oldActivityAndTaskDownloadsDenied: true, oldNotificationsFilteredAfterRemoval: true,
    expenseFeedbackAndExecutionSignaturesPreserved: true, completedTaskAssigneePreserved: true,
    archivedDeleteButtonsDisabled: archivedActivityDeleteDisabled && archivedTaskDeleteDisabled,
    archivedActivityDeleteDisabled, archivedTaskDeleteDisabled,
    archivedActivityAndTaskPageDownloadsPreserveBytes: true,
    archivedActivityAndTaskApiDeleteRejected: true,
    evidenceBoundary: '账号、邀请、资料、费用、反馈与生命周期前置由 API 准备；任务/物资改派、任务完成、物资就绪、成员退出/移除及归档下载由正式页面操作；不据此声称审批与归档全流程均由页面完成。',
    scope: '真实 activity_test 后端；visibilitychange、网络中断及 OWNER 响应延后由脚本控制；无模型调用；归档删除按实际按钮状态记录' }
  await writeFile(new URL('结果.json', output), JSON.stringify(result, null, 2))
  await writeFile(new URL('集中验收-结果.json', output), JSON.stringify(result, null, 2))
  console.log(JSON.stringify(result))
  assert.equal(result.archivedDeleteButtonsDisabled, true, '归档普通活动与任务资料必须没有可操作的删除入口')
} catch (error) {
  console.error(JSON.stringify({ failedPhase: phase, name: error.name, message: error.message }))
  throw error
} finally {
  releaseOld?.()
  await browser.close()
}
