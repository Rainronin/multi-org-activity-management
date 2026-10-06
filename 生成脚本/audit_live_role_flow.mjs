// 正式前端 + 真实测试后端：四会话走创建、审批、任务、手机报名与复盘。
// 账号及部分成员关系由 API 准备；加入结果丢失和目录延后由脚本控制，业务仍写真实测试库。
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('必须确认后端连接 activity_test')
const base = 'http://127.0.0.1:5174'
const out = new URL('../验证截图/浏览器联调/', import.meta.url)
await mkdir(out, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const errors = [], steps = [], pages = []
const expectedJoinFailures = new Set()
const expectedCreationFailures = new Set()
let expectedCreationPath = ''
let current
try {
  const stamp = process.env.FLOW_RUN_STAMP || Date.now(), password = `Test${stamp}Pass`
  console.log(JSON.stringify({ runStamp: stamp }))
  async function call(ctx, method, path, data) {
    const csrf = method === 'GET' ? null : await (await ctx.request.get('/api/auth/csrf')).json()
    const res = await ctx.request.fetch(path, { method, data, headers: csrf ? { [csrf.headerName]: csrf.token } : {} })
    assert.ok(res.ok(), `${method} ${path}: ${res.status()} ${await res.text()}`)
    return res.status() === 204 ? null : res.json()
  }
  async function actor(role, mobile = false) {
    const ctx = await browser.newContext({ baseURL: base, viewport: mobile ? { width: 390, height: 844 } : { width: 1440, height: 1000 } })
    const username = `flow_${role}_${stamp}`
    if (!process.env.FLOW_RUN_STAMP) await call(ctx, 'POST', '/api/auth/register', { username, password })
    const page = await ctx.newPage(); pages.push(page); current = page
    page.on('pageerror', e => errors.push(e.message))
    page.on('response', r => {
      if (r.status() === 503 && new URL(r.url()).pathname === '/api/invitations/accept' && expectedJoinFailures.delete(page)) return
      if (r.status() === 503 && new URL(r.url()).pathname === expectedCreationPath && expectedCreationFailures.delete(page)) return
      if (r.status() >= 500) errors.push(`${r.status()} ${new URL(r.url()).pathname}`)
    })
    await page.goto('/login')
    await page.getByPlaceholder('注册时设置的用户名').fill(username)
    await page.getByPlaceholder('请输入密码').fill(password)
    await page.getByRole('button', { name: '进入工作区', exact: true }).click()
    await page.waitForURL(url => !url.pathname.includes('login'))
    return { ctx, page, username }
  }
  const owner = await actor('owner'), organizer = await actor('org'), executor = await actor('exec'), participant = await actor('join', true)
  const org = await call(owner.ctx, 'POST', '/api/organizations', { name: `浏览器团建-${stamp}` })
  const op = `/api/organizations/${org.id}`
  for (const a of [organizer, executor]) {
    const invitation = await call(owner.ctx, 'POST', `${op}/invitations`, {})
    await call(a.ctx, 'POST', '/api/invitations/accept', { code: invitation.code })
  }
  // 手机参加者走正式邀请码弹窗，后端接受后仅把页面响应替换为 503。
  const joinPage = participant.page; current = joinPage
  const invitation = await call(owner.ctx, 'POST', `${op}/invitations`, { maxUses: 1 })
  await joinPage.goto('/public/activities', { waitUntil: 'networkidle' })
  await joinPage.getByRole('button', { name: '账号菜单', exact: true }).click()
  await joinPage.getByText('返回我的工作区', { exact: true }).click()
  await joinPage.waitForLoadState('networkidle')
  await joinPage.getByRole('button', { name: '加入组织', exact: true }).click()
  const joinDialog = joinPage.getByRole('dialog', { name: '使用邀请码加入组织', exact: true })
  await joinDialog.getByLabel('邀请码', { exact: true }).fill(invitation.code)
  let releaseJoin, observedJoin
  const joinGate = new Promise(resolve => { releaseJoin = resolve })
  const joinStarted = new Promise(resolve => { observedJoin = resolve })
  await joinPage.route('**/api/invitations/accept', async route => {
    const response = await route.fetch()
    assert.equal(response.status(), 200)
    observedJoin()
    await joinGate
    expectedJoinFailures.add(joinPage)
    await route.fulfill({ status: 503, json: { status: 503, code: 'JOIN_RESPONSE_LOST', detail: '加入响应未收到，请核对结果。' } })
  }, { times: 1 })
  const lostResponse = joinPage.waitForResponse(response => response.url().endsWith('/api/invitations/accept'))
  await joinDialog.getByRole('button', { name: '加入组织', exact: true }).click()
  await joinStarted
  const sendingInputDisabled = await joinDialog.locator('input').isDisabled()
  await joinDialog.getByRole('button', { name: '取消', exact: true }).click({ force: true })
  const sendingClosePrevented = await joinDialog.waitFor({ state: 'hidden', timeout: 500 }).then(() => false, () => true)
  releaseJoin()
  assert.equal((await lostResponse).status(), 503)
  console.log(JSON.stringify({ joinSending: { inputDisabled: sendingInputDisabled, closePrevented: sendingClosePrevented } }))
  assert.ok(sendingInputDisabled && sendingClosePrevented, '邀请码发送中应固定输入并阻止关闭，不丢失原请求')
  await joinDialog.getByRole('alert').waitFor()
  assert.equal(await joinDialog.locator('input').inputValue(), invitation.code)
  assert.equal(await joinDialog.locator('input').isDisabled(), true, '未知结果不能改用另一个邀请码')
  await joinDialog.getByRole('button', { name: '取消', exact: true }).click()
  const leavePrompt = joinPage.getByRole('dialog', { name: '未保存的修改', exact: true })
  await leavePrompt.getByRole('button', { name: '继续填写', exact: true }).click()
  await leavePrompt.waitFor({ state: 'hidden' })
  assert.equal(await joinDialog.locator('input').inputValue(), invitation.code)
  await joinPage.goBack()
  await leavePrompt.getByRole('button', { name: '继续填写', exact: true }).click()
  await leavePrompt.waitFor({ state: 'hidden' })
  assert.equal(new URL(joinPage.url()).pathname, '/')
  const beforeReload = joinPage.waitForEvent('dialog')
  const reloadAttempt = joinPage.reload().catch(() => null)
  const unloadPrompt = await beforeReload
  assert.equal(unloadPrompt.type(), 'beforeunload')
  await unloadPrompt.dismiss()
  await reloadAttempt
  assert.equal(await joinDialog.locator('input').inputValue(), invitation.code, '取消刷新必须保留原码核对')
  await joinPage.screenshot({ path: fileURLToPath(new URL('手机加入结果核对.png', out)), fullPage: true, animations: 'disabled' })
  for (const width of [320, 390, 768, 1440]) {
    await joinPage.setViewportSize({ width, height: 900 })
    assert.equal(await joinPage.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${width}px 页面不得溢出`)
    assert.ok(await joinDialog.evaluate(el => el.getBoundingClientRect().left >= 0 && el.getBoundingClientRect().right <= innerWidth), `${width}px 加入弹窗不得溢出`)
  }
  await joinPage.setViewportSize({ width: 390, height: 844 })

  // 成功后的目录刷新不得在用户已离开账号页后把页面拉回旧组织；创建与加入共用同一规则。
  const directoryNavigation = []
  async function leaveDuringDirectoryRefresh(a, action, targetOrg) {
    let releaseDirectory, observedDirectory
    const gate = new Promise(resolve => { releaseDirectory = resolve })
    const started = new Promise(resolve => { observedDirectory = resolve })
    await a.page.route('**/api/me/organizations', async route => {
      const response = await route.fetch()
      assert.equal(response.status(), 200)
      observedDirectory()
      await gate
      await route.fulfill({ response })
    }, { times: 1 })
    await action()
    await started
    await a.page.getByRole('button', { name: '浏览公开活动', exact: true }).click()
    await a.page.waitForURL(url => url.pathname === '/public/activities')
    const delayed = a.page.waitForResponse(response => new URL(response.url()).pathname === '/api/me/organizations')
    releaseDirectory()
    await delayed
    // SPA 已发生过 networkidle 时等待它会立即返回；明确观察是否出现迟到的组织导航。
    await a.page.waitForURL(url => url.pathname.startsWith('/org/'), { timeout: 1500 }).catch(() => undefined)
    directoryNavigation.push({ action: targetOrg.action, pathname: new URL(a.page.url()).pathname, targetOrg: targetOrg.id })
  }
  const retried = joinPage.waitForResponse(response => response.url().endsWith('/api/invitations/accept'))
  await leaveDuringDirectoryRefresh(participant, () => joinDialog.getByRole('button', { name: '核对加入结果', exact: true }).click(),
    { action: 'joined', id: org.id })
  assert.equal((await retried).status(), 200)
  const joinedMembers = await call(owner.ctx, 'GET', `${op}/members?pageSize=100`)
  assert.equal(joinedMembers.items.filter(x => x.username === participant.username).length, 1)
  assert.equal(joinedMembers.items.find(x => x.username === participant.username).role, 'MEMBER')
  assert.equal((await call(owner.ctx, 'GET', `${op}/invitations`)).items.find(x => x.invitationId === invitation.invitationId).usedCount, 1)

  await owner.page.goto('/', { waitUntil: 'networkidle' }); current = owner.page
  await owner.page.getByRole('button', { name: '创建组织', exact: true }).click()
  const createOrgDialog = owner.page.getByRole('dialog', { name: '创建组织', exact: true })
  await createOrgDialog.getByLabel('组织名称', { exact: true }).fill(`目录延后创建-${Date.now()}`)
  let createdOrg
  await leaveDuringDirectoryRefresh(owner, async () => {
    const response = owner.page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/organizations')
    await createOrgDialog.getByRole('button', { name: '创建', exact: true }).click()
    createdOrg = await (await response).json()
  }, { action: 'created', id: 'created' })
  directoryNavigation.at(-1).targetOrg = createdOrg.id
  console.log(JSON.stringify({ directoryNavigation }))
  assert.ok(directoryNavigation.every(x => x.pathname === '/public/activities'), '创建／加入后的旧目录刷新不得导航已离开的页面')
  assert.equal(expectedJoinFailures.size, 0)
  // 明确拒绝后可编辑；无效、已撤销、已用完的邀请都不能新增成员。
  const otherOp = `/api/organizations/${createdOrg.id}`
  const revoked = await call(owner.ctx, 'POST', `${otherOp}/invitations`, {})
  await call(owner.ctx, 'POST', `${otherOp}/invitations/${revoked.invitationId}/revoke`)
  const used = await call(owner.ctx, 'POST', `${otherOp}/invitations`, { maxUses: 1 })
  await call(organizer.ctx, 'POST', '/api/invitations/accept', { code: used.code })
  const invalidInvitations = []
  await joinPage.goto('/', { waitUntil: 'networkidle' }); current = joinPage
  for (const [kind, code] of [['invalid', '0000-0000-0000-0000-0000-0000-0000-0000'], ['revoked', revoked.code], ['exhausted', used.code]]) {
    await joinPage.getByRole('button', { name: '加入组织', exact: true }).click()
    await joinDialog.getByLabel('邀请码', { exact: true }).fill(code)
    const response = joinPage.waitForResponse(r => r.url().endsWith('/api/invitations/accept'))
    await joinDialog.getByRole('button', { name: '加入组织', exact: true }).click()
    const rejected = await response
    assert.equal(rejected.status(), 400)
    assert.equal((await rejected.json()).code, 'INVITATION_INVALID')
    await joinDialog.getByRole('alert').waitFor()
    assert.equal(await joinDialog.locator('input').isDisabled(), false)
    assert.equal(await joinDialog.locator('input').inputValue(), code)
    assert.equal((await call(owner.ctx, 'GET', `${otherOp}/members?pageSize=100`)).items.filter(x => x.username === participant.username).length, 0)
    invalidInvitations.push({ kind, status: rejected.status(), retainedInput: true, noMembership: true })
    await joinDialog.getByRole('button', { name: '取消', exact: true }).click()
    await leavePrompt.getByRole('button', { name: '放弃', exact: true }).click()
    await leavePrompt.waitFor({ state: 'hidden' })
    await joinDialog.waitFor({ state: 'hidden' })
  }
  assert.equal((await call(owner.ctx, 'GET', `${otherOp}/invitations`)).items.find(x => x.invitationId === used.invitationId).usedCount, 1)
  // 同页成功仍应刷新目录并进入目标组织，不能靠禁用导航让反例通过。
  await owner.page.goto(`/org/${createdOrg.id}/members`, { waitUntil: 'networkidle' }); current = owner.page
  await owner.page.getByRole('button', { name: '创建邀请', exact: true }).click()
  const invitationDialog = owner.page.getByRole('dialog', { name: '创建邀请', exact: true })
  await invitationDialog.getByLabel('有效期（天）', { exact: true }).fill('1')
  await invitationDialog.getByLabel('最大使用次数', { exact: true }).fill('1')
  const invitationResponse = owner.page.waitForResponse(r => r.request().method() === 'POST'
    && new URL(r.url()).pathname === `${otherOp}/invitations`)
  await invitationDialog.getByRole('button', { name: '生成邀请码', exact: true }).click()
  const issuedResponse = await invitationResponse
  assert.equal(issuedResponse.status(), 201)
  assert.ok(issuedResponse.request().headers()['idempotency-key'])
  const normalInvite = await issuedResponse.json()
  assert.equal(normalInvite.maxUses, 1)
  assert.equal(await invitationDialog.locator('.members-code').innerText(), normalInvite.code)
  await invitationDialog.getByRole('button', { name: '我已保存', exact: true }).click()
  await invitationDialog.waitFor({ state: 'hidden' })
  assert.equal(await owner.page.locator('.members-code').count(), 0, '关闭邀请弹窗后清除明文')
  current = joinPage
  await joinPage.getByRole('button', { name: '加入组织', exact: true }).click()
  await joinDialog.getByLabel('邀请码', { exact: true }).fill(normalInvite.code)
  await joinDialog.getByRole('button', { name: '加入组织', exact: true }).click()
  await joinPage.waitForURL(url => url.pathname === `/org/${createdOrg.id}`)
  assert.equal((await call(participant.ctx, 'GET', '/api/me/organizations')).items.find(x => x.id === createdOrg.id).role, 'MEMBER')
  await joinPage.getByRole('button', { name: '展开导航', exact: true }).click()
  await joinPage.getByRole('link', { name: '组织设置', exact: true }).click()
  const memberSettings = joinPage.locator('[data-ai-role="member"]')
  await memberSettings.waitFor()
  assert.ok((await memberSettings.innerText()).includes('配置、测试、启用与密钥管理仅组织负责人可操作。'))
  assert.equal(await joinPage.getByRole('button', { name: '保存配置', exact: true }).count(), 0)
  assert.equal((await call(owner.ctx, 'GET', `${otherOp}/invitations`)).items.find(x => x.invitationId === normalInvite.invitationId).usedCount, 1)
  await owner.page.goto('/', { waitUntil: 'networkidle' }); current = owner.page
  await owner.page.getByRole('button', { name: '创建组织', exact: true }).click()
  await createOrgDialog.getByLabel('组织名称', { exact: true }).fill(`正常创建-${Date.now()}`)
  const normalCreateResponse = owner.page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/organizations')
  await createOrgDialog.getByRole('button', { name: '创建', exact: true }).click()
  const normalOrg = await (await normalCreateResponse).json()
  await owner.page.waitForURL(url => url.pathname === `/org/${normalOrg.id}`)
  assert.equal((await call(owner.ctx, 'GET', '/api/me/organizations')).items.find(x => x.id === normalOrg.id).role, 'OWNER')
  const joinRecovery = { sendingInputDisabled, sendingClosePrevented, unknownResult: '原邀请码核对',
    membershipCount: 1, usedCount: 1, invalidInvitations, directoryNavigation,
    normalNavigation: { joined: true, created: true }, normalInvitationUi: { created: true, clearedPlaintext: true, memberSettingsReadOnly: true },
    cancelledReloadKeepsCode: true, widths: [320, 390, 768, 1440],
    simulation: '真实接受后替换 503；目录响应由脚本延后；不证明自然网络时序' }
  steps.push('邀请码真实接受后丢失响应，原码核对仅一条关系／一次使用，手机关闭与后退保护，创建／加入迟到目录不导航')
  // 账号及部分成员关系通过真实 API 准备，以下业务写入均由页面操作。
  const p = owner.page; current = p
  await p.goto(`/org/${org.id}/activities`)
  await p.getByRole('button', { name: '创建活动', exact: true }).click()
  const dialog = p.getByRole('dialog')
  await dialog.getByLabel('活动主题', { exact: true }).fill(`浏览器团建-${stamp}`)
  await dialog.getByRole('combobox').first().click()
  await p.getByRole('option', { name: organizer.username, exact: true }).click()
  await dialog.getByLabel('活动简介', { exact: true }).fill('组织成员团建交流，场地和物料由专人负责。')
  await dialog.getByLabel('活动开始时间', { exact: true }).fill('2027-10-05T14:00')
  await dialog.getByLabel('活动结束时间', { exact: true }).fill('2027-10-05T16:00')
  await dialog.getByLabel('活动地点', { exact: true }).fill('团建场地')
  await dialog.getByLabel('预算（元）', { exact: true }).fill('1000')
  let firstCreatedId, creationKey, creationInput
  expectedCreationPath = `${op}/activities`
  await p.route(`${base}${expectedCreationPath}`, async route => {
    if (route.request().method() !== 'POST') return route.continue()
    if (firstCreatedId) {
      assert.equal(route.request().headers()['idempotency-key'], creationKey)
      assert.deepEqual(route.request().postDataJSON(), creationInput)
      return route.continue()
    }
    creationKey = route.request().headers()['idempotency-key']
    assert.ok(creationKey)
    creationInput = route.request().postDataJSON()
    const upstream = await route.fetch()
    assert.equal(upstream.status(), 201)
    firstCreatedId = (await upstream.json()).id
    expectedCreationFailures.add(p)
    await route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({
      type: 'about:blank', title: '创建响应未确认', status: 503, code: 'SERVICE_UNAVAILABLE',
      detail: '模拟创建已提交后响应丢失，请核对保存结果。',
    }) })
  })
  const created = p.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith(`${op}/activities`))
  await dialog.getByRole('button', { name: '保存草稿', exact: true }).click()
  assert.equal((await created).status(), 503)
  await dialog.getByText('为避免重复创建，当前内容暂不可修改；核对成功后可进入详情编辑。', { exact: true }).waitFor()
  assert.equal(await dialog.getByLabel('活动主题', { exact: true }).isDisabled(), true)
  assert.equal((await call(owner.ctx, 'GET', `${op}/activities`)).total, 1)
  const recovered = p.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith(`${op}/activities`))
  await dialog.getByRole('button', { name: '核对保存结果', exact: true }).click()
  const cr = await recovered; assert.equal(cr.status(), 201); const activity = await cr.json()
  assert.equal(activity.id, firstCreatedId)
  assert.equal((await call(owner.ctx, 'GET', `${op}/activities`)).total, 1)
  const creationRecovery = { sameKeyAndContent: true, sameActivityId: true, activityCount: 1,
    simulation: '真实创建成功后由浏览器替换503，不证明自然网络时序' }
  const path = `/org/${org.id}/activities/${activity.id}`, ap = `${op}/activities/${activity.id}`
  steps.push('浏览器创建活动与指定负责人，响应替换503后原键核对仅1条')
  const q = organizer.page; current = q
  await q.goto(path + '?tab=proposal')
  await q.locator('.proposal-section textarea').waitFor()
  assert.equal(await q.getByRole('button', { name: '删除草稿', exact: true }).count(), 0, '活动负责人不得显示组织负责人专属删除入口')
  await q.locator('.proposal-section textarea').fill('场地确认、物料采购与团队活动安排。')
  const saved = q.waitForResponse(r => r.request().method() === 'PUT' && r.url().endsWith('/proposal'))
  await q.getByRole('button', { name: '保存方案', exact: true }).click()
  assert.equal((await saved).status(), 200)
  await q.getByRole('button', { name: '提交确认', exact: true }).click()
  await q.getByRole('button', { name: '提交', exact: true }).click()
  await q.getByText('等待组织负责人确认或驳回', { exact: true }).waitFor()
  assert.equal(await q.getByRole('button', { name: '通过', exact: true }).count(), 0, '活动负责人不得显示审批入口')
  assert.equal(await q.getByRole('button', { name: '驳回', exact: true }).count(), 0)
  current = p; await p.goto(path)
  await p.getByRole('button', { name: '通过', exact: true }).click()
  await p.getByText('筹备中：分配任务、准备物资、审核报名', { exact: true }).waitFor()
  steps.push('活动负责人提交、组织负责人审批')
  current = q; await q.goto(path + '?tab=tasks')
  await q.getByRole('button', { name: '新建任务', exact: true }).waitFor()
  assert.equal(await q.getByRole('button', { name: '撤回确认', exact: true }).count(), 0)
  await q.getByRole('button', { name: '新建任务', exact: true }).click()
  const td = q.getByRole('dialog', { name: '新建任务', exact: true })
  await td.getByLabel('任务标题', { exact: true }).fill('采购团建物料')
  await td.getByRole('combobox').first().click()
  await q.getByRole('option', { name: executor.username, exact: true }).click()
  await td.getByRole('button', { name: '创建', exact: true }).click()
  await q.getByText('采购团建物料', { exact: true }).waitFor()
  steps.push('页面分派任务')
  await q.goto(path + '?tab=registration')
  await q.locator('.reg-switch').click()
  const m = participant.page; current = m
  await m.goto(path + '?tab=registration')
  await m.getByRole('button', { name: '我要报名', exact: true }).click()
  const rd = m.getByRole('dialog', { name: '报名', exact: true })
  await rd.getByLabel('展示姓名', { exact: true }).fill('普通参与者')
  await rd.getByRole('button', { name: '提交', exact: true }).click()
  await rd.waitFor({ state: 'hidden' })
  await m.getByText('待审核', { exact: true }).waitFor()
  await m.screenshot({ path: fileURLToPath(new URL('手机报名.png', out)), fullPage: true, animations: 'disabled' })
  current = q; await q.reload()
  await q.getByRole('button', { name: '通过', exact: true }).click()
  await q.getByRole('tabpanel', { name: '报名', exact: true }).getByText('已通过', { exact: true }).waitFor()
  await m.reload()
  await m.getByText('已通过', { exact: true }).waitFor()
  assert.equal(await m.getByRole('button', { name: '撤回确认', exact: true }).count(), 0)
  await m.screenshot({ path: fileURLToPath(new URL('手机报名.png', out)), fullPage: true, animations: 'disabled' })
  steps.push('手机报名、负责人审核、手机刷新查看通过结果')
  current = q; await q.goto(path + '?tab=lifecycle')
  await q.getByRole('button', { name: '启动执行', exact: true }).click()
  await q.getByRole('dialog').getByRole('button', { name: '启动执行', exact: true }).click()
  await q.getByText('执行中：记录进展与问题，结束后进入复盘', { exact: true }).waitFor()
  const e = executor.page; current = e
  await e.goto(`/org/${org.id}/my-tasks`)
  await e.getByRole('button', { name: '开始处理', exact: true }).click()
  await e.getByRole('button', { name: '标记完成', exact: true }).click()
  await e.getByText('暂无分配给你的未完成任务', { exact: false }).waitFor()
  steps.push('执行者从我的任务开始处理并完成')
  await m.goto(`/org/${org.id}/my-tasks`)
  await m.getByText('暂无分配给你的未完成任务', { exact: false }).waitFor()
  const tasks = await call(owner.ctx, 'GET', `${ap}/tasks`)
  assert.equal(tasks.items.length, 1); assert.equal(tasks.items[0].status, 'COMPLETED')
  assert.equal(await m.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
  current = q; await q.reload()
  await q.getByRole('button', { name: '结束执行', exact: true }).click()
  await q.getByRole('button', { name: '进入复盘', exact: true }).click()
  await q.getByText('复盘中：收集反馈、编写并确认总结', { exact: true }).waitFor()
  assert.equal((await call(owner.ctx, 'GET', ap)).status, 'REVIEWING')
  await q.getByRole('dialog').waitFor({ state: 'hidden' })
  await q.screenshot({ path: fileURLToPath(new URL('负责人复盘.png', out)), fullPage: true, animations: 'disabled' })
  steps.push('启动执行、完成任务后进入复盘，真实接口重读状态')
  assert.deepEqual(errors, [])
  await writeFile(new URL('结果.json', out), JSON.stringify({ database: 'activity_test', activityId: activity.id, orgId: org.id, joinRecovery, creationRecovery, steps, errors }, null, 2))
  console.log(JSON.stringify({ steps, activityId: activity.id, joinRecovery, creationRecovery, errors }))
} catch (e) {
  if (current) await writeFile(new URL('失败页面.txt', out), await current.locator('body').innerText())
  console.error(e.message); process.exitCode = 1
} finally { await browser.close() }
