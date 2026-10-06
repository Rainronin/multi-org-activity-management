// S10 观察脚本：P3 区块的编辑／改派、物资编辑与角色按钮。
// 仅可针对显式连接 activity_test 的后端运行，会新增专用测试数据。
import assert from 'node:assert/strict'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')

const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const stamp = process.env.FLOW_RUN_STAMP || Date.now()
  console.log(JSON.stringify({ runStamp: stamp }))
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
  // F05：方案保存与审批必须携带当前 version，调用前先读取
  async function currentVersion(activityId) {
    return (await json(api, 'GET', `/api/organizations/${org.id}/activities/${activityId}`)).version
  }
  async function login(ctx, username) {
    if (!process.env.REUSE_TEST_ACCOUNTS) await json(ctx, 'POST', '/api/auth/register', { username, password })
    const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
    const res = await ctx.request.post('/api/auth/login', { form: { username, password }, headers: { [csrf.headerName]: csrf.token } })
    assert.equal(res.status(), 204, '准备流程：登录失败')
  }
  const memberCtx = await browser.newContext({ baseURL: 'http://127.0.0.1:5174' })
  await login(api, ownerName)
  await login(memberCtx, memberName)
  const org = await json(api, 'POST', '/api/organizations', { name: `界面核对-${stamp}-${Date.now()}` })
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
  await json(api, 'PUT', `/api/organizations/${org.id}/activities/${activity.id}/proposal`, { content: '方案', version: await currentVersion(activity.id) })
  await json(api, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/submit`)
  await json(api, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/approve`, { version: await currentVersion(activity.id) })
  await json(api, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/tasks`,
    { title: '界面核对任务', assigneeMemberId: memberMember, priority: 'MEDIUM' })
  await json(api, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/materials`,
    { name: '界面核对物资', quantity: 2, unit: '条' })
  await api.close()

  const detailPath = `/org/${org.id}/activities/${activity.id}?tab=tasks`
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

  const pngBytes = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64')
  const attachmentRoot = `/api/organizations/${org.id}/activities/${activity.id}`
  // 全局确认框跨路由保留时，任务取消与两类资料删除不能再发旧写请求。
  let scopedActivity = await json(ownerCtx, 'POST', `/api/organizations/${org.id}/activities`, {
    name: '旧确认范围核对活动', description: '任务与资料的原页面范围', ownerMemberId: ownerMember,
    startTime: new Date(Date.now() + 8 * 86400000).toISOString(),
    endTime: new Date(Date.now() + 8 * 86400000 + 7200000).toISOString(),
    location: '会议室', registrationScope: 'INTERNAL', capacity: 10,
  }, { 'Idempotency-Key': `scope-${stamp}` })
  const scopedPath = `/api/organizations/${org.id}/activities/${scopedActivity.id}`
  await json(ownerCtx, 'PUT', `${scopedPath}/proposal`, { content: '范围核对方案', version: scopedActivity.version })
  await call(ownerCtx, 'POST', `${scopedPath}/submit`)
  scopedActivity = await json(ownerCtx, 'GET', scopedPath)
  await json(ownerCtx, 'POST', `${scopedPath}/approve`, { version: scopedActivity.version })
  const scopedTask = await json(ownerCtx, 'POST', `${scopedPath}/tasks`, {
    title: '旧确认范围任务', assigneeMemberId: ownerMember, priority: 'MEDIUM',
  })
  async function prepareAttachment(path, name) {
    const csrf = await (await ownerCtx.request.get('/api/auth/csrf')).json()
    const response = await ownerCtx.request.post(path, {
      headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': `file-${stamp}-${path.includes('/tasks/') ? 'task' : 'activity'}` },
      multipart: { file: { name, mimeType: 'image/png', buffer: pngBytes } },
    })
    assert.equal(response.status(), 201)
    return response.json()
  }
  await prepareAttachment(`${scopedPath}/attachments`, '范围活动资料.png')
  await prepareAttachment(`${scopedPath}/tasks/${scopedTask.taskId}/attachments`, '范围任务资料.png')
  const staleConfirmations = []
  for (const kind of ['task', 'activity-attachment', 'task-attachment']) {
    await owner.page.goto(detailPath, { waitUntil: 'networkidle' })
    await owner.page.getByRole('link', { name: '活动管理', exact: true }).click()
    await owner.page.getByRole('button', { name: scopedActivity.name, exact: true }).click()
    await owner.page.getByRole('tab', { name: '任务与物资', exact: true }).click()
    async function openConfirmation() {
      if (kind === 'task') {
        await owner.page.locator('.p3-block', { has: owner.page.getByRole('heading', { name: '任务', exact: true }) })
          .getByRole('button', { name: '取消', exact: true }).click()
      } else if (kind === 'activity-attachment') {
        await owner.page.locator('.p3-block', { has: owner.page.getByRole('heading', { name: '资料', exact: true }) })
          .getByRole('button', { name: '删除', exact: true }).click()
      } else {
        await owner.page.getByRole('button', { name: '资料', exact: true }).first().click()
        await owner.page.getByRole('dialog', { name: /任务资料：/ }).getByRole('button', { name: '删除', exact: true }).click()
      }
    }
    await openConfirmation()
    const title = kind === 'task' ? '取消任务' : kind === 'activity-attachment' ? '删除附件' : '删除任务资料'
    const confirmation = owner.page.getByRole('dialog', { name: title, exact: true })
    await confirmation.waitFor()
    for (let step = 0; new URL(owner.page.url()).pathname !== `/org/${org.id}/activities/${activity.id}` && step < 5; step++) {
      await owner.page.goBack()
    }
    await owner.page.waitForURL(url => url.pathname === `/org/${org.id}/activities/${activity.id}`)
    let writes = 0
    const countWrite = request => {
      const path = new URL(request.url()).pathname
      if (request.method() === 'DELETE' && /\/attachments\/\d+$/.test(path)
        || request.method() === 'POST' && path.endsWith('/transition')) writes++
    }
    owner.page.on('request', countWrite)
    await confirmation.getByRole('button', { name: kind === 'task' ? '取消任务' : '删除', exact: true }).click()
    await confirmation.waitFor({ state: 'hidden' })
    await owner.page.waitForLoadState('networkidle')
    owner.page.off('request', countWrite)
    assert.equal((await json(ownerCtx, 'GET', `${scopedPath}/tasks`)).items[0].status, 'PENDING')
    const filePath = kind === 'task-attachment' ? `${scopedPath}/tasks/${scopedTask.taskId}/attachments` : `${scopedPath}/attachments`
    assert.equal((await json(ownerCtx, 'GET', filePath)).items.length, 1)
    await owner.page.getByRole('link', { name: '活动管理', exact: true }).click()
    await owner.page.getByRole('button', { name: scopedActivity.name, exact: true }).click()
    await owner.page.getByRole('tab', { name: '任务与物资', exact: true }).click()
    await openConfirmation()
    const fresh = owner.page.waitForResponse(response => response.request().method() === (kind === 'task' ? 'POST' : 'DELETE')
      && (kind === 'task' ? response.url().endsWith('/transition') : /\/attachments\/\d+$/.test(new URL(response.url()).pathname)))
    await confirmation.getByRole('button', { name: kind === 'task' ? '取消任务' : '删除', exact: true }).click()
    assert.ok((await fresh).ok())
    await confirmation.waitFor({ state: 'hidden' })
    if (kind === 'task') {
      assert.equal((await json(ownerCtx, 'GET', `${scopedPath}/tasks`)).items[0].status, 'CANCELLED')
      assert.ok((await call(ownerCtx, 'POST', `${scopedPath}/tasks/${scopedTask.taskId}/transition`, { toStatus: 'PENDING' })).ok())
    } else {
      assert.equal((await json(ownerCtx, 'GET', filePath)).items.length, 0)
    }
    staleConfirmations.push({ kind, oldWrites: writes, originalStateKept: true, freshConfirmation: true })
  }
  console.log(JSON.stringify({ staleConfirmations }))
  assert.ok(staleConfirmations.every(result => result.oldWrites === 0), '离开任务与资料页面后的旧确认不得发出写请求')
  // 旧任务写请求不发送到后端，真实退出并重登后再送达 AUTH_REQUIRED。
  const staleTask401 = []
  for (const scope of ['detail', 'my-tasks']) {
    const ctx = await browser.newContext({ baseURL: 'http://127.0.0.1:5174' })
    const actor = await loginAs(ctx, memberName, `旧401-${scope}`)
    const page = actor.page
    if (scope === 'my-tasks') await page.getByRole('link', { name: '我的任务', exact: true }).click()
    let release, observe
    const gate = new Promise(resolve => { release = resolve })
    const started = new Promise(resolve => { observe = resolve })
    await page.route('**/tasks/*/advance', async route => {
      observe()
      await gate
      await route.fulfill({ status: 401, contentType: 'application/problem+json',
        body: JSON.stringify({ status: 401, code: 'AUTH_REQUIRED', detail: '延后的旧任务会话响应' }) })
    }, { times: 1 })
    await page.getByRole('button', { name: '开始处理', exact: true }).click()
    await started
    try {
      await page.getByRole('button', { name: '账号菜单', exact: true }).click()
      await page.getByText('退出登录', { exact: true }).click()
      await page.waitForURL(url => url.pathname === '/login')
      await page.getByPlaceholder('注册时设置的用户名').fill(memberName)
      await page.getByPlaceholder('请输入密码').fill(password)
      await page.getByRole('button', { name: '进入工作区', exact: true }).click()
      await page.waitForURL(url => url.pathname === '/')
      await page.locator('li').filter({ has: page.getByText(org.name, { exact: true }) })
        .getByRole('button', { name: '进入工作区', exact: true }).click()
      if (scope === 'detail') {
        await page.getByRole('link', { name: '活动管理', exact: true }).click()
        await page.getByRole('button', { name: activity.name, exact: true }).click()
        await page.getByRole('tab', { name: '任务与物资', exact: true }).click()
      } else await page.getByRole('link', { name: '我的任务', exact: true }).click()
      const newPath = new URL(page.url()).pathname
      const response = page.waitForResponse(res => res.url().endsWith('/advance') && res.status() === 401)
      release()
      await response
      await page.waitForURL(url => url.pathname === '/login', { timeout: 1500 }).catch(() => undefined)
      const keptNewSession = new URL(page.url()).pathname === newPath
      assert.equal((await call(ctx, 'GET', '/api/auth/me')).status(), 200)
      assert.equal((await json(ownerCtx, 'GET', `${attachmentRoot}/tasks`)).items[0].status, 'PENDING')
      staleTask401.push({ scope, keptNewSession, backendMutation: false, simulatedDelayedResponse: true })
      owner.errors.push(...actor.errors)
    } finally { release(); await ctx.close() }
  }
  console.log(JSON.stringify({ staleTask401 }))
  assert.ok(staleTask401.every(result => result.keptNewSession), '旧任务 401 不得卸载后来重新登录的工作区')
  await owner.page.goto(detailPath, { waitUntil: 'networkidle' })
  async function loseUploadResponse(page, path, fileInput, fileName) {
    const url = new URL(path, 'http://127.0.0.1:5174').href
    const lose = async (route) => {
      const written = await route.fetch()
      assert.equal(written.status(), 201, `响应丢失前须先真实上传：${path}`)
      await route.fulfill({ status: 503, contentType: 'application/problem+json',
        body: JSON.stringify({ status: 503, code: 'TEST_UNAVAILABLE', detail: '模拟上传响应丢失' }) })
    }
    await page.route(url, lose)
    await fileInput.setInputFiles({ name: fileName, mimeType: 'image/png', buffer: pngBytes })
    await page.getByText('模拟上传响应丢失').waitFor()
    await page.unroute(url, lose)
  }

  // 活动资料：真实写入后丢响应，页面须保留原文件供同键核对。
  const activitySection = owner.page.locator('.p3-block', { has: owner.page.getByRole('heading', { name: '资料', exact: true }) })
  const activityPath = `${attachmentRoot}/attachments`
  await loseUploadResponse(owner.page, activityPath, activitySection.locator('input[type=file]'), '活动照片.png')
  assert.equal(await activitySection.getByRole('button', { name: '核对上传结果' }).isVisible(), true)
  assert.equal(await activitySection.locator('input[type=file]').isDisabled(), true)
  await owner.page.getByRole('button', { name: '资料', exact: true }).first().click()
  const ownerTaskDialog = owner.page.getByRole('dialog', { name: /任务资料/ })
  assert.equal(await ownerTaskDialog.getByRole('button', { name: '上传资料' }).isDisabled(), true,
    '活动上传结果未确认时，不得把原键用于任务资料')
  await ownerTaskDialog.getByRole('button', { name: '关闭', exact: true }).click()
  await activitySection.getByRole('button', { name: '核对上传结果' }).click()
  assert.equal((await json(ownerCtx, 'GET', activityPath)).items.length, 1, '活动资料原键核对不应重复上传')

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

  // S10 任务资料：承担者打开任务资料弹窗，上传真实 PNG，活动资料列表不受影响
  await member.page.getByRole('button', { name: '资料' }).first().click()
  await member.page.waitForSelector('text=任务资料：', { timeout: 10000 })
  const dialog = member.page.locator('.el-dialog').filter({ hasText: '任务资料：' })
  const uploadPath = fileURLToPath(new URL('../验证截图/S10-任务资料.png', import.meta.url))
  const taskId = currentTask.items[0].taskId
  const taskAttachmentPath = `${attachmentRoot}/tasks/${taskId}/attachments`
  await loseUploadResponse(member.page, taskAttachmentPath, dialog.locator('input[type=file]'), '现场照片.png')
  assert.equal(await dialog.getByRole('button', { name: '核对上传结果' }).isVisible(), true)
  assert.equal(await dialog.locator('input[type=file]').isDisabled(), true)
  await member.page.setViewportSize({ width: 390, height: 844 })
  const taskDialogBox = await dialog.boundingBox()
  assert.ok(taskDialogBox && taskDialogBox.x >= 0 && taskDialogBox.x + taskDialogBox.width <= 390,
    '手机任务资料核对弹窗应完整显示')
  await member.page.setViewportSize({ width: 1440, height: 1000 })
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await member.page.locator('.el-message-box').getByRole('button', { name: '继续核对' }).click()
  assert.equal(await dialog.isVisible(), true, '取消关闭应保留原文件核对入口')
  await member.page.evaluate((target) => {
    void document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(target)
  }, `/org/${org.id}/activities`)
  await member.page.locator('.el-message-box').getByRole('button', { name: '继续编辑' }).click()
  assert.equal(new URL(member.page.url()).pathname, `/org/${org.id}/activities/${activity.id}`,
    '取消离开应保留原文件核对入口')
  await dialog.getByRole('button', { name: '核对上传结果' }).click()
  await member.page.waitForSelector('text=现场照片.png', { timeout: 10000 })
  const taskUploadItems = (await json(memberCtx2, 'GET', taskAttachmentPath)).items
  assert.equal(taskUploadItems.length, 1,
    '任务资料原键核对不应重复上传')
  await member.page.screenshot({ path: uploadPath, fullPage: true })
  const taskAttachmentRows = await dialog.locator('tbody tr').count()
  // 活动级资料表只包含活动资料：任务资料不得串入。
  const activityItems = (await json(memberCtx2, 'GET', activityPath)).items
  assert.equal(activityItems.length, 1)
  assert.equal(activityItems[0].purpose, 'ACTIVITY_DOC')
  assert.equal(taskUploadItems[0].purpose, 'TASK_ATTACHMENT')
  assert.notEqual(activityItems[0].attachmentId, taskUploadItems[0].attachmentId)
  const activityAttachmentRows = await member.page
    .locator('.p3-block', { has: member.page.getByRole('heading', { name: '资料', exact: true }) })
    .locator('tbody tr').count()

  // 新建任务／物资：页面收到 503 后，按抓取的原请求先写测试库，再核对同键重试。
  async function loseCreateResponse(path, clickSubmit) {
    const url = new URL(path, 'http://127.0.0.1:5174').href
    let original = null
    const lose = async (route) => {
      if (route.request().method() !== 'POST' || original) return route.continue()
      original = { data: JSON.parse(route.request().postData()),
        key: route.request().headers()['idempotency-key'] }
      await route.fulfill({ status: 503, contentType: 'application/problem+json',
        body: JSON.stringify({ status: 503, code: 'TEST_UNAVAILABLE', detail: '模拟创建响应丢失' }) })
    }
    await owner.page.route(url, lose)
    await clickSubmit()
    await owner.page.getByRole('button', { name: '核对创建结果' }).waitFor({ timeout: 10000 })
    await owner.page.unroute(url, lose)
    assert.ok(original?.key, '页面必须为创建请求保留防重键')
    const written = await call(ownerCtx, 'POST', path, original.data, { 'Idempotency-Key': original.key })
    assert.equal(written.status(), 201, `同一原请求须能真实创建：${path}`)
  }
  await owner.page.getByRole('button', { name: '新建任务', exact: true }).click()
  const newTaskDialog = owner.page.getByRole('dialog', { name: '新建任务', exact: true })
  await newTaskDialog.getByLabel('任务标题', { exact: true }).fill('响应丢失核对任务')
  await newTaskDialog.getByRole('combobox').first().click()
  await owner.page.getByRole('option', { name: memberName, exact: true }).click()
  await loseCreateResponse(`${attachmentRoot}/tasks`, () => newTaskDialog.getByRole('button', { name: '创建' }).click())
  assert.equal(await newTaskDialog.getByLabel('任务标题', { exact: true }).isDisabled(), true)
  await newTaskDialog.getByRole('button', { name: '取消', exact: true }).click()
  await owner.page.getByRole('dialog', { name: '未保存的任务' }).getByRole('button', { name: '继续填写' }).click()
  assert.equal(await newTaskDialog.getByRole('button', { name: '核对创建结果' }).isVisible(), true)
  await newTaskDialog.getByRole('button', { name: '核对创建结果' }).click()
  await newTaskDialog.waitFor({ state: 'hidden' })
  const createdTasks = (await json(ownerCtx, 'GET', `${attachmentRoot}/tasks`)).items
    .filter((item) => item.title === '响应丢失核对任务')
  assert.equal(createdTasks.length, 1, '任务原键核对不应重复创建')

  await owner.page.getByRole('button', { name: '添加物资', exact: true }).click()
  const newMaterialDialog = owner.page.getByRole('dialog', { name: '添加物资', exact: true })
  await newMaterialDialog.getByPlaceholder('1-128 字符').fill('响应丢失核对物资')
  await loseCreateResponse(`${attachmentRoot}/materials`, () => newMaterialDialog.getByRole('button', { name: '添加' }).click())
  assert.equal(await newMaterialDialog.getByPlaceholder('1-128 字符').isDisabled(), true)
  await newMaterialDialog.getByRole('button', { name: '取消', exact: true }).click()
  await owner.page.getByRole('dialog', { name: '未保存的物资' }).getByRole('button', { name: '继续填写' }).click()
  assert.equal(await newMaterialDialog.getByRole('button', { name: '核对创建结果' }).isVisible(), true)
  await newMaterialDialog.getByRole('button', { name: '核对创建结果' }).click()
  await newMaterialDialog.waitFor({ state: 'hidden' })
  const createdMaterials = (await json(ownerCtx, 'GET', `${attachmentRoot}/materials`)).items
    .filter((item) => item.name === '响应丢失核对物资')
  assert.equal(createdMaterials.length, 1, '物资原键核对不应重复创建')

  assert.deepEqual([...owner.errors, ...member.errors], [])

  console.log(JSON.stringify({
    check: 'S10 P3 页面操作与角色按钮',
    orgId: org.id, activityId: activity.id,
    uploadRetry: { activityCount: (await json(ownerCtx, 'GET', activityPath)).items.length,
      taskCount: (await json(memberCtx2, 'GET', taskAttachmentPath)).items.length },
    createRetry: { taskCount: createdTasks.length, materialCount: createdMaterials.length },
    staleConfirmations,
    staleTask401,
    ownerChecks,
    memberChecks,
    reassignSaved: reassigned.includes('改派后的任务标题'),
    staleVersionSave: { status: stale.status(), code: staleBody.code },
    taskAttachment: { uploadedRows: taskAttachmentRows, activitySectionRows: activityAttachmentRows },
    browserErrors: [...owner.errors, ...member.errors],
    screenshots: [...shots, uploadPath].map((p) => p.split('验证截图').pop()),
  }))
} finally {
  await browser.close()
}
