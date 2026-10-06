// S5 逐页核对：登录／注册／恢复／账号／组织设置／公开参与。
// 默认模式只读；--account-security 全部替身；--account-security-live 会写 activity_test。
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { mkdir, writeFile } from 'node:fs/promises'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

const base = process.env.UI_BASE || 'http://127.0.0.1:5174'
const out = new URL('../验证截图/UI标准页/', import.meta.url)
await mkdir(out, { recursive: true })
if (process.argv.includes('--account-security-live') && process.env.AUDIT_TEST_DATABASE !== 'activity_test') {
  throw new Error('先从后端日志核实连接 activity_test，再设置 AUDIT_TEST_DATABASE=activity_test')
}
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const results = {
  checks: [],
  screenshots: [],
  widths: [],
  limitations: [
    '加载／空／失败反例为浏览器响应替身，不代表业务验收',
    '未执行注册、恢复、改密、重签与配置保存等写操作；未调用任何模型服务',
  ],
}
let release = null
const gate = () => new Promise((resolve) => { release = resolve })

// 真实测试后端提交成功后替换页面响应，账号、密码和恢复码只留在本次进程内存。
if (process.argv.includes('--account-security-live')) {
  const context = await browser.newContext({ baseURL: base, viewport: { width: 390, height: 844 } })
  const verifier = await browser.newContext({ baseURL: base })
  const page = await context.newPage()
  const errors = []
  const checks = []
  const stamp = Date.now()
  const username = `a11_${stamp}`
  const password = `Audit${stamp}a`
  const changedPassword = `Audit${stamp}b`
  const recoveredPassword = `Audit${stamp}c`
  page.on('pageerror', error => errors.push(error.message))
  const check = (name, passed) => checks.push({ name, passed })
  async function call(method, path, data) {
    const csrf = await (await verifier.request.get('/api/auth/csrf')).json()
    return verifier.request.fetch(path, { method, data, headers: { [csrf.headerName]: csrf.token } })
  }
  async function loseNextResponse(path, expectedStatus) {
    let resolve, reject
    const written = new Promise((yes, no) => { resolve = yes; reject = no })
    void written.catch(() => undefined)
    const pattern = `**${path}`
    let consumed = false
    const handler = async route => {
      if (consumed) { await route.continue(); return }
      consumed = true
      try {
        const response = await route.fetch()
        assert.equal(response.status(), expectedStatus, `${path} 未真正提交成功`)
        const result = expectedStatus === 204 ? null : await response.json()
        await route.fulfill({ response, status: 503, json: { status: 503, code: 'AUDIT_RESPONSE_LOST' } })
        resolve(result)
      } catch (error) { reject(error); await route.abort().catch(() => undefined) }
    }
    await page.route(pattern, handler)
    return { written }
  }
  async function loginWith(value, destination = '/') {
    await page.getByPlaceholder('注册时设置的用户名').fill(username)
    await page.getByPlaceholder('请输入密码').fill(value)
    const reply = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/login' && response.request().method() === 'POST')
    await page.getByRole('button', { name: '进入工作区' }).click()
    assert.equal((await reply).status(), 204, '用已确认密码重新登录应一次成功')
    await page.waitForURL(url => url.pathname === destination)
    if (destination === '/') await page.getByRole('heading', { name: '我的工作区' }).waitFor()
    const me = await context.request.get('/api/auth/me')
    assert.equal(me.status(), 200)
    assert.equal((await me.json()).username, username)
  }
  async function confirmCode() {
    const dialog = page.getByRole('dialog', { name: '请保存你的恢复码' })
    await dialog.waitFor()
    const code = (await dialog.locator('.recovery-code-dialog__code').textContent()).trim()
    await dialog.getByText('我已将恢复码保存在安全的地方', { exact: true }).click()
    await dialog.getByRole('button', { name: '确认已保存' }).click()
    await dialog.waitFor({ state: 'hidden' })
    return code
  }
  try {
    await page.goto('/register', { waitUntil: 'networkidle' })
    const registerForm = page.locator('.auth-content form')
    for (const [index, value] of [username, password, password].entries()) await registerForm.locator('input').nth(index).fill(value)
    const { written: registered } = await loseNextResponse('/api/auth/register', 201)
    await page.getByRole('button', { name: '注册', exact: true }).click()
    const lostRegistration = await registered
    await page.getByText(/注册结果尚未确认/).waitFor()
    await page.getByRole('button', { name: '注册', exact: true }).click()
    await page.getByText(/该用户名已被注册/).waitFor()
    check('register:committedThen503', Boolean(lostRegistration.id))
    check('register:replayKeepsLoginGuidance', (await page.locator('.auth-error').textContent()).includes('登录核对'))
    await page.locator('a[href="/login"]').click()
    await page.getByRole('heading', { name: '继续，把事情做好。' }).waitFor()
    const { written: loggedIn } = await loseNextResponse('/api/auth/login', 204)
    await page.getByPlaceholder('注册时设置的用户名').fill(username)
    await page.getByPlaceholder('请输入密码').fill(password)
    await page.getByRole('button', { name: '进入工作区' }).click()
    await loggedIn
    await page.locator('.auth-error').waitFor()
    check('login:committedThen503', (await context.request.get('/api/auth/me')).status() === 200)
    check('login:unknownResultExplained', (await page.locator('.auth-error').textContent()).includes('结果尚未确认'))
    await loginWith(password)
    check('login:manualRetryRestoresPage', true)
    check('register:originalCredentialsLogin', true)

    await page.getByRole('button', { name: '修改密码', exact: true }).click()
    const passwordForm = page.locator('.el-dialog:visible form')
    for (const [index, value] of [password, changedPassword, changedPassword].entries()) await passwordForm.locator('input').nth(index).fill(value)
    const { written: changed } = await loseNextResponse('/api/auth/password', 204)
    await page.getByRole('button', { name: '确认修改', exact: true }).click()
    await changed
    await page.getByText(/修改结果尚未确认/).waitFor()
    await page.getByRole('button', { name: '确认修改', exact: true }).click()
    await page.getByText(/当前密码不正确/).waitFor()
    check('password:replayKeepsLoginGuidance', (await page.locator('.account-page__dialog-error').textContent()).includes('登录核对'))
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.waitForURL(url => url.pathname === '/login')
    await loginWith(changedPassword)
    check('password:newPasswordLogin', true)

    await page.getByRole('button', { name: '重签恢复码', exact: true }).click()
    const reissueForm = page.locator('.el-dialog:visible form')
    await reissueForm.locator('input').fill(changedPassword)
    const { written: reissued } = await loseNextResponse('/api/auth/recovery/reissue', 200)
    await page.getByRole('button', { name: '重签', exact: true }).click()
    const lostReissue = await reissued
    await page.getByText(/重签结果尚未确认/).waitFor()
    await page.getByRole('button', { name: '重签', exact: true }).click()
    const currentCode = await confirmCode()
    check('reissue:manualReplacementIsNew', currentCode !== lostReissue.recoveryCode)
    for (const code of [lostRegistration.recoveryCode, lostReissue.recoveryCode]) {
      const rejected = await call('POST', '/api/auth/recover', { username, recoveryCode: code, newPassword: recoveredPassword })
      assert.equal(rejected.status(), 401)
    }
    check('reissue:previousCodesRejected', true)

    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.waitForURL(url => url.pathname === '/login')
    await page.locator('a[href="/recover"]').click()
    await page.getByRole('heading', { name: '用恢复码重新取得控制权。' }).waitFor()
    const recoveryForm = page.locator('.auth-content form')
    for (const [index, value] of [username, currentCode, recoveredPassword, recoveredPassword].entries()) await recoveryForm.locator('input').nth(index).fill(value)
    const { written: recovered } = await loseNextResponse('/api/auth/recover', 200)
    await page.getByRole('button', { name: '恢复账号', exact: true }).click()
    await recovered
    await page.getByText(/恢复结果尚未确认/).waitFor()
    await page.getByRole('button', { name: '恢复账号', exact: true }).click()
    await page.getByText(/账号或恢复码不正确/).waitFor()
    check('recover:replayKeepsLoginGuidance', (await page.locator('.auth-error').textContent()).includes('登录核对'))
    await page.locator('a[href="/login"]').click()
    await page.getByRole('heading', { name: '继续，把事情做好。' }).waitFor()
    await loginWith(recoveredPassword)
    check('recover:newPasswordLogin', true)
    await page.getByRole('button', { name: '重签恢复码', exact: true }).click()
    await page.locator('.el-dialog:visible input').fill(recoveredPassword)
    await page.getByRole('button', { name: '重签', exact: true }).click()
    await confirmCode()
    check('recover:freshCodeConfirmed', true)

    // 三个正式退出入口：真实退出已完成，但页面仅收到 503。
    const csrf = await (await context.request.get('/api/auth/csrf')).json()
    const createdOrg = await context.request.post('/api/organizations', { data: { name: `退出核对_${stamp}` },
      headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() } })
    assert.equal(createdOrg.status(), 201)
    const organization = await createdOrg.json()
    for (const [entry, path] of [['account', '/'], ['public', '/public/activities'], ['organization', `/org/${organization.id}`]]) {
      await page.setViewportSize({ width: 1280, height: 900 })
      await page.goto(path, { waitUntil: 'networkidle' })
      const clickLogout = async () => {
        if (entry !== 'account') await page.getByRole('button', { name: '账号菜单', exact: true }).click()
        await page.getByRole(entry === 'account' ? 'button' : 'menuitem', { name: '退出登录', exact: true }).click()
      }
      // 请求未转发时，真实会话仍有效；核对失败也不能当作已退出或跳走。
      for (const failure of ['server', 'transport']) {
        const logoutFailure = route => failure === 'transport' ? route.abort('failed')
          : route.fulfill({ status: 503, json: { status: 503, code: 'UNAVAILABLE' } })
        const probeFailure = route => route.fulfill({ status: 503, json: { status: 503, code: 'UNAVAILABLE' } })
        await page.route('**/api/auth/logout', logoutFailure)
        if (failure === 'transport') await page.route('**/api/auth/me', probeFailure)
        const probe = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/me')
        await clickLogout()
        assert.equal((await probe).status(), failure === 'transport' ? 503 : 200)
        await page.getByText('退出结果尚未确认，请检查网络后再次退出。', { exact: true }).first().waitFor()
        check(`logout:${entry}:${failure}:keepsPageAndSession`, new URL(page.url()).pathname === path
          && (await context.request.get('/api/auth/me')).status() === 200)
        await page.unroute('**/api/auth/logout', logoutFailure)
        if (failure === 'transport') await page.unroute('**/api/auth/me', probeFailure)
      }
      const { written: loggedOut } = await loseNextResponse('/api/auth/logout', 204)
      await clickLogout()
      await loggedOut
      await page.waitForTimeout(700)
      check(`logout:${entry}:serverConfirmedGuest`, (await context.request.get('/api/auth/me')).status() === 401)
      const cleared = entry === 'public' ? await page.getByRole('button', { name: '登录', exact: true }).isVisible()
        : new URL(page.url()).pathname === '/login' && await page.locator('.account-page,.workspace').count() === 0
      check(`logout:${entry}:clearsProtectedState`, cleared)
      assert.ok(cleared, `${entry} 退出已完成，页面仍保留旧身份`)
      if (entry === 'public') await page.getByRole('button', { name: '登录', exact: true }).click()
      await loginWith(recoveredPassword, entry === 'public' ? '/public/activities' : '/')
    }
    assert.deepEqual(errors, [])
    const result = { mode: 'account-security-live', runStamp: stamp, accountId: lostRegistration.id, checks, errors,
      limitations: ['注册、登录、改密、重签、恢复及退出先由真实 activity_test 后端完成，再替换页面响应为 503；不代表自然网络时序',
        '退出前的服务／网络失败及核对 503 为页面替身，未转发退出；原会话与正常重试仍用真实后端',
        '重签是用户明确发起的新一次替换，不恢复此前响应明文；未调用模型或验证自然闲置、生产环境'] }
    await writeFile(new URL('S5账号真实结果.json', out), JSON.stringify(result, null, 2))
    console.log(JSON.stringify(result, null, 2))
    assert.ok(checks.every(check => check.passed), '账号真实结果未知恢复路径仍有误导提示')
  } catch (error) {
    console.log(JSON.stringify({ mode: 'account-security-live', checks, errors, path: new URL(page.url()).pathname }))
    throw error
  } finally { await browser.close() }
  process.exit(0)
}

