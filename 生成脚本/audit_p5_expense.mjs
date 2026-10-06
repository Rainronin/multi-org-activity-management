// P5 经费真机核对：预算与统计、登记支出（含凭证）、提交、超支确认弹框、作废与更正、窄屏布局。
// 仅可针对显式连接 activity_test 的后端运行，会新增专用测试数据。
import assert from 'node:assert/strict'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
import { mkdir, readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')

const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64')

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const shots = []
try {
  const stamp = process.env.FLOW_RUN_STAMP || Date.now()
  console.log(JSON.stringify({ runStamp: stamp }))
  const ownerName = `p5owner${stamp}`
  const memberName = `p5member${stamp}`
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
      if (res.status() !== 429 || attempt >= 4) {
        throw new Error(`注册失败 ${res.status()} ${await res.text()}`)
      }
      await new Promise((resolve) => setTimeout(resolve, 60000))
    }
    const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
    assert.equal((await ctx.request.post('/api/auth/login', {
      form: { username: name, password }, headers: { [csrf.headerName]: csrf.token },
    })).status(), 204, '准备流程：登录失败')
  }

  // 准备：组织（预算 100）、已确认活动、活动负责人为普通成员
  const owner = await browser.newContext({ baseURL: base })
  await login(owner, ownerName)
  const member = await browser.newContext({ baseURL: base })
  await login(member, memberName)
  const org = await json(owner, 'POST', '/api/organizations', { name: `P5核对-${stamp}-${Date.now()}` })
  const invitation = await json(owner, 'POST', `/api/organizations/${org.id}/invitations`, {})
  await json(member, 'POST', '/api/invitations/accept', { code: invitation.code })
  const members = (await json(owner, 'GET', `/api/organizations/${org.id}/members?pageSize=100`)).items
  const ownerMemberId = members.find((x) => x.username === ownerName).memberId
  const memberMemberId = members.find((x) => x.username === memberName).memberId
  const activity = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
    name: 'P5 经费活动', description: '经费核对', location: '线上', ownerMemberId: memberMemberId,
    startTime: '2027-10-01T14:00:00+08:00', endTime: '2027-10-01T16:00:00+08:00',
    registrationScope: 'INTERNAL', capacity: 10, budget: '100.00',
  }, { 'Idempotency-Key': `p5-${stamp}` })
  const expensesPath = `/api/organizations/${org.id}/activities/${activity.id}/expenses`
  await json(owner, 'PUT', `/api/organizations/${org.id}/activities/${activity.id}/proposal`,
    { content: '经费核对方案', version: activity.version })
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/submit`)
  const pendingVersion = (await json(owner, 'GET', `/api/organizations/${org.id}/activities/${activity.id}`)).version
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/approve`,
    { note: '核对确认', version: pendingVersion })

  const detailPath = `/org/${org.id}/activities/${activity.id}?tab=expense`
  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
  const page = await owner.newPage({ viewport: { width: 1440, height: 1000 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))

  const concurrentRecheck = process.argv.includes('--concurrent-recheck')
  if (process.argv.includes('--readonly-recheck') || concurrentRecheck) {
    const results = []
    for (const kind of ['create', 'correction']) {
      for (const state of concurrentRecheck ? ['APPROVED'] : ['ARCHIVED', 'PENDING_APPROVAL']) {
        let subject = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
          name: `只读费用核对-${kind}-${state}`, description: '费用原请求核对', location: '线上',
          ownerMemberId, budget: '100.00',
          startTime: '2027-10-01T14:00:00+08:00', endTime: '2027-10-01T16:00:00+08:00',
        }, { 'Idempotency-Key': `readonly-${stamp}-${kind}-${state}` })
        const ap = `/api/organizations/${org.id}/activities/${subject.id}`
        const ep = `${ap}/expenses`
        const step = async (action, input = {}) => json(owner, 'POST', `${ap}/${action}`, {
          ...input, version: (await json(owner, 'GET', ap)).version,
        })
        await json(owner, 'PUT', `${ap}/proposal`, { content: '费用核对方案', version: subject.version })
        await json(owner, 'POST', `${ap}/submit`)
        await step('approve')
        const input = { purpose: `待核对费用-${kind}-${state}`, amount: '10.00',
          incurredOn: '2027-10-01', category: 'OTHER', noReceiptNote: '测试无票据' }
        let original
        if (kind === 'correction') {
          original = await json(owner, 'POST', ep, input, { 'Idempotency-Key': `original-${stamp}-${state}` })
          original = await json(owner, 'POST', `${ep}/${original.expenseId}/submit`, { version: original.version })
          original = await json(owner, 'POST', `${ep}/${original.expenseId}/confirm`, { version: original.version })
          original = await json(owner, 'POST', `${ep}/${original.expenseId}/void`, { version: original.version, reason: '更正费用' })
        }
        await page.goto(`/org/${org.id}/activities/${subject.id}?tab=expense`, { waitUntil: 'networkidle' })
        const target = kind === 'create' ? ep : `${ep}/${original.expenseId}/correction`
        let firstId, firstKey, firstInput, concurrentStatuses
        await page.route(`${base}${target}`, async route => {
          if (route.request().method() !== 'POST') return route.continue()
          if (firstId) {
            assert.equal(route.request().headers()['idempotency-key'], firstKey)
            assert.deepEqual(route.request().postDataJSON(), firstInput)
            return route.continue()
          }
          firstKey = route.request().headers()['idempotency-key']
          assert.ok(firstKey)
          firstInput = route.request().postDataJSON()
          if (concurrentRecheck) {
            const attempts = await Promise.all(Array.from({ length: 8 }, () => call(owner, 'POST', target,
              firstInput, { 'Idempotency-Key': firstKey })))
            concurrentStatuses = attempts.map(r => r.status())
            const ids = new Set()
            for (const response of attempts) {
              assert.ok([201, 409].includes(response.status()))
              const body = await response.json()
              if (response.status() === 409) assert.equal(body.code, 'REQUEST_IN_PROGRESS')
              else ids.add(body.expenseId)
            }
            assert.equal(ids.size, 1)
          }
          const upstream = await route.fetch()
          assert.equal(upstream.status(), 201)
          firstId = (await upstream.json()).expenseId
          await route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({
            type: 'about:blank', title: '响应未确认', status: 503, code: 'SERVICE_UNAVAILABLE',
            detail: '模拟费用已提交后响应丢失，请核对原请求。',
          }) })
        })
        const lost = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === target)
        if (kind === 'create') {
          await page.getByRole('button', { name: '登记支出', exact: true }).click()
          const dialog = page.getByRole('dialog', { name: '登记支出', exact: true })
          await dialog.getByPlaceholder('例如：横幅制作').fill(input.purpose)
          await dialog.getByLabel('金额（元）', { exact: true }).fill(input.amount)
          await dialog.getByRole('button', { name: '保存', exact: true }).click()
        } else {
          await page.locator('.expense-table .el-table__row').filter({ hasText: input.purpose })
            .getByRole('button', { name: '更正登记', exact: true }).click()
          await page.getByRole('dialog', { name: '更正登记', exact: true })
            .getByRole('button', { name: '建立更正', exact: true }).click()
        }
        assert.equal((await lost).status(), 503)
        const retryName = kind === 'create' ? '核对保存结果' : '核对更正结果'
        await page.getByRole('button', { name: retryName, exact: true }).first().waitFor()
        if (state === 'ARCHIVED') {
          await step('start-execution')
          await step('finish-execution')
          await json(owner, 'PUT', `${ap}/summary`, {
            summary: '活动结束，费用按实际确认结果保留。', version: (await json(owner, 'GET', ap)).version,
          })
          await step('summary/confirm')
          await step('archive')
        } else if (state === 'PENDING_APPROVAL') {
          await step('withdraw-approval', { reason: '调整活动安排' })
          await json(owner, 'POST', `${ap}/submit`)
        }
        subject = await json(owner, 'GET', ap)
        assert.equal(subject.status, state)
        if (!concurrentRecheck) {
        const refreshed = page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname === ap)
        await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
        assert.equal((await refreshed).status(), 200)
        await page.locator('.page-description .el-tag').filter({ hasText: state === 'ARCHIVED' ? '已归档' : '待确认' }).waitFor()
        assert.equal(await page.getByRole('button', { name: '登记支出', exact: true }).count(), 0)
        }
        const shot = fileURLToPath(new URL(`../验证截图/P5-${concurrentRecheck ? '并发' : '只读'}核对-${kind}-${state}.png`, import.meta.url))
        await page.screenshot({ path: shot, fullPage: true, animations: 'disabled' })
        shots.push(shot)
        const recovered = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === target)
        await page.getByRole('button', { name: retryName, exact: true }).first().click()
        const response = await recovered
        assert.equal(response.status(), 201)
        assert.equal((await response.json()).expenseId, firstId)
        assert.equal((await json(owner, 'GET', ep)).total, kind === 'correction' ? 2 : 1)
        if (!concurrentRecheck) {
          const denied = await call(owner, 'POST', target, firstInput, { 'Idempotency-Key': `new-${stamp}-${kind}-${state}` })
          assert.equal(denied.status(), 409)
          assert.equal((await denied.json()).code, 'ACTIVITY_STATE_CONFLICT')
        }
        results.push({ kind, state, sameId: true, sameKeyAndInput: true,
          count: kind === 'correction' ? 2 : 1,
          ...(concurrentRecheck ? { concurrentStatuses } : { newRequestRejected: true, currentUiStatusRefreshed: true }) })
        await page.unroute(`${base}${target}`)
      }
    }
    assert.deepEqual(errors, [])
    console.log(JSON.stringify({ check: concurrentRecheck ? '费用同键并发核对' : '费用只读阶段原键核对', results, errors,
      simulation: concurrentRecheck ? '8个真实同键并发请求后201向浏览器替换503，页面原键核对；非自然网络丢包'
        : '真实201后浏览器替换503；脚本触发visibilitychange；非自然网络或硬件切回', screenshots: shots }, null, 2))
  } else {
  // 1. 经费区块与初始统计（预算 100.00，尚无支出）
  await page.goto(detailPath, { waitUntil: 'networkidle' })
  await page.waitForSelector('text=经费', { timeout: 15000 })
  const initialBody = await page.locator('body').innerText()

  // 经费及站内提示的旧 401：真实退出重登后再释放，原写请求不转发后端。
  let probe = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
    name: '旧经费与提示会话核对', description: '独立活动避免影响经费统计', ownerMemberId: memberMemberId,
    startTime: '2027-10-02T14:00:00+08:00', endTime: '2027-10-02T16:00:00+08:00',
    location: '线上', registrationScope: 'INTERNAL', budget: '100.00',
  }, { 'Idempotency-Key': `probe-${stamp}` })
  const probePath = `/api/organizations/${org.id}/activities/${probe.id}`
  await json(owner, 'PUT', `${probePath}/proposal`, { content: '核对方案', version: probe.version })
  await call(owner, 'POST', `${probePath}/submit`)
  probe = await json(owner, 'GET', probePath)
  await json(owner, 'POST', `${probePath}/approve`, { version: probe.version })
  const probeExpenses = `${probePath}/expenses`
  const probeInput = { purpose: '旧响应核对支出', amount: '0.01', incurredOn: '2026-10-01', category: 'OTHER', noReceiptNote: '测试无票据' }
  for (let i = 0; i < 2; i++) {
    const item = await json(member, 'POST', probeExpenses, probeInput, { 'Idempotency-Key': `notice-${stamp}-${i}` })
    assert.ok((await call(member, 'POST', `${probeExpenses}/${item.expenseId}/submit`, { version: item.version })).ok())
  }
  const probeExpense = await json(owner, 'POST', probeExpenses, probeInput, { 'Idempotency-Key': `expense-${stamp}` })
  const delayedSessionResponses = []
  for (const scope of ['expense', 'notification-one', 'notification-all']) {
    const target = scope === 'expense' ? `/org/${org.id}/activities/${probe.id}?tab=expense` : '/'
    await page.goto(target, { waitUntil: 'networkidle' })
    const beforeUnread = (await json(owner, 'GET', '/api/me/notifications')).unread
    let release, observe
    const gate = new Promise(resolve => { release = resolve })
    const started = new Promise(resolve => { observe = resolve })
    const pattern = scope === 'expense' ? `**${probeExpenses}/${probeExpense.expenseId}/submit`
      : scope === 'notification-one' ? '**/api/me/notifications/*/read' : '**/api/me/notifications/read-all'
    await page.route(pattern, async route => {
      observe()
      await gate
      await route.fulfill({ status: 401, contentType: 'application/problem+json',
        body: JSON.stringify({ status: 401, code: 'AUTH_REQUIRED', detail: '延后的旧业务会话响应' }) })
    }, { times: 1 })
    if (scope === 'expense') await page.locator('.expense-table .el-table__row').filter({ hasText: probeInput.purpose })
      .filter({ has: page.getByRole('button', { name: '提交', exact: true }) }).getByRole('button', { name: '提交', exact: true }).click()
    else await page.getByRole('button', { name: scope === 'notification-one' ? '标为已读' : '全部标为已读', exact: true }).first().click()
    await started
    try {
      if (scope === 'expense') {
        await page.getByRole('button', { name: '账号菜单', exact: true }).click()
        await page.getByText('退出登录', { exact: true }).click()
      } else await page.getByRole('button', { name: '退出登录', exact: true }).click()
      await page.waitForURL(url => url.pathname === '/login')
      await page.getByPlaceholder('注册时设置的用户名').fill(ownerName)
      await page.getByPlaceholder('请输入密码').fill(password)
      await page.getByRole('button', { name: '进入工作区', exact: true }).click()
      await page.waitForURL(url => url.pathname === '/')
      if (scope === 'expense') {
        await page.locator('li').filter({ has: page.getByText(org.name, { exact: true }) })
          .getByRole('button', { name: '进入工作区', exact: true }).click()
        await page.getByRole('link', { name: '活动管理', exact: true }).click()
        await page.getByRole('button', { name: probe.name, exact: true }).click()
        await page.getByRole('tab', { name: '经费', exact: true }).click()
      }
      const newPath = new URL(page.url()).pathname
      const response = page.waitForResponse(res => res.status() === 401)
      release()
      await response
      await page.waitForURL(url => url.pathname === '/login', { timeout: 1500 }).catch(() => undefined)
      assert.equal((await call(owner, 'GET', '/api/auth/me')).status(), 200)
      assert.equal((await json(owner, 'GET', probeExpenses)).items.find(item => item.expenseId === probeExpense.expenseId).status, 'DRAFT')
      assert.equal((await json(owner, 'GET', '/api/me/notifications')).unread, beforeUnread)
      delayedSessionResponses.push({ scope, keptNewSession: new URL(page.url()).pathname === newPath, backendMutation: false })
    } finally { release() }
  }
  console.log(JSON.stringify({ delayedSessionResponses }))
  assert.ok(delayedSessionResponses.every(result => result.keptNewSession), '经费与提示的旧 401 不得清理重新登录的会话')
  await page.goto(`/org/${org.id}/activities/${probe.id}?tab=expense`, { waitUntil: 'networkidle' })
  const freshSubmit = page.waitForResponse(response => response.request().method() === 'POST'
    && response.url().endsWith(`/expenses/${probeExpense.expenseId}/submit`))
  await page.locator('.expense-table .el-table__row').filter({ hasText: probeInput.purpose })
    .filter({ has: page.getByRole('button', { name: '提交', exact: true }) }).getByRole('button', { name: '提交', exact: true }).click()
  assert.ok((await freshSubmit).ok())
  assert.equal((await json(owner, 'GET', probeExpenses)).items.find(item => item.expenseId === probeExpense.expenseId).status, 'PENDING')
  await page.goto('/', { waitUntil: 'networkidle' })
  const beforeRead = (await json(owner, 'GET', '/api/me/notifications')).unread
  const readOne = page.waitForResponse(response => response.request().method() === 'POST' && /\/notifications\/\d+\/read$/.test(response.url()))
  await page.getByRole('button', { name: '标为已读', exact: true }).first().click()
  assert.ok((await readOne).ok())
  assert.equal((await json(owner, 'GET', '/api/me/notifications')).unread, beforeRead - 1)
  const readAll = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/notifications/read-all'))
  await page.getByRole('button', { name: '全部标为已读', exact: true }).click()
  assert.ok((await readAll).ok())
  assert.equal((await json(owner, 'GET', '/api/me/notifications')).unread, 0)
  // 作废与更正确认跨页面保留时，不得把原支出 id 用于后来进入的活动。
  const staleExpenseConfirmations = []
  const currentProbeExpense = (await json(owner, 'GET', probeExpenses)).items.find(item => item.expenseId === probeExpense.expenseId)
  assert.ok((await call(owner, 'POST', `${probeExpenses}/${probeExpense.expenseId}/confirm`, { version: currentProbeExpense.version })).ok())
  for (const kind of ['void', 'correction']) {
    await page.goto(detailPath, { waitUntil: 'networkidle' })
    await page.getByRole('link', { name: '活动管理', exact: true }).click()
    await page.getByRole('button', { name: probe.name, exact: true }).click()
    await page.getByRole('tab', { name: '经费', exact: true }).click()
    const row = page.locator('.expense-table .el-table__row').filter({ hasText: probeInput.purpose })
      .filter({ has: page.getByRole('button', { name: kind === 'void' ? '作废' : '更正登记', exact: true }) })
    const dialog = page.getByRole('dialog', { name: kind === 'void' ? '作废支出' : '更正登记', exact: true })
    async function openConfirmation() {
      await row.getByRole('button', { name: kind === 'void' ? '作废' : '更正登记', exact: true }).click()
      await dialog.waitFor()
      if (kind === 'void') await dialog.getByRole('textbox').fill('旧确认范围核对')
    }
    await openConfirmation()
    for (let step = 0; new URL(page.url()).pathname !== `/org/${org.id}/activities/${activity.id}` && step < 5; step++) await page.goBack()
    await page.waitForURL(url => url.pathname === `/org/${org.id}/activities/${activity.id}`)
    let oldWrites = 0
    const countWrite = request => {
      if (request.method() === 'POST' && /\/(void|correction)$/.test(new URL(request.url()).pathname)) oldWrites++
    }
    page.on('request', countWrite)
    await dialog.getByRole('button', { name: kind === 'void' ? '作废' : '建立更正', exact: true }).click()
    await dialog.waitFor({ state: 'hidden' })
    await page.waitForLoadState('networkidle')
    page.off('request', countWrite)
    const afterOld = (await json(owner, 'GET', probeExpenses)).items
    assert.equal(afterOld.find(item => item.expenseId === probeExpense.expenseId).status, kind === 'void' ? 'APPROVED' : 'VOIDED')
    assert.equal(afterOld.filter(item => item.correctsExpenseId === probeExpense.expenseId).length, 0)
    await page.getByRole('link', { name: '活动管理', exact: true }).click()
    await page.getByRole('button', { name: probe.name, exact: true }).click()
    await page.getByRole('tab', { name: '经费', exact: true }).click()
    await openConfirmation()
    const fresh = page.waitForResponse(response => response.request().method() === 'POST'
      && response.url().endsWith(`/${probeExpense.expenseId}/${kind}`))
    await dialog.getByRole('button', { name: kind === 'void' ? '作废' : '建立更正', exact: true }).click()
    assert.ok((await fresh).ok())
    await page.waitForLoadState('networkidle')
    const afterFresh = (await json(owner, 'GET', probeExpenses)).items
    assert.equal(afterFresh.find(item => item.expenseId === probeExpense.expenseId).status, 'VOIDED')
    assert.equal(afterFresh.filter(item => item.correctsExpenseId === probeExpense.expenseId).length, kind === 'correction' ? 1 : 0)
    staleExpenseConfirmations.push({ kind, oldWrites, originalStateKept: true, freshConfirmation: true })
  }
  console.log(JSON.stringify({ staleExpenseConfirmations }))
  assert.ok(staleExpenseConfirmations.every(result => result.oldWrites === 0), '离开经费页面后的作废／更正确认不得发出写请求')
  await page.goto(detailPath, { waitUntil: 'networkidle' })

  // 登记弹窗：取消或离开时保留草稿，确认放弃后重开为空。
  await page.getByRole('button', { name: '登记支出' }).click()
  const createDialog = page.getByRole('dialog', { name: '登记支出' })
  await createDialog.getByPlaceholder('例如：横幅制作').fill('未保存支出')
  await createDialog.getByRole('button', { name: '取消', exact: true }).click()
  await page.locator('.el-message-box').getByText('未保存的修改').waitFor()
  await page.locator('.el-message-box').getByRole('button', { name: '继续编辑' }).click()
  assert.equal(await createDialog.getByPlaceholder('例如：横幅制作').inputValue(), '未保存支出')
  await createDialog.locator('.el-dialog__headerbtn').click()
  await page.locator('.el-message-box').getByRole('button', { name: '放弃修改' }).click()
  await createDialog.waitFor({ state: 'hidden' })
  await page.getByRole('button', { name: '登记支出' }).click()
  assert.equal(await createDialog.getByPlaceholder('例如：横幅制作').inputValue(), '')
  await createDialog.getByPlaceholder('例如：横幅制作').fill('路由草稿')
  const listPath = `/org/${org.id}/activities`
  await page.evaluate((target) => { void document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(target) }, listPath)
  await page.locator('.el-message-box').getByText('未保存的修改').waitFor()
  await page.locator('.el-message-box').getByRole('button', { name: '继续编辑' }).click()
  assert.equal(new URL(page.url()).pathname, `/org/${org.id}/activities/${activity.id}`)
  assert.equal(await createDialog.getByPlaceholder('例如：横幅制作').inputValue(), '路由草稿')
  await page.evaluate((target) => { void document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(target) }, listPath)
  await page.locator('.el-message-box').last().getByRole('button', { name: '离开' }).click()
  await page.waitForURL((url) => url.pathname === listPath)
  await page.goto(detailPath, { waitUntil: 'networkidle' })

  // 2. 首次登记已写入后替换响应为 503，模拟结果未知；原键重试不得多出一笔。
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '登记支出' }).click()
  const mobileDialog = page.getByRole('dialog', { name: '登记支出' })
  const rect = await mobileDialog.boundingBox()
  assert.ok(rect && rect.x >= 0 && rect.x + rect.width <= 390, '手机登记弹窗应完整落在视口内')
  await mobileDialog.getByPlaceholder('例如：横幅制作').fill('场地费')
  await mobileDialog.locator('input[role="spinbutton"]').fill('60')
  await mobileDialog.getByPlaceholder('没有票据时必填').fill('供应商未开票')
  const expenseUrl = new URL(expensesPath, base).href
  const failOnce = async (route) => {
    if (route.request().method() === 'POST' && route.request().url() === expenseUrl) {
      const written = await route.fetch()
      assert.ok(written.ok(), `模拟响应丢失前须先真实写入：${written.status()}`)
      await route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({ status: 503, code: 'TEST_UNAVAILABLE', detail: '模拟经费保存失败' }) })
    } else await route.continue()
  }
  await page.route(expenseUrl, failOnce)
  await mobileDialog.getByRole('button', { name: '保存', exact: true }).click()
  await mobileDialog.getByRole('alert').getByText('模拟经费保存失败').waitFor()
  assert.equal(await mobileDialog.getByPlaceholder('例如：横幅制作').inputValue(), '场地费')
  assert.equal(await mobileDialog.getByPlaceholder('例如：横幅制作').isDisabled(), true, '结果未知时应冻结原输入')
  await mobileDialog.getByRole('button', { name: '核对保存结果' }).waitFor()
  await page.unroute(expenseUrl, failOnce)
  await mobileDialog.getByRole('button', { name: '核对保存结果' }).click()
  await mobileDialog.waitFor({ state: 'hidden' })
  const afterRetryItems = (await json(owner, 'GET', expensesPath)).items
  const afterRetryCount = afterRetryItems.length
  assert.equal(afterRetryCount, 1, '响应丢失后重试不应重复登记')
  const siteExpenseId = afterRetryItems[0].expenseId
  await page.getByText('场地费', { exact: true }).waitFor()
  await page.setViewportSize({ width: 1440, height: 1000 })

  async function addExpense(purpose, amount) {
    await page.getByRole('button', { name: '登记支出' }).click()
    await page.locator('.el-dialog').getByPlaceholder('例如：横幅制作').fill(purpose)
    await page.locator('.el-dialog').locator('input[role="spinbutton"]').fill(String(amount))
    await page.locator('.el-dialog').getByPlaceholder('没有票据时必填').fill('供应商未开票')
    await page.locator('.el-dialog').getByRole('button', { name: '保存' }).click()
    await page.waitForSelector(`text=${purpose}`, { timeout: 15000 })
  }
  await addExpense('餐饮费', 60)
  const afterCreateBody = await page.locator('body').innerText()

  // 草稿上传票据；待确认与已确认时仍应能独立查看，不能依赖编辑入口。
  await page.locator('.expense-table .el-table__row').filter({ hasText: '场地费' })
    .getByRole('button', { name: '编辑', exact: true }).click()
  const edit = page.getByRole('dialog', { name: '编辑支出', exact: true })
  const voucherPath = `${expensesPath}/${siteExpenseId}/attachments`
  const voucherUrl = new URL(voucherPath, base).href
  const voucherResultPath = expensesPath.replace(/\/expenses$/, '/attachments/by-request-key')
  let originalVoucherKey = ''
  const loseVoucherResponse = async (route) => {
    if (route.request().method() === 'POST' && route.request().url() === voucherUrl) {
      originalVoucherKey = route.request().headers()['idempotency-key']
      const written = await route.fetch()
      assert.ok(written.ok(), `模拟凭证响应丢失前须先真实写入：${written.status()}`)
      await route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({ status: 503, code: 'TEST_UNAVAILABLE', detail: '模拟凭证上传失败' }) })
    } else await route.continue()
  }
  await page.route(voucherUrl, loseVoucherResponse)
  await edit.locator('input[type=file]').setInputFiles({ name: '场地票据.png', mimeType: 'image/png', buffer: PNG })
  await edit.getByRole('alert').getByText('模拟凭证上传失败').waitFor()
  await edit.getByRole('button', { name: '取消', exact: true }).click()
  await page.locator('.el-message-box').last().getByRole('button', { name: '继续编辑' }).click()
  await edit.getByRole('button', { name: '核对上传结果' }).waitFor()
  await page.unroute(voucherUrl, loseVoucherResponse)
  const voucherRetryResponse = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === voucherResultPath
      && url.searchParams.get('requestKey') === originalVoucherKey
      && response.request().method() === 'GET'
  })
  await edit.getByRole('button', { name: '核对上传结果' }).click()
  assert.equal((await voucherRetryResponse).status(), 200)
  const afterVoucherRetryCount = (await json(owner, 'GET', voucherPath)).items.length
  assert.equal(afterVoucherRetryCount, 1, '凭证响应丢失后重试不应重复上传')
  await edit.getByRole('button', { name: '场地票据.png', exact: true }).waitFor()
  await edit.getByPlaceholder('例如：横幅制作').fill('未保存编辑')
  await edit.getByRole('button', { name: '取消', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '继续编辑' }).click()
  assert.equal(await edit.getByPlaceholder('例如：横幅制作').inputValue(), '未保存编辑')
  await edit.locator('.el-dialog__headerbtn').click()
  await page.locator('.el-message-box').getByRole('button', { name: '放弃修改' }).click()
  await edit.waitFor({ state: 'hidden' })

  // 3. 提交两笔支出 → 待确认合计 120.00
  const rows = page.locator('.expense-table .el-table__row')
  for (let i = 0; i < 2; i += 1) {
    await rows.nth(i).getByRole('button', { name: '提交' }).click()
    await page.waitForTimeout(400)
  }
  await page.waitForTimeout(600)
  const pendingSummary = await json(owner, 'GET', `${expensesPath}/summary`)

  // 4. 确认第一笔（60 ≤ 100 无需说明），第二笔触发超支弹框
  // 列表按发生日期与 id 倒序，两笔同日，因此按用途文字定位行，不依赖顺序
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForSelector('text=场地费', { timeout: 15000 })
  const siteRow = page.locator('.expense-table .el-table__row').filter({ hasText: '场地费' }).first()
  await siteRow.getByRole('button', { name: '确认' }).click()
  await page.waitForTimeout(800)
  await siteRow.getByRole('button', { name: '查看凭证', exact: true }).click()
  const voucherDialog = page.getByRole('dialog', { name: '支出凭证', exact: true })
  const downloaded = page.waitForEvent('download')
  await voucherDialog.getByRole('button', { name: '场地票据.png', exact: true }).click()
  const file = await downloaded
  assert.deepEqual(await readFile(await file.path()), PNG, '下载票据字节应与上传一致')
  await voucherDialog.locator('.el-dialog__headerbtn').click()
  const afterFirstConfirm = await json(owner, 'GET', `${expensesPath}/summary`)
  const secondRow = page.locator('.expense-table .el-table__row').filter({ hasText: '餐饮费' }).first()
  await secondRow.getByRole('button', { name: '确认' }).click()
  await page.waitForSelector('text=超出预算，需要说明', { timeout: 15000 })
  const overBudgetDialogVisible = await page.locator('.el-dialog').filter({ hasText: '超出预算，需要说明' }).isVisible()
  const overBudgetShot = fileURLToPath(new URL('../验证截图/P5-超支说明.png', import.meta.url))
  await page.screenshot({ path: overBudgetShot, fullPage: true })
  shots.push(overBudgetShot)

  // 5. 空说明被拦下；填写说明后确认成功，余额为负
  const overDialog = page.locator('.el-dialog').filter({ hasText: '超出预算，需要说明' })
  await overDialog.getByRole('button', { name: '确认支出' }).click()
  await page.waitForTimeout(400)
  const blockedByEmptyNote = (await page.locator('body').innerText()).includes('超出预算时必须填写超支说明')
  await overDialog.getByPlaceholder('请填写超支说明（必填）').fill('实际人数超出预期，超支部分自筹')
  await overDialog.getByRole('button', { name: '确认支出' }).click()
  await page.waitForSelector('text=实际人数超出预期', { timeout: 15000 })
  const finalSummary = await json(owner, 'GET', `${expensesPath}/summary`)
  const finalBody = await page.locator('body').innerText()
  const finalShot = fileURLToPath(new URL('../验证截图/P5-经费区块.png', import.meta.url))
  await page.screenshot({ path: finalShot, fullPage: true })
  shots.push(finalShot)

  // 6. 作废 + 更正登记：统计只按有效明细
  const approvedRow = page.locator('.expense-table .el-table__row').filter({ hasText: '场地费' }).first()
  await approvedRow.getByRole('button', { name: '作废' }).click()
  await page.waitForSelector('.el-message-box', { timeout: 10000 })
  await page.locator('.el-message-box__input input').fill('重复登记')
  await page.locator('.el-message-box').getByRole('button', { name: '作废' }).click()
  await page.waitForTimeout(800)
  const afterVoid = await json(owner, 'GET', `${expensesPath}/summary`)

  const voidedRow = page.locator('.expense-table .el-table__row').filter({ hasText: '场地费' }).first()
  const correctionPath = `${expensesPath}/${siteExpenseId}/correction`
  const correctionUrl = new URL(correctionPath, base).href
  const loseCorrectionResponse = async (route) => {
    if (route.request().method() === 'POST' && route.request().url() === correctionUrl) {
      const written = await route.fetch()
      assert.ok(written.ok(), `模拟更正响应丢失前须先真实写入：${written.status()}`)
      await route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({ status: 503, code: 'TEST_UNAVAILABLE', detail: '模拟更正登记失败' }) })
    } else await route.continue()
  }
  await page.route(correctionUrl, loseCorrectionResponse)
  await voidedRow.getByRole('button', { name: '更正登记' }).click()
  await page.waitForSelector('.el-message-box', { timeout: 10000 })
  await page.locator('.el-message-box').getByRole('button', { name: '建立更正' }).click()
  await page.getByRole('alert').getByText('模拟更正登记失败').waitFor()
  await page.evaluate((target) => { void document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(target) }, listPath)
  await page.locator('.el-message-box').last().getByRole('button', { name: '继续编辑' }).click()
  assert.equal(new URL(page.url()).pathname, `/org/${org.id}/activities/${activity.id}`)
  await page.unroute(correctionUrl, loseCorrectionResponse)
  const correctionRetryResponse = page.waitForResponse((response) => response.url() === correctionUrl
    && response.request().method() === 'POST')
  const recheckCorrection = voidedRow.getByRole('button', { name: '核对更正结果' })
  if (await recheckCorrection.count()) {
    await recheckCorrection.click()
  } else {
    await voidedRow.getByRole('button', { name: '更正登记' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '建立更正' }).click()
  }
  assert.equal((await correctionRetryResponse).status(), 201)
  const correctionRows = (await json(owner, 'GET', expensesPath)).items
    .filter((x) => x.purpose.includes('更正'))
  assert.equal(correctionRows.length, 1, '更正响应丢失后重试不应重复登记')
  const correctionId = correctionRows[0].expenseId
  await json(owner, 'POST', `${expensesPath}/${correctionId}/submit`,
    { version: correctionRows[0].version })
  const correctionVersion = (await json(owner, 'GET', expensesPath)).items
    .find((x) => x.expenseId === correctionId).version
  // 更正后合计仍超过预算：不填说明必须被拒（与首笔确认同一规则）
  const correctionWithoutNote = await call(owner, 'POST', `${expensesPath}/${correctionId}/confirm`,
    { version: correctionVersion, overBudgetNote: null })
  const correctionWithoutNoteCode = (await correctionWithoutNote.json().catch(() => null))?.code
  await json(owner, 'POST', `${expensesPath}/${correctionId}/confirm`,
    { version: correctionVersion, overBudgetNote: '更正后仍超预算，说明见活动记录' })
  const afterCorrection = await json(owner, 'GET', `${expensesPath}/summary`)

  // 7. 活动负责人（非组织负责人）可登记、界面无确认按钮；普通成员看不到经费区块
  const memberPage = await member.newPage({ viewport: { width: 1440, height: 1000 } })
  const memberErrors = []
  memberPage.on('pageerror', (e) => memberErrors.push(e.message))
  await memberPage.goto(detailPath, { waitUntil: 'networkidle' })
  await memberPage.waitForSelector('text=经费', { timeout: 15000 })
  const memberBody = await memberPage.locator('body').innerText()
  const memberRowButtons = {
    register: await memberPage.getByRole('button', { name: '登记支出' }).count(),
    confirm: await memberPage.locator('.expense-table .el-table__row').getByRole('button', { name: '确认' }).count(),
    reject: await memberPage.locator('.expense-table .el-table__row').getByRole('button', { name: '驳回' }).count(),
    voidButton: await memberPage.locator('.expense-table .el-table__row').getByRole('button', { name: '作废' }).count(),
    submit: await memberPage.locator('.expense-table .el-table__row').getByRole('button', { name: '提交' }).count(),
  }

  // 8. 窄屏：经费区块与统计卡在各宽度下不横向溢出
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
  const narrowShot = fileURLToPath(new URL('../验证截图/P5-经费窄屏.png', import.meta.url))
  await page.setViewportSize({ width: 390, height: 900 })
  await page.waitForTimeout(200)
  await page.screenshot({ path: narrowShot, fullPage: true })
  shots.push(narrowShot)

  // 归档只读仍须能下载已确认票据；通过真实 API 准备归档状态。
  const ap = `/api/organizations/${org.id}/activities/${activity.id}`
  const step = async action => json(owner, 'POST', `${ap}/${action}`, { version: (await json(owner, 'GET', ap)).version })
  await step('start-execution')
  await step('finish-execution')
  await json(owner, 'PUT', `${ap}/summary`, { summary: '经费已核对，归档保留票据。', version: (await json(owner, 'GET', ap)).version })
  await step('summary/confirm')
  await step('archive')
  await page.goto(detailPath, { waitUntil: 'networkidle' })
  assert.equal(await page.getByRole('button', { name: '登记支出', exact: true }).count(), 0)
  assert.equal(await page.getByRole('button', { name: '更正登记', exact: true }).count(), 0)
  await page.locator('.expense-table .el-table__row').filter({ hasText: '场地费' }).filter({ hasText: '已作废' })
    .getByRole('button', { name: '查看凭证', exact: true }).click()
  const archivedDownload = page.waitForEvent('download')
  await page.getByRole('dialog', { name: '支出凭证', exact: true })
    .getByRole('button', { name: '场地票据.png', exact: true }).click()
  assert.deepEqual(await readFile(await (await archivedDownload).path()), PNG)
  assert.equal(await page.getByRole('dialog', { name: '支出凭证', exact: true }).getByRole('button', { name: '上传凭证' }).count(), 0)
  const voucherBox = await page.getByRole('dialog', { name: '支出凭证', exact: true }).boundingBox()
  assert.ok(voucherBox.x >= 0 && voucherBox.x + voucherBox.width <= 390, '手机凭证弹窗应完整显示')
  await page.screenshot({ path: fileURLToPath(new URL('../验证截图/P5-归档票据查看.png', import.meta.url)), fullPage: true, animations: 'disabled' })
  assert.equal(pendingSummary.pendingAmount, '120.00')
  assert.equal(afterFirstConfirm.approvedAmount, '60.00')
  assert.equal(finalSummary.balance, '-20.00')
  assert.equal(afterVoid.approvedAmount, '60.00')
  assert.equal(afterCorrection.approvedAmount, '120.00')
  assert.ok(overBudgetDialogVisible && blockedByEmptyNote)
  assert.equal(correctionWithoutNote.status(), 409)
  assert.ok(memberRowButtons.register > 0)
  assert.equal(memberRowButtons.confirm + memberRowButtons.reject + memberRowButtons.voidButton, 0)
  assert.ok(widths.every(w => w.overflowPx <= 0))
  assert.deepEqual(errors, [])
  assert.deepEqual(memberErrors, [])

  console.log(JSON.stringify({
    check: 'P5 经费真机核对',
    delayedSessionResponses,
    staleExpenseConfirmations,
    freshActions: { expenseSubmitted: true, notificationReadOne: true, notificationsReadAll: true },
    voucher: { uploaded: true, approvedDownloadMatches: true, archivedDownloadMatches: true },
    createRetry: { writtenBeforeLostResponse: true, frozenUntilResolved: true, countAfterRetry: afterRetryCount },
    voucherRetry: { writtenBeforeLostResponse: true, countAfterRetry: afterVoucherRetryCount },
    correctionRetry: { writtenBeforeLostResponse: true, countAfterRetry: correctionRows.length },
    sectionRendered: initialBody.includes('活动预算（元）') && initialBody.includes('预算余额（元）'),
    initialSummaryZero: initialBody.includes('0.00'),
    afterCreateDraftShown: afterCreateBody.includes('草稿'),
    pendingSummary,
    afterFirstConfirm,
    overBudgetDialog: { visible: overBudgetDialogVisible, blockedByEmptyNote },
    finalSummary,
    balanceNegativeShown: finalBody.includes('-'),
    afterVoid,
    afterCorrection,
    correction: {
      created: correctionRows.length === 1,
      linked: correctionRows[0].correctsExpenseId !== null,
      confirmWithoutNoteStatus: correctionWithoutNote.status(),
      confirmWithoutNoteCode: correctionWithoutNoteCode,
    },
    activityOwnerView: {
      seesExpenseSection: memberBody.includes('活动预算（元）'),
      canRegister: memberRowButtons.register > 0,
      // 计划书 7.4：活动负责人非组织负责人不能确认／驳回／作废，界面不应出现这些按钮
      confirmButtons: memberRowButtons.confirm,
      rejectButtons: memberRowButtons.reject,
      voidButtons: memberRowButtons.voidButton,
      submitButtons: memberRowButtons.submit,
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
