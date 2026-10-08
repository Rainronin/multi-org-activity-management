// 公开报名、反馈与组织 AI 设置：正式 Vue 页面 + API 替身，不写库或调用模型。
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

const base = 'http://127.0.0.1:5174'
const output = new URL('../验证截图/公开反馈AI设置/', import.meta.url)
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const context = await browser.newContext({ reducedMotion: 'reduce' })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))

  let feedbackSaved = null
  let feedbackConflict = true
  let registration = null
  let registrationFailed = true
  let configTestFailed = true
  let configTestKey
  let configTestPosts = 0
  let configSaveConflict = true
  let savedModel = 'model-1'
  let configVersion = 1
  let pauseOldConfig = false
  let releaseOldConfig
  const oldConfigGate = new Promise(resolve => { releaseOldConfig = resolve })
  let releaseEnable
  let signalEnableStarted
  const enableGate = new Promise(resolve => { releaseEnable = resolve })
  const enableStarted = new Promise(resolve => { signalEnableStarted = resolve })
  const configBusyChecks = []
  let heldConfigAction
  function holdConfigAction(action) {
    let release, signal
    const gate = new Promise(resolve => { release = resolve })
    const started = new Promise(resolve => { signal = resolve })
    heldConfigAction = { action, gate, signal }
    return { release, started }
  }
  async function waitHeldConfigAction(action) {
    if (heldConfigAction?.action !== action) return
    const held = heldConfigAction
    heldConfigAction = null
    held.signal()
    await held.gate
  }
  async function checkConfigBusy(action) {
    const fieldsLocked = await page.locator('.ai-config__form input').evaluateAll(inputs => inputs.every(input => input.disabled))
    const actionsLocked = await page.locator('.ai-config__actions button').evaluateAll(buttons => buttons.every(button => button.disabled))
    configBusyChecks.push({ action, fieldsLocked, actionsLocked })
    return fieldsLocked && actionsLocked
  }
  const org = id => ({ id, name: `组织${id}`, role: id === '1' ? 'OWNER' : 'MEMBER', memberCount: 2,
    joinedAt: '2026-09-29', createdAt: '2026-09-29', description: '' })
  const config = id => ({ viewerRole: id === '1' ? 'OWNER' : 'MEMBER', available: false,
    availabilityHint: '已停用', configState: 'DISABLED', protocol: 'OPENAI_CHAT_COMPLETIONS',
    endpoint: `https://api.example.com/${id}`, model: id === '1' ? savedModel : 'model-2', authScheme: 'BEARER',
    maxOutputTokens: 1024, outputLimitField: 'MAX_TOKENS', timeoutSeconds: 20,
    hasApiKey: true, enabled: false, version: id === '1' ? configVersion : 1, connectionRevision: 1,
    testedRevision: 1, testedForCurrentRevision: true, testedAt: null, testedBy: null })

  await context.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/public/activities/7/feedback' && request.method() === 'POST') {
      if (feedbackConflict) {
        feedbackConflict = false
        await route.fulfill({ status: 409, json: { status: 409, code: 'FEEDBACK_CONFLICT', detail: '反馈冲突，请核对后重试。' } })
      } else {
        feedbackSaved = request.postDataJSON()
        await route.fulfill({ json: { feedbackId: '1', ...feedbackSaved, version: 1 } })
      }
      return
    }
    if (path === '/api/activities/8/registrations' && request.method() === 'POST') {
      const input = request.postDataJSON()
      assert.equal(input.displayName, '未保存报名姓名')
      assert.equal(input.note, '报名备注草稿')
      if (registrationFailed) {
        registrationFailed = false
        await route.fulfill({ status: 503, json: { status: 503, code: 'UNAVAILABLE', detail: '报名服务暂不可用，请重试。' } })
      } else {
        registration = { registrationId: '1', activityId: '8', accountId: 1, accountUsername: '表单核对',
          ...input, version: 1, status: 'PENDING', cancelReason: null, decidedAt: null, createdAt: '2026-09-29',
          activityName: '可报名活动', organizationName: '公开组织', location: '线上',
          startTime: '2030-01-01T12:00:00', endTime: '2030-01-01T13:00:00', registrationOpen: true, adjusting: false }
        await route.fulfill({ json: registration })
      }
      return
    }
    if (path === '/api/organizations/1/ai/config' && request.method() === 'GET' && pauseOldConfig) {
      pauseOldConfig = false
      await oldConfigGate
      await route.fulfill({ json: config('1') })
      return
    }
    if (path === '/api/organizations/1/ai/config/test' && request.method() === 'POST') {
      configTestPosts++
      configTestKey = request.headers()['idempotency-key']
      assert.ok(configTestKey, '首次测试前必须已有请求键')
      assert.equal(request.postDataJSON().version, configVersion)
      await waitHeldConfigAction('test')
      if (configTestFailed) {
        configTestFailed = false
        await route.fulfill({ status: 502, json: { status: 502, code: 'AI_PROVIDER_ERROR', detail: '模型连接测试失败。' } })
      } else {
        await route.fulfill({ json: { status: 'SUCCEEDED', message: '测试成功', testedRevision: 1, testedAt: '2026-09-29', requestId: 'test' } })
      }
      return
    }
    if (path === '/api/organizations/1/ai/config/test/by-request-key') {
      assert.equal(request.method(), 'GET')
      assert.equal(new URL(request.url()).searchParams.get('requestKey'), configTestKey)
      await route.fulfill({ json: { status: 'FAILED', message: '模型连接测试失败。', testedRevision: 1, testedAt: '2026-09-29', requestId: 'test', errorCode: 'AI_CONFIG_INVALID' } })
      return
    }
    if (path === '/api/organizations/1/ai/config' && request.method() === 'PUT') {
      const input = request.postDataJSON()
      assert.equal(input.model, '未保存模型')
      if (configSaveConflict) {
        configSaveConflict = false
        await route.fulfill({ status: 409, json: { status: 409, code: 'CONFIG_CONFLICT', detail: '配置版本已变化，请核对后重试。' } })
      } else {
        savedModel = input.model
        configVersion++
        await waitHeldConfigAction('save')
        await route.fulfill({ json: { ...config('1'), enabled: true, available: true, configState: 'ENABLED' } })
      }
      return
    }
    if (path === '/api/organizations/1/ai/config/enable' && request.method() === 'POST') {
      signalEnableStarted()
      await enableGate
      await route.fulfill({ json: { ...config('1'), enabled: true, configState: 'ENABLED' } })
      return
    }
    if (path === '/api/organizations/1/ai/config/clear-key' && request.method() === 'POST') {
      await waitHeldConfigAction('clear-key')
      await route.fulfill({ json: { ...config('1'), hasApiKey: false } })
      return
    }
    if (path === '/api/organizations/1/ai/config/disable' && request.method() === 'POST') {
      await waitHeldConfigAction('disable')
      await route.fulfill({ json: config('1') })
      return
    }
    assert.equal(request.method(), 'GET', `未预设写请求：${path}`)
    let data
    if (path === '/api/auth/me') data = { id: '1', username: '表单核对' }
    else if (path === '/api/auth/csrf') data = { token: 'test', headerName: 'X-CSRF-TOKEN' }
    else if (path === '/api/me/organizations') data = { items: [org('1'), org('2')] }
    else if (path === '/api/me/registrations') data = { items: registration ? [registration] : [], page: 1, pageSize: 20, total: registration ? 1 : 0 }
    else if (path === '/api/public/activities/7') data = { id: '7', organizationName: '公开组织',
      name: '公开活动', description: '', location: '', startTime: null, endTime: null,
      registrationScope: 'PUBLIC', capacity: null, registrationStart: null, registrationEnd: null,
      registrationOpen: false, updatedAt: '2026-09-29' }
    else if (path === '/api/public/activities/7/feedback') data = { count: feedbackSaved ? 1 : 0,
      averageRating: feedbackSaved ? '5.0' : null, mineSubmitted: Boolean(feedbackSaved),
      myRating: feedbackSaved?.rating ?? null, myComment: feedbackSaved?.comment ?? null,
      myVersion: feedbackSaved ? 1 : null,
      canSubmit: true, submitBlockedReason: null }
    else if (path === '/api/public/activities/8') data = { id: '8', organizationName: '公开组织',
      name: '可报名活动', description: '', location: '线上', startTime: '2030-01-01T12:00:00',
      endTime: '2030-01-01T13:00:00', registrationScope: 'PUBLIC', capacity: 30,
      registrationStart: null, registrationEnd: null, registrationOpen: true, updatedAt: '2026-09-29' }
    else if (path === '/api/public/activities/8/feedback') {
      await route.fulfill({ status: 404, json: { status: 404, code: 'NOT_FOUND', detail: '尚未进入复盘。' } })
      return
    }
    else if (path === '/api/public/activities') data = { items: [], page: 1, pageSize: 20, total: 0 }
    else if (/^\/api\/organizations\/[12]$/.test(path)) data = org(path.at(-1))
    else if (/^\/api\/organizations\/[12]\/ai\/config$/.test(path)) data = config(path.split('/')[3])
    else if (path.endsWith('/workbench')) data = { role: 'MEMBER', cards: [], todos: [], myCards: [] }
    else data = { items: [], total: 0 }
    await route.fulfill({ json: data })
  })

  await page.goto(`${base}/public/activities/7`, { waitUntil: 'networkidle' })
  await page.locator('[data-public-feedback]').waitFor()
  const comment = page.locator('[data-public-feedback-comment] textarea, textarea[data-public-feedback-comment]')
  await page.locator('[data-public-feedback-rating] .el-rate__item').nth(4).click()
  await comment.fill('公开反馈未保存草稿')
  await page.locator('.public-breadcrumb a').click()
  const feedbackLeave = page.getByRole('dialog', { name: '未保存的修改' })
  await feedbackLeave.waitFor({ timeout: 3000 })
  await feedbackLeave.getByRole('button', { name: '继续编辑' }).click()
  assert.equal(new URL(page.url()).pathname, '/public/activities/7')
  assert.equal(await comment.inputValue(), '公开反馈未保存草稿')
  await page.locator('[data-public-feedback-submit]').click()
  await page.locator('[data-public-feedback]').getByRole('alert').filter({ hasText: '反馈冲突' }).waitFor({ timeout: 3000 })
  assert.equal(await comment.inputValue(), '公开反馈未保存草稿')
  await page.screenshot({ path: fileURLToPath(new URL('公开反馈失败保留输入.png', output)) })
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, '公开反馈手机页不得横向溢出')
  await page.screenshot({ path: fileURLToPath(new URL('公开反馈失败-390.png', output)) })
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.locator('[data-public-feedback-submit]').click()
  await page.waitForFunction(() => document.querySelector('[data-public-feedback-submit]')?.textContent?.includes('更新反馈'))
  assert.equal(feedbackSaved?.comment, '公开反馈未保存草稿')
  await page.locator('.public-breadcrumb a').click()
  await page.waitForURL('**/public/activities')

  await page.goto(`${base}/public/activities/8`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '我要报名' }).click()
  const applyDialog = page.getByRole('dialog', { name: '报名', exact: true })
  const applyName = applyDialog.getByPlaceholder('报名姓名（1-64 字符）')
  const applyNote = applyDialog.getByPlaceholder('选填，例如联系方式用途说明')
  await applyName.fill('未保存报名姓名')
  await applyNote.fill('报名备注草稿')
  await applyDialog.getByRole('button', { name: '取消' }).click()
  const applyLeave = page.getByRole('dialog', { name: '未保存的修改' })
  await applyLeave.waitFor({ timeout: 3000 })
  await applyLeave.getByRole('button', { name: '继续编辑' }).click()
  assert.equal(await applyNote.inputValue(), '报名备注草稿', '取消放弃后应保留报名草稿')
  await applyDialog.locator('.el-dialog__headerbtn').click()
  await applyLeave.getByRole('button', { name: '放弃修改' }).click()
  await applyDialog.waitFor({ state: 'hidden' })
  await page.getByRole('button', { name: '我要报名' }).click()
  assert.equal(await applyNote.inputValue(), '', '确认放弃后重新打开应恢复原值')
  await applyName.fill('未保存报名姓名')
  await applyNote.fill('报名备注草稿')
  await page.evaluate(() => {
    void document.querySelector('#app').__vue_app__.config.globalProperties.$router.push('/public/activities')
  })
  await applyLeave.waitFor({ timeout: 3000 })
  await applyLeave.getByRole('button', { name: '继续编辑' }).click()
  assert.equal(new URL(page.url()).pathname, '/public/activities/8')
  assert.equal(await applyNote.inputValue(), '报名备注草稿')
  await applyDialog.getByRole('button', { name: '提交' }).click()
  await applyDialog.getByRole('alert').filter({ hasText: '报名服务暂不可用' }).waitFor({ timeout: 3000 })
  assert.equal(await applyName.inputValue(), '未保存报名姓名')
  assert.equal(await applyNote.inputValue(), '报名备注草稿')
  await page.screenshot({ path: fileURLToPath(new URL('公开报名失败保留输入.png', output)) })
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, '报名弹窗手机页不得横向溢出')
  const applyBox = await applyDialog.locator('.el-dialog').boundingBox()
  const submitBox = await applyDialog.getByRole('button', { name: '提交' }).boundingBox()
  assert.ok(applyBox && applyBox.x >= 0 && applyBox.x + applyBox.width <= 390, '报名弹窗须完整位于手机视口内')
  assert.ok(submitBox && submitBox.x >= 0 && submitBox.x + submitBox.width <= 390, '报名提交按钮须在手机视口内')
  await page.screenshot({ path: fileURLToPath(new URL('公开报名失败-390.png', output)) })
  await page.setViewportSize({ width: 1280, height: 720 })
  await applyDialog.getByRole('button', { name: '提交' }).click()
  await applyDialog.waitFor({ state: 'hidden' })
  await page.getByText('待审核', { exact: true }).waitFor()
  assert.equal(registration?.displayName, '未保存报名姓名')
  await page.locator('.public-breadcrumb a').click()
  await page.waitForURL('**/public/activities')

  await page.goto(`${base}/org/1/settings`, { waitUntil: 'networkidle' })
  const model = page.locator('[data-ai-field="model"] input, input[data-ai-field="model"]')
  await model.fill('')
  await page.locator('[data-ai-action="save"]').click()
  await page.locator('.el-form-item__error').getByText('请填写模型 ID', { exact: true }).waitFor()
  await page.waitForFunction(() => !document.querySelector('[data-ai-field="model"] input, input[data-ai-field="model"]').disabled)
  await model.fill('未保存模型')
  await page.evaluate(() => {
    void document.querySelector('#app').__vue_app__.config.globalProperties.$router.push('/org/2/settings')
  })
  const settingsLeave = page.getByRole('dialog', { name: '未保存的修改' })
  await settingsLeave.waitFor({ timeout: 3000 })
  await settingsLeave.getByRole('button', { name: '继续编辑' }).click()
  assert.equal(new URL(page.url()).pathname, '/org/1/settings')
  assert.equal(await model.inputValue(), '未保存模型')
  await page.locator('[data-ai-action="test"]').click()
  await page.getByRole('alert').filter({ hasText: '请先保存配置' }).waitFor({ timeout: 3000 })
  assert.equal(await model.inputValue(), '未保存模型', '测试旧配置不得丢掉未保存的模型 ID')
  await page.locator('[data-ai-action="save"]').click()
  await page.getByRole('alert').filter({ hasText: '配置版本已变化' }).waitFor()
  assert.equal(await model.inputValue(), '未保存模型', '保存冲突不得丢掉草稿')
  const heldSave = holdConfigAction('save')
  await page.locator('[data-ai-action="save"]').click()
  await heldSave.started
  const saveLocked = await checkConfigBusy('save')
  const keyInput = page.locator('[data-ai-field="apiKey"] input, input[data-ai-field="apiKey"]')
  if (!saveLocked) {
    await model.fill('等待响应时新输入的模型')
    await keyInput.fill('fixture-key-never-sent')
  }
  heldSave.release()
  await page.getByText('配置已保存并生效', { exact: false }).waitFor()
  console.log(JSON.stringify({ saveLocked,
    inputOverwritten: !saveLocked && await model.inputValue() === '未保存模型',
    newKeyCleared: !saveLocked && await keyInput.inputValue() === '' }))
  assert.ok(saveLocked, 'AI 配置保存期间应锁定字段与其他动作，避免响应覆盖新输入')
  assert.equal(await model.isDisabled(), false, '保存完成后恢复编辑')
  assert.equal(savedModel, '未保存模型')
  const heldTest = holdConfigAction('test')
  await page.locator('[data-ai-action="test"]').click()
  await heldTest.started
  const testLocked = await checkConfigBusy('test')
  heldTest.release()
  await page.getByRole('alert').filter({ hasText: '连接测试结果尚未确认' }).waitFor()
  assert.ok(testLocked, '连接测试期间锁定字段与其他动作')
  assert.equal(await model.isDisabled(), true, '未知测试结果冻结原配置')
  await page.locator('[data-ai-action="check-test"]').click()
  await page.locator('.ai-config__notice').getByText('模型连接测试失败。', { exact: false }).waitFor()
  assert.equal(configTestPosts, 1, '核对不得再次发起模型请求')
  assert.equal(await model.isDisabled(), false, '明确FAILED后恢复编辑')
  await page.screenshot({ path: fileURLToPath(new URL('AI设置测试失败提示.png', output)), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, 'AI 设置手机页不得横向溢出')
  await page.screenshot({ path: fileURLToPath(new URL('AI设置测试失败-390.png', output)), fullPage: true })
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.evaluate(() => {
    void document.querySelector('#app').__vue_app__.config.globalProperties.$router.push('/org/2/settings')
  })
  await page.waitForURL('**/org/2/settings')
  await page.locator('[data-ai-role="member"]').waitFor()
  assert.equal(await page.locator('[data-ai-field="model"]').count(), 0, 'B 成员不得看到 A 的配置表单')
  pauseOldConfig = true
  await page.evaluate(() => {
    void document.querySelector('#app').__vue_app__.config.globalProperties.$router.push('/org/1/settings')
  })
  await page.waitForURL('**/org/1/settings')
  await page.locator('[data-ai-config-skeleton]').waitFor()
  await page.evaluate(() => {
    void document.querySelector('#app').__vue_app__.config.globalProperties.$router.push('/org/2/settings')
  })
  await page.waitForURL('**/org/2/settings')
  await page.locator('[data-ai-role="member"]').waitFor()
  const staleResponse = page.waitForResponse(response => response.url().endsWith('/organizations/1/ai/config'))
  releaseOldConfig()
  await staleResponse
  await page.waitForLoadState('networkidle')
  assert.equal(await page.locator('[data-ai-role="member"]').count(), 1, '迟到的 A 配置不得覆盖 B 的成员视图')
  await page.evaluate(() => {
    void document.querySelector('#app').__vue_app__.config.globalProperties.$router.push('/org/1/settings')
  })
  await page.locator('[data-ai-action="enable"]').waitFor()
  await page.locator('[data-ai-action="enable"]').click()
  await enableStarted
  const enableLocked = await checkConfigBusy('enable')
  await page.evaluate(() => {
    void document.querySelector('#app').__vue_app__.config.globalProperties.$router.push('/org/2/settings')
  })
  assert.equal(new URL(page.url()).pathname, '/org/1/settings', '启用请求未结束时不得切到另一组织')
  releaseEnable()
  await page.locator('.ai-config__notice').getByText('模型已启用', { exact: false }).waitFor()
  assert.ok(enableLocked, '启用期间锁定字段与其他动作')
  assert.equal(await model.isDisabled(), false, '启用完成后恢复编辑')
  const heldDisable = holdConfigAction('disable')
  await page.locator('[data-ai-action="disable"]').click()
  await heldDisable.started
  const disableLocked = await checkConfigBusy('disable')
  heldDisable.release()
  await page.locator('.ai-config__notice').getByText('模型已停用', { exact: false }).waitFor()
  assert.ok(disableLocked, '停用期间锁定字段与其他动作')
  assert.equal(await model.isDisabled(), false, '停用完成后恢复编辑')
  const heldClear = holdConfigAction('clear-key')
  await page.locator('[data-ai-action="clear-key"]').click()
  await heldClear.started
  const clearLocked = await checkConfigBusy('clear-key')
  heldClear.release()
  await page.locator('[data-ai-action="clear-key"]').waitFor({ state: 'hidden' })
  assert.ok(clearLocked, '清除密钥期间锁定字段与其他动作')
  assert.equal(await model.isDisabled(), false, '清除完成后恢复编辑')
  await page.evaluate(() => {
    void document.querySelector('#app').__vue_app__.config.globalProperties.$router.push('/org/2/settings')
  })
  await page.locator('[data-ai-role="member"]').waitFor()
  assert.deepEqual(errors, [])
  await writeFile(new URL('结果.json', output), JSON.stringify({ status: 'PASS',
    publicRegistration: '取消、右上角关闭与路由离开保护，503 失败就近显示并保留输入，手动重试成功',
    publicFeedback: '离开取消保留、409 冲突保留并重试、保存后正常离开',
    aiSettings: '未保存切换取消、保存冲突保留并重试、旧配置测试失败显示错误、A 迟到配置不覆盖 B、启用期间不能切走',
    configBusyChecks,
    validationRestoresEditing: true,
    pageErrors: errors, scope: '真实 Vue 页面与全部 API 替身；未写数据库或调用模型' }, null, 2))
  console.log('PASS：公开报名、反馈与 AI 设置草稿、失败重试、组织权限刷新（浏览器 API 替身）')
} finally {
  await browser.close()
}