// 账号安全专项全部使用 API 替身；虚构凭据不发往真实后端，也不输出到结果。
if (process.argv.includes('--account-security')) {
  const context = await browser.newContext({ baseURL: base, viewport: { width: 390, height: 844 } })
  const page = await context.newPage()
  const errors = []
  const checks = []
  page.on('pageerror', error => errors.push(error.message))
  let authenticated = true
  let held
  let activeCode = ''
  await context.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (held?.path === path && route.request().method() === 'POST') {
      const number = ++held.calls
      const code = `fixture-code-${number}`
      activeCode = code
      if (number === 1) held.signal()
      if (number === 1 || path.endsWith('/login')) await held.gate
      if (held.failure === 'transport') { await route.abort('failed'); return }
      if (path.endsWith('/login') && !held.failure) authenticated = true
      await route.fulfill(held.failure
        ? { status: held.failure === 'session' ? 401 : held.failure, json: { status: held.failure === 'session' ? 401 : held.failure,
          code: held.failure === 'session' ? 'AUTH_REQUIRED' : held.failure === 401 ? 'AUTH_INVALID_CREDENTIALS' : 'UNAVAILABLE', detail: '服务暂不可用。' } }
        : path.endsWith('/password') || path.endsWith('/login') ? { status: 204 }
          : { status: path.endsWith('/register') ? 201 : 200, json: { id: '1', username: 'fixture_user', recoveryCode: code } })
      return
    }
    if (path === '/api/auth/me') {
      await route.fulfill(authenticated ? { json: { id: '1', username: 'fixture_user' } }
        : { status: 401, json: { status: 401, code: 'AUTH_REQUIRED' } })
    } else if (path === '/api/auth/login') {
      authenticated = true
      await route.fulfill({ status: 204 })
    } else if (path === '/api/auth/csrf') {
      await route.fulfill({ json: { token: 'fixture-csrf', headerName: 'X-CSRF-TOKEN' } })
    } else if (path === '/api/me/notifications') {
      await route.fulfill({ json: { items: [], total: 0, unread: 0 } })
    } else if (['/api/me/registrations', '/api/me/organizations'].includes(path)) {
      await route.fulfill({ json: { items: [], total: 0 } })
    } else if (path.endsWith('/unread-count')) {
      await route.fulfill({ json: { count: 0 } })
    } else {
      await route.fulfill({ json: [] })
    }
  })
  const hold = path => {
    let signal, release
    const started = new Promise(resolve => { signal = resolve })
    const gate = new Promise(resolve => { release = resolve })
    held = { path, calls: 0, signal, gate, failure: false }
    return { started, release }
  }
  const check = (name, passed) => checks.push({ name, passed })
  const submitAgain = form => form.evaluate(element => {
    element.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    element.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  })
  try {
    authenticated = false
    await page.goto('/login', { waitUntil: 'networkidle' })
    const loginForm = page.locator('.auth-content form')
    const invalidLogin = hold('/api/auth/login')
    await submitAgain(loginForm)
    await loginForm.locator('.el-form-item__error').first().waitFor()
    check('login:validationUnlocks', held.calls === 0 && await loginForm.locator('input').evaluateAll(inputs => inputs.every(input => !input.disabled)))
    invalidLogin.release()
    await loginForm.locator('input').nth(0).fill('fixture_user')
    await loginForm.locator('input').nth(1).fill('Fixture123')
    for (const failure of [401, 503, 'transport']) {
      const failedLogin = hold('/api/auth/login')
      held.failure = failure
      await submitAgain(loginForm)
      await failedLogin.started
      failedLogin.release()
      const error = page.locator('.auth-error')
      await error.waitFor()
      check(`login:${failure}:failureUnlocks`, held.calls === 1 && await loginForm.locator('input').evaluateAll(inputs => inputs.every(input => !input.disabled)))
      check(`login:${failure}:failureKeepsDraft`, (await loginForm.locator('input').nth(0).inputValue()) === 'fixture_user'
        && (await loginForm.locator('input').nth(1).inputValue()) === 'Fixture123')
      if (failure !== 401) check(`login:${failure}:unknownResultExplained`, (await error.textContent()).includes('结果尚未确认'))
    }
    const loginPending = hold('/api/auth/login')
    await submitAgain(loginForm)
    await loginPending.started
    await submitAgain(loginForm)
    await page.waitForTimeout(150)
    check('login:oneRequest', held.calls === 1)
    check('login:fieldsLocked', await loginForm.locator('input').evaluateAll(inputs => inputs.every(input => input.disabled)))
    await page.locator('a[href="/register"]').click()
    await page.waitForTimeout(350)
    check('login:pendingRouteKept', new URL(page.url()).pathname === '/login')
    loginPending.release()
    await page.getByRole('heading', { name: '我的工作区' }).waitFor()
    check('login:successNavigation', new URL(page.url()).pathname === '/')
    assert.ok(checks.every(check => check.passed), '登录发送中存在重复请求或旧响应导航风险')
    for (const action of ['reissue', 'password', 'register', 'recover']) {
      console.log(`account-security: ${action}`)
      const isAccount = ['reissue', 'password'].includes(action)
      authenticated = isAccount
      await page.goto(isAccount ? '/' : '/login', { waitUntil: 'networkidle' })
      if (!isAccount) {
        await page.locator(`a[href="/${action}"]`).click()
        await page.getByRole('heading', { name: action === 'register' ? '开始，一起做活动。' : '用恢复码重新取得控制权。' }).waitFor()
      } else {
        await page.getByRole('button', { name: action === 'reissue' ? '重签恢复码' : '修改密码', exact: true }).click()
      }
      const form = page.locator(isAccount ? '.el-dialog:visible form' : '.auth-content form')
      const path = action === 'reissue' ? '/api/auth/recovery/reissue' : `/api/auth/${action}`
      const invalid = hold(path)
      await submitAgain(form)
      await form.locator('.el-form-item__error').first().waitFor()
      check(`${action}:validationUnlocks`, held.calls === 0 && await form.locator('input').evaluateAll(inputs => inputs.every(input => !input.disabled)))
      invalid.release()
      const values = action === 'reissue' ? ['Fixture123'] : action === 'password'
        ? ['Fixture123', 'Fixture456', 'Fixture456'] : action === 'register'
          ? ['fixture_user', 'Fixture123', 'Fixture123'] : ['fixture_user', 'fixture-old-code', 'Fixture456', 'Fixture456']
      for (let index = 0; index < values.length; index++) await form.locator('input').nth(index).fill(values[index])
      for (const failure of [401, 503, 'transport']) {
        const failed = hold(path)
        held.failure = failure
        await submitAgain(form)
        await failed.started
        failed.release()
        const error = page.locator(isAccount ? '.el-dialog:visible .account-page__dialog-error' : '.auth-error')
        await error.waitFor()
        check(`${action}:${failure}:failureUnlocks`, held.calls === 1 && await form.locator('input').evaluateAll(inputs => inputs.every(input => !input.disabled)))
        check(`${action}:${failure}:failureKeepsDraft`, JSON.stringify(await form.locator('input').evaluateAll(inputs => inputs.map(input => input.value))) === JSON.stringify(values))
        if (failure !== 401) {
          check(`${action}:${failure}:unknownResultExplained`, (await error.textContent()).includes('结果尚未确认'))
          assert.ok(checks.at(-1).passed, `${action} 结果未知提示不准确`)
        }
      }
      const pending = hold(path)
      await submitAgain(form)
      await pending.started
      await submitAgain(form)
      await page.waitForTimeout(150)
      check(`${action}:oneRequest`, held.calls === 1)
      check(`${action}:fieldsLocked`, await form.locator('input').evaluateAll(inputs => inputs.every(input => input.disabled)))
      check(`${action}:sendingUnloadWarns`, await page.evaluate(() => {
        const event = new Event('beforeunload', { cancelable: true })
        window.dispatchEvent(event)
        return event.defaultPrevented
      }))
      if (isAccount) {
        const cancel = page.getByRole('button', { name: '取消', exact: true })
        check(`${action}:cancelLocked`, await cancel.isDisabled())
        const box = await page.locator('.el-dialog:visible').boundingBox()
        check(`${action}:dialogFits390`, box.x >= 0 && box.x + box.width <= 390)
        await page.keyboard.press('Escape')
        await page.waitForTimeout(350)
        check(`${action}:sendingDialogKept`, await form.isVisible())
      } else {
        await page.evaluate(() => history.back())
        await page.waitForTimeout(350)
        check(`${action}:sendingRouteKept`, new URL(page.url()).pathname === `/${action}`)
        if (new URL(page.url()).pathname !== `/${action}`) { pending.release(); continue }
      }
      pending.release()
      if (action === 'password') {
        await page.locator('.el-dialog:visible').waitFor({ state: 'hidden' })
        continue
      }
      const codeDialog = page.getByRole('dialog', { name: '请保存你的恢复码' })
      await codeDialog.waitFor()
      const codeBox = await codeDialog.boundingBox()
      check(`${action}:codeFits390`, codeBox.x >= 0 && codeBox.x + codeBox.width <= 390)
      check(`${action}:latestCodeDisplayed`, (await codeDialog.locator('.recovery-code-dialog__code').textContent()).trim() === activeCode)
      check(`${action}:noCloseButton`, await codeDialog.locator('.el-dialog__headerbtn').count() === 0)
      await page.keyboard.press('Escape')
      await page.waitForTimeout(350)
      check(`${action}:escapeKeptCode`, await codeDialog.isVisible())
      assert.ok(checks.every(check => check.passed), `${action} 存在重复提交或恢复码丢失风险`)
      if (await codeDialog.isVisible()) {
        await page.screenshot({ path: fileURLToPath(new URL(`S5-账号安全-${action}-390.png`, out)), fullPage: true })
        check(`${action}:unconfirmedUnloadWarns`, await page.evaluate(() => {
          const event = new Event('beforeunload', { cancelable: true })
          window.dispatchEvent(event)
          return event.defaultPrevented
        }))
        if (!isAccount) {
          await page.evaluate(() => history.back())
          await page.waitForTimeout(150)
          check(`${action}:unconfirmedBackKept`, new URL(page.url()).pathname === `/${action}` && await codeDialog.isVisible())
        }
        check(`${action}:confirmationRequired`, await codeDialog.getByRole('button', { name: '确认已保存' }).isDisabled())
        await codeDialog.getByText('我已将恢复码保存在安全的地方', { exact: true }).click()
        await codeDialog.getByRole('button', { name: '确认已保存' }).click()
        await codeDialog.waitFor({ state: 'hidden' })
        if (!isAccount) {
          await page.waitForURL('**/login')
          check(`${action}:confirmedNavigation`, true)
        }
      }
    }
    // 已发送后由另一会话导致失效；应用卸载旧页后重登，迟到结果不得进入新页面。
    for (const failure of [false, 'session', 503]) {
      authenticated = true
      await page.goto('/', { waitUntil: 'networkidle' })
      await page.getByRole('button', { name: '重签恢复码', exact: true }).click()
      const form = page.locator('.el-dialog:visible form')
      await form.locator('input').fill('Fixture123')
      const old = hold('/api/auth/recovery/reissue')
      held.failure = failure
      await submitAgain(form)
      await old.started
      authenticated = false
      await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
      await page.waitForURL(url => url.pathname === '/login')
      await page.getByPlaceholder('注册时设置的用户名').fill('fixture_user')
      await page.getByPlaceholder('请输入密码').fill('Fixture123')
      await page.getByRole('button', { name: '进入工作区' }).click()
      await page.getByRole('heading', { name: '我的工作区' }).waitFor()
      old.release()
      await page.waitForTimeout(350)
      check(`stale:${failure || 'success'}:keepsNewSession`, new URL(page.url()).pathname === '/'
        && await page.getByRole('heading', { name: '我的工作区' }).isVisible()
        && await page.getByRole('dialog', { name: '请保存你的恢复码' }).count() === 0)
    }
    await page.getByRole('button', { name: '重签恢复码', exact: true }).click()
    await page.locator('.el-dialog:visible input').fill('Fixture123')
    const expired = hold('/api/auth/recovery/reissue')
    held.failure = 'session'
    await submitAgain(page.locator('.el-dialog:visible form'))
    await expired.started
    expired.release()
    await page.waitForURL(url => url.pathname === '/login')
    check('current401:clearsProtectedPage', await page.locator('.account-page').count() === 0)
    assert.deepEqual(errors, [])
    const result = { mode: 'account-security', checks, errors,
      limitations: ['全部 API 替身，不写库；延迟、重复提交和切回事件由脚本控制，不证明自然网络时序或真实闲置失效',
        'beforeunload 仅检查脚本事件的取消标记，未执行浏览器真实关闭或刷新；未新增厂商或后端全量验收'] }
    await writeFile(new URL('S5账号安全结果.json', out), JSON.stringify(result, null, 2))
    console.log(JSON.stringify(result, null, 2))
    assert.ok(checks.every(check => check.passed), '账号安全专项存在未通过检查')
  } catch (error) {
    console.log(JSON.stringify({ checks, errors, path: new URL(page.url()).pathname }))
    await page.screenshot({ path: fileURLToPath(new URL('S5-账号安全-失败.png', out)), fullPage: true })
    throw error
  } finally {
    await browser.close()
  }
  process.exit(0)
}

/** 页头与状态：一页一个 h1、可见文案、以及四种状态的互斥关系。 */
const readPage = (target) => target.evaluate(() => ({
  h1: [...document.querySelectorAll('h1')].map((e) => e.textContent.trim()),
  h2: [...document.querySelectorAll('h2')].map((e) => e.textContent.trim()),
  overflow: document.documentElement.scrollWidth - window.innerWidth,
  skeleton: document.querySelectorAll('[data-public-skeleton], [data-public-detail-skeleton], [data-ai-config-skeleton]').length,
  empty: document.querySelectorAll('[data-public-empty], [data-public-detail-missing]').length,
  error: document.querySelectorAll('.public-error, .ai-config__error').length,
  cards: document.querySelectorAll('.public-card').length,
  loadingMask: document.querySelectorAll('.el-loading-mask').length,
}))

const shot = async (name, target, fullPage = false) => {
  await target.screenshot({ path: fileURLToPath(new URL(`${name}.png`, out)), fullPage })
  results.screenshots.push(`${name}.png`)
}

try {
  // ---------- 1. 登录／注册／恢复（匿名会话） ----------
  const anon = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const authPage = await anon.newPage()
  const authErrors = []
  authPage.on('pageerror', (e) => authErrors.push(e.message))

  for (const [name, path, heading] of [
    ['登录', '/login', '继续，把事情做好。'],
    ['注册', '/register', '开始，一起做活动。'],
    ['恢复', '/recover', '用恢复码重新取得控制权。'],
  ]) {
    await authPage.goto(path, { waitUntil: 'networkidle' })
    const state = await readPage(authPage)
    assert.deepEqual(state.h1, [heading], `${name}页应只有一个 h1 且文案正确`)
    assert.equal(state.overflow, 0, `${name}页桌面横向溢出`)
    // 左右分屏：品牌区与表单区各占一列且都可见
    const split = await authPage.evaluate(() => ({
      brand: Math.round(document.querySelector('.auth-brand').getBoundingClientRect().width),
      panel: Math.round(document.querySelector('.auth-panel').getBoundingClientRect().width),
      inputs: document.querySelectorAll('.auth-content input').length,
    }))
    assert.ok(split.brand > 0 && split.panel > 0, `${name}页应保持左右分屏`)
    assert.ok(split.inputs >= 2, `${name}页表单字段应可见`)
    await shot(`S5-${name}-1440`, authPage)
  }

  // N01 定点回归：品牌区必须有环形装饰与玻璃流程卡，标语与页边距按认证专用 token
  await authPage.setViewportSize({ width: 1440, height: 1000 })
  await authPage.goto('/login', { waitUntil: 'networkidle' })
  const brand = await authPage.evaluate(() => {
    const decor = document.querySelector('.auth-decor')
    const flow = document.querySelector('.auth-flow')
    const slogan = document.querySelector('.auth-slogan')
    const brandBox = document.querySelector('.auth-brand')
    return {
      decor: decor ? decor.getBoundingClientRect().width : 0,
      flow: flow ? Math.round(flow.getBoundingClientRect().width) : 0,
      sloganFont: parseFloat(getComputedStyle(slogan).fontSize),
      sloganLeft: Math.round(slogan.getBoundingClientRect().left),
      brandPadding: getComputedStyle(brandBox).padding,
      breakDisplay: getComputedStyle(document.querySelector('.auth-slogan__break')).display,
    }
  })
  assert.ok(brand.decor > 0, '品牌区应有环形装饰')
  assert.ok(brand.flow > 0, '品牌区应有玻璃流程卡')
  assert.ok(brand.sloganFont >= 64, `桌面标语应达到 Demo 量级，实际 ${brand.sloganFont}`)
  assert.ok(brand.sloganLeft >= 48, `品牌左边距应明显大于通用页边距，实际 ${brand.sloganLeft}`)
  assert.ok(brand.brandPadding.startsWith('45px'), `认证页应使用认证专用边距，实际 ${brand.brandPadding}`)
  // 手机：装饰与流程卡让位、标语允许自然折行、字段与按钮仍可见
  await authPage.setViewportSize({ width: 390, height: 844 })
  await authPage.reload({ waitUntil: 'networkidle' })
  const phone = await authPage.evaluate(() => ({
    decor: document.querySelectorAll('.auth-decor:not([hidden])').length && getComputedStyle(document.querySelector('.auth-decor')).display,
    flow: getComputedStyle(document.querySelector('.auth-flow')).display,
    breakDisplay: getComputedStyle(document.querySelector('.auth-slogan__break')).display,
    overflow: document.documentElement.scrollWidth - window.innerWidth,
    inputs: document.querySelectorAll('.auth-content input').length,
    button: Math.round(document.querySelector('.auth-submit').getBoundingClientRect().height),
  }))
  assert.equal(phone.decor, 'none', '手机应压缩环形装饰')
  assert.equal(phone.flow, 'none', '手机应压缩玻璃流程卡')
  assert.equal(phone.breakDisplay, 'none', '手机不应强制三行标语')
  assert.equal(phone.overflow, 0, '手机认证页不应横向溢出')
  assert.ok(phone.inputs >= 2, '手机不得隐藏字段')
  assert.ok(phone.button >= 40, `手机主按钮不应只有 32px，实际 ${phone.button}`)
  await shot('S5-登录-390', authPage)
  await authPage.setViewportSize({ width: 1440, height: 1000 })

  // 登录表单：首次校验在失焦触发，不在输入过程中逐字报错；失败给出可读原因
  await authPage.goto('/login', { waitUntil: 'networkidle' })
  const loginForm = authPage.locator('.auth-content')
  const usernameField = loginForm.getByPlaceholder('注册时设置的用户名')
  await usernameField.click()
  // 输入过程中不应出现错误提示（规范 7.1：首次校验在 blur）
  await usernameField.fill('不存在的账号_核对')
  assert.equal(await loginForm.locator('.el-form-item.is-error').count(), 0, '输入过程中不应逐字报错')
  await usernameField.fill('')
  await loginForm.getByPlaceholder('请输入密码').click()
  await loginForm.getByText('请输入用户名', { exact: true }).waitFor({ state: 'visible' })
  assert.equal(await loginForm.locator('.el-form-item.is-error').count(), 1, '清空后失焦应只报当前字段')
  await shot('S5-登录-失焦校验', authPage)
  // 真实提交一次错误凭据：应给出可读的失败原因，且不跳转
  await loginForm.getByPlaceholder('注册时设置的用户名').fill('不存在的账号_核对')
  await loginForm.getByPlaceholder('请输入密码').fill('WrongPass123')
  await loginForm.getByRole('button', { name: '进入工作区' }).click()
  await loginForm.getByText('账号或密码不正确。', { exact: true }).waitFor({ state: 'visible' })
  assert.ok(authPage.url().includes('/login'), '登录失败不应跳转')
  await shot('S5-登录-失败提示', authPage)
  // 匿名会话不得直接进入组织工作区
  await authPage.goto('/org/4922/activities', { waitUntil: 'networkidle' })
  assert.ok(authPage.url().includes('/login'), '未登录访问组织路由应回到登录页')
  assert.equal(await authPage.locator('.unified-sidebar').count(), 0, '未登录不得渲染组织导航')
  assert.deepEqual(authErrors, [], `认证页运行错误：${authErrors.join('；')}`)
  await anon.close()

  // ---------- 2. 公开参与（匿名） ----------
  const publicCtx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const publicPage = await publicCtx.newPage()
  const publicErrors = []
  publicPage.on('pageerror', (e) => publicErrors.push(e.message))
  const hold = gate()
  await publicPage.route('**/api/public/activities?*', async (route) => { await hold; await route.continue() })
  await publicPage.goto('/public/activities', { waitUntil: 'domcontentloaded' })
  await publicPage.locator('[data-public-skeleton]').waitFor({ state: 'visible' })
  const publicLoading = await readPage(publicPage)
  assert.equal(publicLoading.cards, 0, '首屏加载时不应有卡片')
  assert.equal(publicLoading.empty, 0, '首屏加载时不应同时显示空态')
  assert.equal(publicLoading.error, 0, '首屏加载时不应同时显示失败')
  release()
  await publicPage.waitForResponse((r) => r.url().includes('/api/public/activities?'))
  await publicPage.unrouteAll({ behavior: 'ignoreErrors' })
  await publicPage.locator('.public-card').first().waitFor({ state: 'visible' })
  const publicReady = await readPage(publicPage)
  assert.equal(publicReady.h1.length, 1, '公开列表应只有一个 h1')
  assert.equal(publicReady.skeleton, 0, '有数据时不应显示骨架')
  assert.equal(publicReady.overflow, 0, '公开列表桌面横向溢出')
  // 公开页不得出现内部信息：字段层核对响应，展示层核对标签（页脚说明里的“预算”是免责文字，不算泄露）
  const publicRaw = await (await publicCtx.request.get('/api/public/activities?page=1&pageSize=12')).json()
  const publicKeys = Object.keys(publicRaw.items[0])
  for (const internal of ['budget', 'proposal', 'ownerUsername', 'summary', 'reviewNote', 'tasks', 'materials']) {
    assert.equal(publicKeys.includes(internal), false, `公开接口不得下发内部字段：${internal}`)
  }
  const publicText = await publicPage.locator('main').innerText()
  for (const label of ['预算（元）', '已确认支出', '活动负责人', '组织负责人', '方案', '任务分工', '附件']) {
    assert.equal(publicText.includes(label), false, `公开列表不得出现内部字段：${label}`)
  }
  await shot('S5-公开活动-1440', publicPage)
  await shot('S5-公开活动-完整', publicPage, true)

  // 空态与失败态互斥
  await publicPage.route('**/api/public/activities?*', (route) => route.fulfill({
    json: { items: [], page: 1, pageSize: 12, total: 0 },
  }))
  await publicPage.reload({ waitUntil: 'networkidle' })
  await publicPage.locator('[data-public-empty]').waitFor({ state: 'visible' })
  const publicEmpty = await readPage(publicPage)
  assert.equal(publicEmpty.cards, 0, '空态不应显示卡片')
  assert.equal(publicEmpty.skeleton, 0, '空态不应同时显示骨架')
  assert.equal(publicEmpty.error, 0, '空态不应同时显示失败')
  await shot('S5-公开活动-空态', publicPage)
  await publicPage.unrouteAll({ behavior: 'ignoreErrors' })
  await publicPage.route('**/api/public/activities?*', (route) => route.fulfill({
    status: 503,
    contentType: 'application/problem+json',
    body: JSON.stringify({ status: 503, code: 'UNAVAILABLE', detail: '状态核对' }),
  }))
  await publicPage.reload({ waitUntil: 'networkidle' })
  await publicPage.locator('.public-error').waitFor({ state: 'visible' })
  const publicFailed = await readPage(publicPage)
  assert.equal(publicFailed.cards, 0, '失败不应残留卡片')
  assert.equal(publicFailed.skeleton, 0, '失败不应同时显示骨架')
  assert.equal(publicFailed.empty, 0, '失败不应同时显示空态')
  assert.equal(await publicPage.getByRole('button', { name: '重新加载' }).count(), 1, '失败应提供重试入口')
  await shot('S5-公开活动-失败', publicPage)
  await publicPage.unrouteAll({ behavior: 'ignoreErrors' })
  await publicPage.reload({ waitUntil: 'networkidle' })
  await publicPage.locator('.public-card').first().waitFor({ state: 'visible' })

  // 公开详情：真实活动 + 失败终态
  const publicList = (await (await publicCtx.request.get('/api/public/activities?page=1&pageSize=12')).json()).items
  assert.ok(publicList.length > 0, '需要既有公开活动样例')
  const detailUrl = `/public/activities/${publicList[0].id}`
  await publicPage.goto(detailUrl, { waitUntil: 'networkidle' })
  await publicPage.locator('.public-facts').waitFor({ state: 'visible' })
  const detailState = await readPage(publicPage)
  assert.equal(detailState.h1.length, 1, '公开详情应只有一个 h1')
  assert.equal(detailState.overflow, 0, '公开详情桌面横向溢出')
  assert.equal(await publicPage.locator('.public-facts div').count(), 7, '公开详情应展示 7 项公开事实')
  const detailKeys = Object.keys((await (await publicCtx.request.get(`/api/public/activities/${publicList[0].id}`)).json()))
  assert.equal(detailKeys.includes('budget'), false, '公开详情接口不得下发预算')
  const detailText = await publicPage.locator('.public-detail').innerText()
  for (const label of ['预算（元）', '已确认支出', '活动负责人', '组织负责人']) {
    assert.equal(detailText.includes(label), false, `公开详情不得出现内部字段：${label}`)
  }
  await shot('S5-公开详情-1440', publicPage, true)

  await publicPage.route('**/api/public/activities/*', (route) => route.fulfill({
    status: 503,
    contentType: 'application/problem+json',
    body: JSON.stringify({ status: 503, code: 'UNAVAILABLE', detail: '状态核对' }),
  }))
  await publicPage.reload({ waitUntil: 'networkidle' })
  await publicPage.locator('[data-public-detail-missing]').waitFor({ state: 'visible' })
  const detailFailed = await readPage(publicPage)
  assert.equal(detailFailed.h1.length, 0, '详情失败态不保留标题')
  assert.equal(detailFailed.skeleton, 0, '详情失败不应同时显示骨架')
  const detailMissingText = await publicPage.locator('[data-public-detail-missing]').innerText()
  assert.ok(detailMissingText.trim().length > 0, '详情失败必须说明原因，不能留空白页')
  assert.equal(await publicPage.getByRole('button', { name: '重新加载' }).count(), 1, '详情失败应提供重试入口')
  assert.equal(await publicPage.getByRole('link', { name: '返回公开活动' }).count(), 1, '详情失败应给出返回入口')
  await shot('S5-公开详情-失败', publicPage)
  await publicPage.unrouteAll({ behavior: 'ignoreErrors' })
  assert.deepEqual(publicErrors, [], `公开页运行错误：${publicErrors.join('；')}`)
  await publicCtx.close()

  // ---------- 3. 账号页与组织设置（已登录负责人） ----------
  const ctx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
  assert.equal((await ctx.request.post('/api/auth/login', {
    form: { username: process.env.UI_AUDIT_USER || 'demo_owner_yrnn', password: process.env.UI_AUDIT_PASS || 'Demo12345!' },
    headers: { [csrf.headerName]: csrf.token },
  })).status(), 204, '演示负责人登录失败')
  const org = (await (await ctx.request.get('/api/me/organizations')).json()).items[0]
  const page = await ctx.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))

  await page.goto('/', { waitUntil: 'networkidle' })
  const account = await readPage(page)
  assert.deepEqual(account.h1, ['我的工作区'], '账号页应只有一个 h1')
  assert.equal(account.overflow, 0, '账号页桌面横向溢出')
  assert.equal(await page.getByRole('button', { name: '加入组织' }).count(), 1, '账号页应提供加入组织入口')
  assert.equal(await page.getByRole('button', { name: '创建组织' }).count(), 1, '账号页应提供创建组织入口')
  await page.getByRole('button', { name: '加入组织' }).click()
  await page.getByRole('dialog', { name: '加入组织' }).waitFor({ state: 'visible' })
  await page.getByRole('dialog', { name: '加入组织' }).getByRole('button', { name: /关闭|Close/ }).click()
  await shot('S5-账号-1440', page, true)

  await page.goto(`/org/${org.id}/settings`, { waitUntil: 'networkidle' })
  await page.locator('.ai-config__form').waitFor({ state: 'visible' })
  const settings = await readPage(page)
  assert.deepEqual(settings.h1, ['组织设置'], '设置页应只有一个 h1')
  assert.equal(settings.overflow, 0, '设置页桌面横向溢出')
  assert.equal(settings.skeleton, 0, '配置已加载时不应显示骨架')
  assert.equal(settings.error, 0, '正常读取不应显示失败')
  const config = (await (await ctx.request.get(`/api/organizations/${org.id}/ai/config`)).json())
  // 字段定位沿用 audit_p8_ai.mjs 的写法：属性可能落在输入框或包装元素上
  const fieldInput = (name) => page.locator(`input[data-ai-field="${name}"], [data-ai-field="${name}"] input`).first()
  assert.equal(await fieldInput('endpoint').inputValue(), config.endpoint ?? '', '端点字段应与接口一致')
  assert.equal(await fieldInput('model').inputValue(), config.model ?? '', '模型字段应与接口一致')
  assert.equal(await fieldInput('apiKey').inputValue(), '', '已配置的密钥不得回显到输入框')
  // N04：字段必须在白色内容面板里，不再直接排在页面底色上
  const settingsPanel = await page.evaluate(() => {
    const panel = document.querySelector('[data-ai-config-panel]')
    const style = getComputedStyle(panel)
    return { bg: style.backgroundColor, border: style.borderTopWidth, radius: style.borderTopLeftRadius, padding: style.padding }
  })
  assert.notEqual(settingsPanel.bg, 'rgba(0, 0, 0, 0)', '设置字段应落在有底色的面板内')
  assert.ok(parseFloat(settingsPanel.border) > 0, '设置面板应有边框')
  assert.ok(parseFloat(settingsPanel.padding) >= 16, '设置面板应有卡片内边距')
  // N03：地址失焦校验（输入非法值后离开字段即报错，并同步 aria-invalid）
  await fieldInput('endpoint').click()
  await fieldInput('endpoint').clear()
  await fieldInput('endpoint').fill('not-a-url')
  await page.locator('.ai-config__group').click()
  await page.locator('.el-form-item__error').first().waitFor({ state: 'visible' })
  assert.ok(await page.locator('.el-form-item__error').first().innerText().then((t) => t.includes('完整地址')), '非法地址应给出可读错误')
  assert.equal(await fieldInput('endpoint').getAttribute('aria-invalid'), 'true', '错误字段应标记 aria-invalid')
  await shot('S5-组织设置-失焦校验', page, true)
  await page.reload({ waitUntil: 'networkidle' })
  await page.locator('.ai-config__form').waitFor({ state: 'visible' })
  await shot('S5-组织设置-1440', page, true)

  // 配置骨架与失败互斥
  const settingsHold = gate()
  await page.route('**/api/organizations/*/ai/config', async (route) => { await settingsHold; await route.continue() })
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.locator('[data-ai-config-skeleton]').waitFor({ state: 'visible' })
  const settingsLoading = await readPage(page)
  assert.equal(settingsLoading.error, 0, '配置加载时不应同时显示失败')
  assert.equal(settingsLoading.loadingMask, 0, '配置加载不应叠加整页遮罩')
  await shot('S5-组织设置-加载', page)
  release()
  await page.waitForResponse((r) => r.url().includes('/ai/config'))
  await page.unrouteAll({ behavior: 'ignoreErrors' })
  await page.locator('.ai-config__form').waitFor({ state: 'visible' })
  await page.route('**/api/organizations/*/ai/config', (route) => route.fulfill({
    status: 503,
    contentType: 'application/problem+json',
    body: JSON.stringify({ status: 503, code: 'UNAVAILABLE', detail: '状态核对' }),
  }))
  await page.reload({ waitUntil: 'networkidle' })
  await page.locator('.ai-config__error').waitFor({ state: 'visible' })
  const settingsFailed = await readPage(page)
  assert.equal(settingsFailed.skeleton, 0, '配置失败不应同时显示骨架')
  assert.equal(await page.locator('.ai-config__form').count(), 0, '配置失败不应显示表单')
  // N03：初次读取失败必须给出可操作的重试
  assert.equal(await page.locator('.ai-config').getByRole('button', { name: /重试|重新加载/ }).count(), 1, '配置失败应提供重试入口')
  await shot('S5-组织设置-失败', page)
  await page.unrouteAll({ behavior: 'ignoreErrors' })
  await page.locator('.ai-config').getByRole('button', { name: /重试|重新加载/ }).click()
  await page.locator('.ai-config__form').waitFor({ state: 'visible' })
  assert.equal(await page.locator('.ai-config__error').count(), 0, '重试成功后应移除错误')
  assert.deepEqual(errors, [], `S5 页面运行错误：${errors.join('；')}`)

  // 普通成员：设置页只读，看不到表单与保存入口
  const memberCtx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const memberCsrf = await (await memberCtx.request.get('/api/auth/csrf')).json()
  assert.equal((await memberCtx.request.post('/api/auth/login', {
    form: { username: process.env.UI_AUDIT_MEMBER || 'demo_member_yrnn', password: process.env.UI_AUDIT_PASS || 'Demo12345!' },
    headers: { [memberCsrf.headerName]: memberCsrf.token },
  })).status(), 204, '演示成员登录失败')
  const memberPage = await memberCtx.newPage()
  await memberPage.goto(`/org/${org.id}/settings`, { waitUntil: 'networkidle' })
  await memberPage.locator('[data-ai-role="member"]').waitFor({ state: 'visible' })
  assert.equal(await memberPage.locator('.ai-config__form').count(), 0, '普通成员不应看到配置表单')
  assert.equal(await memberPage.locator('[data-ai-action="save"]').count(), 0, '普通成员不应看到保存入口')
  assert.equal(await memberPage.locator('[data-ai-action="test"]').count(), 0, '普通成员不应看到测试入口')
  await shot('S5-组织设置-普通成员', memberPage)
  await memberCtx.close()

  // ---------- 3b. N02 触控控件尺寸 ----------
  const touchCtx = await browser.newContext({
    baseURL: base, viewport: { width: 390, height: 720 }, isMobile: true, hasTouch: true,
  })
  const touchPage = await touchCtx.newPage()
  await touchPage.goto('/login', { waitUntil: 'networkidle' })
  const touchTargets = await touchPage.evaluate(() => {
    const size = (selector) => Math.round(document.querySelector(selector).getBoundingClientRect().height)
    return {
      coarse: matchMedia('(pointer: coarse)').matches,
      button: size('.auth-submit'),
      input: size('.auth-content .el-input'),
      minTouch: getComputedStyle(document.documentElement).getPropertyValue('--ui-control-min-touch').trim(),
    }
  })
  assert.equal(touchTargets.coarse, true, '触控上下文应命中 coarse 指针')
  assert.ok(touchTargets.button >= 44, `触控主按钮高度应达到 44px，实际 ${touchTargets.button}`)
  assert.ok(touchTargets.input >= 44, `触控输入框高度应达到 44px，实际 ${touchTargets.input}`)
  await shot('S5-登录-触控', touchPage)
  await touchCtx.close()

  // ---------- 3c. N05 详情业务区加载状态 ----------
  const detailActivities = (await (await ctx.request.get(`/api/organizations/${org.id}/activities?pageSize=50`)).json()).items
  const detailTarget = detailActivities.find((item) => item.status === 'APPROVED') ?? detailActivities[0]
  assert.ok(detailTarget, '需要既有活动用于详情加载核对')
  const detailPage = await ctx.newPage()
  const directiveWarnings = []
  detailPage.on('console', (m) => { if (m.text().includes('Failed to resolve directive')) directiveWarnings.push(m.text().split('\n')[0]) })
  let releaseTasks
  const tasksHeld = new Promise((resolve) => { releaseTasks = resolve })
  await detailPage.route(
    (url) => url.pathname === `/api/organizations/${org.id}/activities/${detailTarget.id}/tasks`,
    async (route) => { await tasksHeld; await route.continue() },
  )
  await detailPage.goto(`/org/${org.id}/activities/${detailTarget.id}?tab=tasks`, { waitUntil: 'domcontentloaded' })
  await detailPage.locator('[data-p3-skeleton="tasks"]').waitFor({ state: 'visible' })
  const detailLoading = await detailPage.evaluate(() => ({
    taskSkeleton: document.querySelectorAll('[data-p3-skeleton="tasks"]').length,
    materialSkeleton: document.querySelectorAll('[data-p3-skeleton="materials"]').length,
    emptyTexts: [...document.querySelectorAll('.el-table__empty-text')].map((e) => e.textContent.trim()),
    overlays: document.querySelectorAll('.el-loading-mask').length,
  }))
  assert.ok(detailLoading.taskSkeleton > 0, '任务首次读取应显示骨架')
  assert.ok(detailLoading.materialSkeleton > 0, '同批读取的物资也应显示骨架')
  assert.equal(detailLoading.overlays, 0, '不应叠加整表遮罩')
  assert.deepEqual(detailLoading.emptyTexts, [], '加载期间不得显示“暂无任务／暂无物资”')
  await shot('S5-详情-任务慢响应', detailPage, true)
  releaseTasks()
  await detailPage.unrouteAll({ behavior: 'ignoreErrors' })
  await detailPage.locator('[data-p3-skeleton="tasks"]').waitFor({ state: 'detached' })
  assert.ok(await detailPage.locator('.el-table').count() >= 1, '数据到达后应渲染表格')
  assert.deepEqual(directiveWarnings, [], `详情区出现指令解析警告：${directiveWarnings.join('；')}`)
  await shot('S5-详情-任务完成', detailPage, true)
  await detailPage.close()

  // ---------- 3d. 骨架延迟：真实限速下可见，快速请求不闪一帧（UI 规范第 10 节） ----------
  const listCount = (await (await ctx.request.get(`/api/organizations/${org.id}/activities?pageSize=50`)).json()).items.length
  const throttleCtx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const throttleCsrf = await (await throttleCtx.request.get('/api/auth/csrf')).json()
  assert.equal((await throttleCtx.request.post('/api/auth/login', {
    form: { username: process.env.UI_AUDIT_USER || 'demo_owner_yrnn', password: process.env.UI_AUDIT_PASS || 'Demo12345!' },
    headers: { [throttleCsrf.headerName]: throttleCsrf.token },
  })).status(), 204, '限速核对登录失败')
  const throttlePage = await throttleCtx.newPage()
  const cdp = await throttleCtx.newCDPSession(throttlePage)
  await cdp.send('Network.enable')
  // Fast 3G：真实链路延迟，不用响应替身
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false, latency: 400, downloadThroughput: 400 * 1024, uploadThroughput: 400 * 1024,
  })
  await throttlePage.goto(`/org/${org.id}/activities`, { waitUntil: 'domcontentloaded' })
  await throttlePage.locator('[data-list-skeleton]').waitFor({ state: 'visible', timeout: 15000 })
  const throttleSkeleton = await throttlePage.evaluate(() => ({
    skeleton: document.querySelectorAll('[data-list-skeleton]').length,
    rows: document.querySelectorAll('.list-table .el-table__body tr').length,
    empty: document.querySelectorAll('[data-list-empty]').length,
    placeholders: document.querySelectorAll('[data-list-skeleton] .el-skeleton__item').length,
  }))
  assert.ok(throttleSkeleton.skeleton > 0, '限速下应显示骨架')
  assert.equal(throttleSkeleton.rows, 0, '骨架期间不应有数据行')
  assert.equal(throttleSkeleton.empty, 0, '骨架期间不应显示空态')
  assert.ok(throttleSkeleton.placeholders >= 6, '骨架应与真实表格同构（含表头占位）')
  await shot('S5-活动列表-限速骨架', throttlePage)
  await throttlePage.locator('.list-table .el-table__body tr').first().waitFor({ state: 'visible', timeout: 20000 })
  assert.equal(await throttlePage.locator('.list-table .el-table__body tr').count(), listCount, '限速下数据最终应到达')
  // 解除限速后本地快响应：骨架不应闪现（leading 延迟生效）
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1,
  })
  await throttlePage.goto(`/org/${org.id}/activities`, { waitUntil: 'domcontentloaded' })
  const fastFrames = []
  for (let i = 0; i < 10; i += 1) {
    fastFrames.push(await throttlePage.evaluate(() => document.querySelectorAll('[data-list-skeleton]').length))
    await throttlePage.waitForTimeout(20)
  }
  assert.equal(fastFrames.some((n) => n > 0), false, `快速请求不应闪现骨架，实际采样：${fastFrames.join(',')}`)
  await throttlePage.locator('.list-table .el-table__body tr').first().waitFor({ state: 'visible' })
  await throttleCtx.close()

  // ---------- 4. 五档宽度（S5 代表页，各用匹配的会话） ----------
  const widthCtx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const widthPage = await widthCtx.newPage()
  for (const [name, path, anchor] of [
    ['登录', '/login', '.auth-content'],
    ['公开活动', '/public/activities', '.public-card'],
    ['账号', '/', '.account-page'],
    ['组织设置', `/org/${org.id}/settings`, '.ai-config'],
  ]) {
    const anonymous = path === '/login' || path === '/public/activities'
    if (anonymous) {
      await widthCtx.clearCookies()
    } else if (!(await widthCtx.cookies()).some((cookie) => cookie.name === 'SESSION')) {
      // 组织路由需要登录；复用一次真实登录，不伪造会话
      const widthCsrf = await (await widthCtx.request.get('/api/auth/csrf')).json()
      assert.equal((await widthCtx.request.post('/api/auth/login', {
        form: { username: process.env.UI_AUDIT_USER || 'demo_owner_yrnn', password: process.env.UI_AUDIT_PASS || 'Demo12345!' },
        headers: { [widthCsrf.headerName]: widthCsrf.token },
      })).status(), 204, '宽度核对登录失败')
    }
    for (const width of [320, 390, 768, 1024, 1440]) {
      await widthPage.setViewportSize({ width, height: 1000 })
      await widthPage.goto(path, { waitUntil: 'networkidle' })
      await widthPage.locator(anchor).first().waitFor({ state: 'visible' })
      const state = await readPage(widthPage)
      assert.equal(state.overflow, 0, `${name} ${width} 出现整页横向溢出`)
      assert.equal(state.h1.length, 1, `${name} ${width} 应只有一个 h1`)
      results.widths.push({ page: name, width, overflow: state.overflow })
      if (width === 390) {
        await shot(`S5-${name}-390`, widthPage, true)
      }
    }
  }
  await widthPage.setViewportSize({ width: 390, height: 1000 })
  await widthPage.goto('/public/activities', { waitUntil: 'networkidle' })
  await widthPage.locator('.public-card').first().waitFor({ state: 'visible' })
  assert.equal(await readPage(widthPage).then((s) => s.overflow), 0, '公开列表 390 横向溢出')
  await shot('S5-公开活动-390', widthPage, true)
  await widthCtx.close()

  results.checks.push(
    '登录／注册／恢复：左右分屏、一页一个 h1、字段可见、输入不逐字报错而失焦才报错、错误凭据给出可读提示且不跳转、未登录访问组织路由回到登录页',
    'N01 认证构图：品牌区有环形装饰与玻璃流程卡，桌面标语与左边距达到 Demo 量级且用认证专用边距；手机压缩装饰、允许自然折行、字段与按钮保留',
    'N02 控件尺寸：触控上下文下主按钮与输入框高度均 ≥ 44px（桌面默认按钮按 control-height 收敛）',
    'N03 设置校验：非法地址失焦即报错并标记 aria-invalid；初次读取失败给出可操作重试，重试成功后错误消失',
    'N04 设置面板：字段落在有底色、边框与内边距的内容面板内，不再直接排在页面底色上',
    'N05 详情状态：首次读取显示骨架、不显示“暂无任务／物资”、无整表遮罩、无指令解析警告，数据到达后渲染表格',
    '骨架延迟：真实 Fast 3G 限速下骨架可见且与表格同构、期间无数据行与空态；解除限速后本地快响应不闪现骨架（leading／trailing 来自集中 token）',
    '公开活动：加载骨架与空态／失败态互斥、失败有重试、不含内部字段、五档宽度无溢出',
    '公开详情：真实公开活动 7 项事实、隐藏内部字段、失败给出原因与返回入口且不残留骨架',
    '账号页：单一 h1、加入与创建组织入口可用、桌面与手机无溢出',
    '组织设置：端点与模型与接口一致、密钥不回显、骨架与失败互斥、普通成员只读且无保存入口',
  )
  results.orgId = org.id
  await writeFile(new URL('S5结果.json', out), JSON.stringify(results, null, 2))
  const cards = [
    ['登录 1440', 'S5-登录-1440.png'],
    ['登录 失焦校验', 'S5-登录-失焦校验.png'],
    ['登录 失败提示', 'S5-登录-失败提示.png'],
    ['登录 手机 390', 'S5-登录-390.png'],
    ['登录 触控尺寸', 'S5-登录-触控.png'],
    ['注册 1440', 'S5-注册-1440.png'],
    ['恢复 1440', 'S5-恢复-1440.png'],
    ['账号 1440', 'S5-账号-1440.png'],
    ['账号 手机 390', 'S5-账号-390.png'],
    ['组织设置 1440', 'S5-组织设置-1440.png'],
    ['组织设置 失焦校验', 'S5-组织设置-失焦校验.png'],
    ['组织设置 加载', 'S5-组织设置-加载.png'],
    ['组织设置 失败', 'S5-组织设置-失败.png'],
    ['组织设置 普通成员', 'S5-组织设置-普通成员.png'],
    ['详情 任务慢响应', 'S5-详情-任务慢响应.png'],
    ['详情 任务完成', 'S5-详情-任务完成.png'],
    ['公开活动 1440', 'S5-公开活动-1440.png'],
    ['公开活动 空态', 'S5-公开活动-空态.png'],
    ['公开活动 失败', 'S5-公开活动-失败.png'],
    ['公开活动 手机 390', 'S5-公开活动-390.png'],
    ['公开详情 1440', 'S5-公开详情-1440.png'],
    ['公开详情 失败', 'S5-公开详情-失败.png'],
  ]
  await writeFile(new URL('S5逐页对照.html', out), `<!doctype html><html lang="zh-CN"><meta charset="utf-8">`
    + `<meta name="viewport" content="width=device-width"><title>S5 逐页还原对照</title>`
    + '<style>body{font:15px system-ui;margin:24px;background:#f3f3ef;color:#1a1814}'
    + 'main{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}figure{margin:0}'
    + 'img{width:100%;vertical-align:top;border:1px solid #ddd}figcaption{padding:8px 0;font-weight:600}'
    + '@media(max-width:800px){main{grid-template-columns:1fr}}</style>'
    + '<h1>S5 登录／账号／设置／公开参与</h1>'
    + '<p>1440×1000 与 390 宽真实数据截图；加载、空态、失败为浏览器响应替身，已在 S5结果.json 标注。</p>'
    + `<main>${cards.map(([label, file]) => `<figure><figcaption>${label}</figcaption><img src="${file}" alt="${label}"></figure>`).join('')}</main></html>`)
  console.log(JSON.stringify({ checks: results.checks, widths: results.widths.length, screenshots: results.screenshots.length }, null, 2))
} finally {
  await browser.close()
}
