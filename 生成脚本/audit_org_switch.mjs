// 组织切换与未保存保护：全部 API 替身，不写库，不替代后端权限验收。
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const staleError = process.argv.includes('--stale-error')
if (process.argv.includes('--loading-states')) {
  await auditLoadingStates()
} else if (process.argv.includes('--interaction-basics')) {
  await auditInteractionBasics()
} else try {
  const context = await browser.newContext({ reducedMotion: 'reduce' })
  const errors = []
  let releaseOld
  const oldGate = new Promise(resolve => { releaseOld = resolve })
  let first = true
  let oldReturned
  const oldDone = new Promise(resolve => { oldReturned = resolve })
  const org = (id, extra = {}) => ({ id, name: `组织${id}`, role: 'OWNER', memberCount: 3,
    joinedAt: '2026-09-27', createdAt: '2026-09-27', description: '', ...extra })
  let savedSummary = '原始总结'
  let summaryVersion = 1
  let summarySaveConflict = true
  let savedFeedback = null
  let feedbackSaveConflict = true
  await context.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/organizations/1/activities/2/feedback' && route.request().method() === 'POST') {
      const input = route.request().postDataJSON()
      assert.equal(input.rating, 5)
      assert.equal(input.comment, '反馈未保存草稿')
      if (feedbackSaveConflict) {
        feedbackSaveConflict = false
        await route.fulfill({ status: 409, json: { status: 409, code: 'FEEDBACK_CONFLICT', detail: '请刷新查看已保存的反馈，再决定是否修改。' } })
        return
      }
      savedFeedback = input
      await route.fulfill({ json: { feedbackId: '1', accountId: 1, accountUsername: '组织切换核对',
        ...input, version: 1, createdAt: '2026-09-28', updatedAt: '2026-09-28' } })
      return
    }
    if (path === '/api/organizations/1/activities/2/summary' && route.request().method() === 'PUT') {
      if (summarySaveConflict) {
        summarySaveConflict = false
        summaryVersion++
        await route.fulfill({ status: 409, json: { status: 409, code: 'EDIT_CONFLICT', detail: '版本已变化，请刷新后重试。' } })
        return
      }
      const input = JSON.parse(route.request().postData() ?? '{}')
      assert.equal(input.version, summaryVersion, '冲突后重试须使用刷新后的版本')
      savedSummary = input.summary
      await route.fulfill({ json: { summary: savedSummary, version: ++summaryVersion } })
      return
    }
    assert.equal(route.request().method(), 'GET', '仅允许预设的写请求替身')
    if (path === '/api/organizations/1' && first) {
      first = false
      await oldGate
      await route.fulfill(staleError
        ? { status: 404, json: { status: 404, code: 'NOT_FOUND', detail: '旧请求组织不可用' } }
        : { json: org('1', { memberCount: 99 }) })
      oldReturned()
      return
    }
    let data
    if (path === '/api/auth/me') data = { id: '1', username: '组织切换核对' }
    else if (path === '/api/auth/csrf') data = { token: 'test', headerName: 'X-CSRF-TOKEN' }
    else if (path === '/api/me/organizations') data = { items: [org('1'), org('2', { role: 'MEMBER' })] }
    else if (/^\/api\/organizations\/[12]$/.test(path)) data = org(path.at(-1), { role: path.endsWith('2') ? 'MEMBER' : 'OWNER' })
    else if (path.endsWith('/workbench')) data = { role: 'MEMBER', cards: [], todos: [], myCards: [] }
    else if (path.endsWith('/activities/1')) data = { id: '1', name: '待编辑活动', status: 'DRAFT',
      version: 1, proposal: '原始方案', ownerMemberId: '1', ownerUsername: '组织切换核对', budget: '0.00', registrationScope: 'INTERNAL' }
    else if (path.endsWith('/activities/2')) data = { id: '2', name: '待复盘活动', status: 'REVIEWING',
      version: summaryVersion, summary: savedSummary, ownerMemberId: '1', ownerUsername: '组织切换核对', budget: '0.00', registrationScope: 'INTERNAL' }
    else if (path.endsWith('/members')) data = { items: [{ memberId: '1', username: '组织切换核对' }], total: 1 }
    else if (path.endsWith('/execution-records')) data = { items: [] }
    else if (path.endsWith('/feedback')) data = { count: savedFeedback ? 1 : 0,
      averageRating: savedFeedback ? '5.0' : null, items: [], mineSubmitted: Boolean(savedFeedback),
      myRating: savedFeedback?.rating ?? null, myComment: savedFeedback?.comment ?? null,
      myVersion: savedFeedback ? 1 : null,
      canSubmit: true, submitBlockedReason: null }
    else data = { items: [], total: 0 }
    await route.fulfill({ json: data })
  })
  const page = await context.newPage()
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('http://127.0.0.1:5174/org/1', { waitUntil: 'domcontentloaded' })
  async function switchTo(id) {
    await page.locator('.organization-select').click()
    await page.getByRole('option').filter({ hasText: `组织${id}` }).click()
  }
  await switchTo('2')
  await page.waitForURL('**/org/2')
  await page.locator('.u-meta').filter({ hasText: '3 位成员' }).waitFor()
  assert.equal((await page.locator('.u-meta').innerText()).includes('组织负责人'), false, 'B 不得沿用 A 的负责人身份')
  await switchTo('1')
  await page.waitForURL('**/org/1')
  await page.locator('.u-meta').filter({ hasText: '3 位成员' }).waitFor()
  releaseOld()
  await oldDone
  await page.waitForLoadState('networkidle')
  assert.ok((await page.locator('.u-meta').innerText()).includes('3 位成员'), 'A→B→A 后旧 A 响应不得覆盖新 A')

  await page.goto('http://127.0.0.1:5174/org/1/activities/1?tab=proposal', { waitUntil: 'networkidle' })
  const editor = page.locator('textarea').first()
  await editor.fill('未保存的方案')
  await switchTo('2')
  const confirm = page.getByRole('dialog', { name: '未保存的修改' })
  await confirm.getByRole('button', { name: '继续编辑' }).click()
  assert.equal(new URL(page.url()).pathname, '/org/1/activities/1')
  assert.equal(await editor.inputValue(), '未保存的方案')
  assert.equal(await page.locator('.organization-select').getAttribute('title'), '组织1')
  await switchTo('2')
  await confirm.getByRole('button', { name: '离开', exact: true }).click()
  await page.waitForURL('**/org/2')
  await page.waitForLoadState('networkidle')
  assert.equal(await page.locator('textarea').count(), 0)

  await page.goto('http://127.0.0.1:5174/org/1/activities/1', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '编辑信息' }).click()
  const editDialog = page.getByRole('dialog', { name: '编辑活动信息' })
  await editDialog.getByRole('button', { name: '取消' }).click()
  await editDialog.waitFor({ state: 'hidden' })
  await page.getByRole('button', { name: '编辑信息' }).click()
  await editDialog.getByRole('textbox', { name: '活动主题' }).fill('未保存的活动主题')
  await editDialog.getByRole('button', { name: '取消' }).click()
  const editConfirm = page.getByRole('dialog', { name: '未保存的修改' })
  await editConfirm.getByRole('button', { name: '继续编辑' }).click()
  assert.equal(await editDialog.getByRole('textbox', { name: '活动主题' }).inputValue(), '未保存的活动主题')
  await editDialog.locator('.el-dialog__headerbtn').click()
  await editConfirm.getByRole('button', { name: '放弃修改' }).click()
  await editDialog.waitFor({ state: 'hidden' })
  await page.getByRole('button', { name: '编辑信息' }).click()
  assert.equal(await editDialog.getByRole('textbox', { name: '活动主题' }).inputValue(), '待编辑活动')
  await editDialog.getByRole('button', { name: '取消' }).click()

  await page.goto('http://127.0.0.1:5174/org/1/activities/2?tab=lifecycle', { waitUntil: 'networkidle' })
  const summary = page.getByPlaceholder(/目标、执行概况/)
  await summary.fill('复盘总结草稿，尚未保存')
  const summaryConflict = page.waitForResponse(response => response.url().endsWith('/activities/2/summary') && response.request().method() === 'PUT')
  await page.getByRole('button', { name: '保存总结' }).click()
  assert.equal((await summaryConflict).status(), 409)
  await page.getByRole('alert').filter({ hasText: '版本已变化' }).waitFor()
  await page.waitForLoadState('networkidle')
  assert.equal(await summary.inputValue(), '复盘总结草稿，尚未保存', '保存冲突刷新不得覆盖总结草稿')
  await page.getByRole('tab', { name: '概览' }).click()
  await switchTo('2')
  const summaryConfirm = page.getByRole('dialog', { name: '未保存的修改' })
  await summaryConfirm.waitFor({ timeout: 5000 })
  await summaryConfirm.getByRole('button', { name: '继续编辑' }).click()
  assert.equal(new URL(page.url()).pathname, '/org/1/activities/2')
  await page.getByRole('tab', { name: '执行与复盘' }).click()
  assert.equal(await summary.inputValue(), '复盘总结草稿，尚未保存')
  const summarySaved = page.waitForResponse(response => response.url().endsWith('/activities/2/summary') && response.request().method() === 'PUT')
  await page.getByRole('button', { name: '保存总结' }).click()
  await page.getByRole('dialog', { name: '活动已更新', exact: true }).getByRole('button', { name: '继续保存', exact: true }).click()
  assert.equal((await summarySaved).status(), 200)
  await page.waitForLoadState('networkidle')
  assert.equal(savedSummary, '复盘总结草稿，尚未保存')
  assert.equal(await summary.inputValue(), savedSummary, '重试成功后不得显示旧总结')
  await summary.fill('再次编辑但未保存')
  await switchTo('2')
  await summaryConfirm.getByRole('button', { name: '离开', exact: true }).click()
  await page.waitForURL('**/org/2')

  await page.goto('http://127.0.0.1:5174/org/1/activities/2?tab=lifecycle', { waitUntil: 'networkidle' })
  assert.equal(await summary.inputValue(), savedSummary, '确认离开只丢弃尚未保存的再次编辑')
  await switchTo('2')
  await page.waitForURL('**/org/2')
  assert.equal(await summaryConfirm.count(), 0, '保存成功后切换组织无需放弃提示')

  await page.goto('http://127.0.0.1:5174/org/1/activities/2?tab=lifecycle', { waitUntil: 'networkidle' })
  const feedbackForm = page.locator('.lifecycle-feedback-form')
  const feedbackComment = feedbackForm.getByPlaceholder('选填')
  await feedbackForm.locator('.el-rate__item').nth(4).click()
  await feedbackComment.fill('反馈未保存草稿')
  summarySaveConflict = true
  const refreshConflict = page.waitForResponse(response => response.url().endsWith('/activities/2/summary') && response.request().method() === 'PUT')
  await page.getByRole('button', { name: '保存总结' }).click()
  assert.equal((await refreshConflict).status(), 409)
  await page.waitForLoadState('networkidle')
  assert.equal(await feedbackComment.inputValue(), '反馈未保存草稿', '其他操作冲突刷新不得覆盖反馈草稿')
  const feedbackConflict = page.waitForResponse(response => response.url().endsWith('/activities/2/feedback') && response.request().method() === 'POST')
  await feedbackForm.getByRole('button', { name: '提交反馈' }).click()
  assert.equal((await feedbackConflict).status(), 409)
  await page.getByRole('alert').filter({ hasText: '请刷新查看已保存的反馈' }).waitFor()
  await page.waitForLoadState('networkidle')
  assert.equal(await feedbackComment.inputValue(), '反馈未保存草稿', '反馈冲突刷新不得覆盖评分与文字草稿')
  await switchTo('2')
  const feedbackConfirm = page.getByRole('dialog', { name: '未保存的修改' })
  await feedbackConfirm.waitFor({ timeout: 5000 })
  await feedbackConfirm.getByRole('button', { name: '继续编辑' }).click()
  assert.equal(new URL(page.url()).pathname, '/org/1/activities/2')
  assert.equal(await feedbackComment.inputValue(), '反馈未保存草稿')
  const feedbackSaved = page.waitForResponse(response => response.url().endsWith('/activities/2/feedback') && response.request().method() === 'POST')
  await feedbackForm.getByRole('button', { name: '提交反馈' }).click()
  assert.equal((await feedbackSaved).status(), 200)
  await page.waitForLoadState('networkidle')
  assert.equal(await feedbackComment.inputValue(), '反馈未保存草稿')
  await switchTo('2')
  await page.waitForURL('**/org/2')
  assert.equal(await feedbackConfirm.count(), 0, '反馈保存成功后切换组织无需放弃提示')

  await page.setViewportSize({ width: 390, height: 844 })
  await page.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
  assert.deepEqual(errors, [])
  const output = new URL('../验证截图/组织切换/', import.meta.url)
  await mkdir(output, { recursive: true })
  await writeFile(new URL(staleError ? '旧错误结果.json' : '结果.json', output), JSON.stringify({ status: 'PASS', staleError, staleResponseIgnored: true,
    cancelKeepsDraft: true, confirmLeaves: true, activityEditProtected: true, summaryConflictKeepsDraft: true,
    summaryLeaveProtected: true, summaryRetrySaved: savedSummary === '复盘总结草稿，尚未保存',
    feedbackRefreshKeepsDraft: true, feedbackConflictKeepsDraft: true,
    feedbackLeaveProtected: true, feedbackRetrySaved: Boolean(savedFeedback),
    errors, scope: '浏览器真实页面、全部 API 替身' }, null, 2))
  console.log('PASS：组织切换乱序与未保存保护')
} finally {
  await browser.close()
}

// 定点复核首次账号读取、内部报名草稿和 AI 人工改稿；全部 API 截获，不写库。
async function auditInteractionBasics() {
  const only = process.argv.find(arg => arg.startsWith('--case='))?.slice(7)
  const checks = []
  const errors = []
  const check = (name, passed) => checks.push({ name, passed })
  const context = await browser.newContext({ reducedMotion: 'reduce' })
  const page = await context.newPage()
  page.setDefaultTimeout(5000)
  page.on('pageerror', error => errors.push(error.message))
  let releaseReads
  let readGate = null
  let noOrganizations = false
  let previewFails = false
  let registrationWrites = 0
  let releaseWrite
  let writeGate = null
  let writeStarted
  let mine = null
  const organization = id => ({ id, name: `组织${id}`, role: 'OWNER', memberCount: 3,
    description: '', joinedAt: '2026-10-03', createdAt: '2026-10-03' })
  const activity = id => ({ id, name: id === '1' ? 'AI验收草稿' : '内部验收活动',
    status: id === '1' ? 'DRAFT' : 'APPROVED', version: 1, proposal: '原始方案', description: '',
    ownerMemberId: '1', ownerUsername: '交互验收', budget: '0.00', registrationScope: 'INTERNAL',
    registrationOpen: true, capacity: 20, location: '活动室', startTime: '2030-10-04T10:00:00+08:00', endTime: '2030-10-04T12:00:00+08:00' })
  const registration = { registrationId: '9', activityId: '3', accountId: 2, accountUsername: '其他成员',
    displayName: '其他成员', note: '', status: 'PENDING', version: 1, cancelReason: null, decidedAt: null,
    createdAt: '2026-10-03T10:00:00+08:00', activityName: '内部验收活动', organizationName: '组织1',
    location: '活动室', startTime: '2030-10-04T10:00:00+08:00', endTime: '2030-10-04T12:00:00+08:00',
    registrationOpen: true, adjusting: false }
  await context.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    if (method === 'POST' && (path === '/api/activities/3/registrations' || path.endsWith('/registrations/9/reject'))) {
      registrationWrites++
      writeStarted?.()
      if (writeGate) await writeGate
      await route.fulfill({ status: 503, json: { status: 503, code: 'UNAVAILABLE', detail: '报名操作暂时失败，请重试。' } })
      return
    }
    if (path.endsWith('/ai/generations') && method === 'POST') {
      await route.fulfill({ status: 201, json: { generationId: '7', type: 'ACTIVITY_PLAN', status: 'SUCCEEDED',
        activityId: '1', output: '模型原始草稿', errorCode: null, errorMessage: null,
        protocol: 'OPENAI_CHAT_COMPLETIONS', model: 'test-model', endpointHost: 'localhost',
        scope: ['当前活动'], missing: [], sourceChanged: false, createdAt: '2026-10-03',
        adoptedAt: null, adoptedBy: null, requestId: 'interaction-ai' } })
      return
    }
    assert.equal(method, 'GET', `不允许未约定的写请求：${method} ${path}`)
    if (readGate && (path === '/api/me/organizations' || path === '/api/me/registrations')) await readGate
    if (path.endsWith('/ai/input') && previewFails) {
      await route.fulfill({ status: 503, json: { status: 503, code: 'UNAVAILABLE', detail: '输入范围读取失败，请重试。' } })
      return
    }
    let data
    if (path === '/api/auth/me') data = { id: '1', username: '交互验收' }
    else if (path === '/api/auth/csrf') data = { token: 'fixture-token', headerName: 'X-CSRF-TOKEN' }
    else if (path === '/api/me/organizations') data = { items: noOrganizations ? [] : [organization('1'), organization('2')] }
    else if (path === '/api/me/registrations') data = { items: mine ? [mine] : [] }
    else if (/^\/api\/organizations\/[12]$/.test(path)) data = organization(path.at(-1))
    else if (path.endsWith('/members')) data = { items: [{ memberId: '1', accountId: '1', username: '交互验收', role: 'OWNER', joinedAt: '2026-10-03' }], total: 1 }
    else if (path.endsWith('/activities')) data = { items: [activity('1'), activity('3')], total: 2 }
    else if (/\/activities\/[13]$/.test(path)) data = activity(path.at(-1))
    else if (path.endsWith('/registrations')) data = { items: [registration], total: 1, page: 1, pageSize: 20 }
    else if (path.endsWith('/ai/input')) data = { available: true, availabilityHint: '模型可用', sourceHash: 'fixture', scope: ['当前活动'], missing: [] }
    else data = { items: [], total: 0, unread: 0 }
    await route.fulfill({ json: data })
  })
  const visible = locator => locator.waitFor({ state: 'visible', timeout: 1000 }).then(() => true, () => false)
  const beforeUnloadProtected = () => page.evaluate(() => !window.dispatchEvent(new Event('beforeunload', { cancelable: true })))
  async function openActivity(id, tab) {
    await page.goto('http://127.0.0.1:5174/org/1/activities', { waitUntil: 'networkidle' })
    await page.getByRole('button', { name: id === '1' ? 'AI验收草稿' : '内部验收活动', exact: true }).click()
    await page.getByRole('tab', { name: tab, exact: true }).click()
    await page.waitForLoadState('networkidle')
  }
  try {
    if (!only || only === 'account') {
      readGate = new Promise(resolve => { releaseReads = resolve })
      await page.goto('http://127.0.0.1:5174/', { waitUntil: 'domcontentloaded' })
      await page.locator('.account-page').waitFor()
      await page.waitForTimeout(400)
      const text = await page.locator('.account-page').innerText()
      check('account:loadingIsNotEmpty', !text.includes('尚未创建或加入任何组织') && !text.includes('暂无报名记录'))
      check('account:twoLoadingSections', await page.locator('.account-page .el-skeleton').count() === 2)
      mine = { ...registration, accountId: 1, accountUsername: '交互验收' }
      releaseReads()
      readGate = null
      await page.getByText('内部验收活动', { exact: true }).waitFor()
      await page.waitForLoadState('networkidle')
      check('account:readyHasNoSkeleton', await page.locator('.account-page .el-skeleton').count() === 0)
      noOrganizations = true
      await page.reload({ waitUntil: 'networkidle' })
      check('account:noOrganizationKeepsRegistrations', (await page.locator('.account-page').innerText()).includes('尚未创建或加入任何组织')
        && await page.getByText('内部验收活动', { exact: true }).count() === 1)
      noOrganizations = false
      mine = null
    }
    if (!only || only === 'registration') {
      for (const kind of ['apply', 'reject']) {
        await openActivity('3', '报名')
        await page.locator('.reg-panel').getByRole('button', { name: kind === 'apply' ? '我要报名' : '驳回', exact: true }).click()
        const dialog = page.getByRole('dialog', { name: kind === 'apply' ? '报名' : '驳回报名', exact: true })
        const field = dialog.locator('textarea')
        await field.fill(`${kind}未保存内容`)
        check(`${kind}:refreshProtected`, await beforeUnloadProtected())
        await dialog.getByRole('button', { name: '取消', exact: true }).click()
        const confirm = page.getByRole('dialog', { name: '未保存的修改', exact: true })
        const closeProtected = await visible(confirm)
        check(`${kind}:closeProtected`, closeProtected)
        if (closeProtected) {
          await confirm.getByRole('button', { name: '继续编辑', exact: true }).click()
          check(`${kind}:cancelKeepsDraft`, await field.inputValue() === `${kind}未保存内容`)
          await dialog.getByRole('button', { name: '取消', exact: true }).click()
          await confirm.getByRole('button', { name: '放弃填写', exact: true }).click()
        }
        await page.locator('.reg-panel').getByRole('button', { name: kind === 'apply' ? '我要报名' : '驳回', exact: true }).click()
        await field.fill(`${kind}后退草稿`)
        await page.evaluate(() => history.go(-2))
        const backProtected = await visible(confirm)
        check(`${kind}:backProtected`, backProtected)
        if (backProtected) {
          await confirm.getByRole('button', { name: '继续编辑', exact: true }).click()
          await confirm.waitFor({ state: 'hidden' })
          await page.waitForURL('**/org/1/activities/3**')
          check(`${kind}:backKeepsDraft`, await field.inputValue() === `${kind}后退草稿`)
          await page.evaluate(() => history.go(-2))
          await confirm.getByRole('button', { name: '离开', exact: true }).click()
        }
        await openActivity('3', '报名')
        await page.locator('.reg-panel').getByRole('button', { name: kind === 'apply' ? '我要报名' : '驳回', exact: true }).click()
        await field.fill(`${kind}发送原内容`)
        writeGate = new Promise(resolve => { releaseWrite = resolve })
        const started = new Promise(resolve => { writeStarted = resolve })
        registrationWrites = 0
        await dialog.getByRole('button', { name: kind === 'apply' ? '提交' : '确认驳回', exact: true }).evaluate(button => { button.click(); button.click() })
        await started
        await page.waitForTimeout(80)
        check(`${kind}:oneWrite`, registrationWrites === 1)
        check(`${kind}:sendingLocksInput`, await field.isDisabled())
        await page.keyboard.press('Escape')
        check(`${kind}:sendingKeepsDialog`, await dialog.isVisible())
        await page.evaluate(() => history.go(-2))
        await page.waitForTimeout(100)
        check(`${kind}:sendingKeepsRoute`, new URL(page.url()).pathname === '/org/1/activities/3')
        releaseWrite()
        writeGate = null
        await page.waitForLoadState('networkidle')
        check(`${kind}:failureKeepsInput`, await dialog.isVisible() && await field.inputValue() === `${kind}发送原内容`)
        if (await dialog.isVisible()) {
          await dialog.getByRole('button', { name: '取消', exact: true }).click()
          if (await visible(confirm)) await confirm.getByRole('button', { name: '放弃填写', exact: true }).click()
        }
      }
    }
    if (!only || only === 'ai') {
      previewFails = true
      await openActivity('1', '方案')
      const panel = page.locator('[data-ai-type="ACTIVITY_PLAN"]')
      check('ai:readFailureIsNotLoading', !(await panel.innerText()).includes('正在读取模型可用状态'))
      const retry = panel.getByRole('button', { name: '重新读取', exact: true })
      check('ai:readFailureCanRetry', await retry.count() === 1)
      previewFails = false
      if (await retry.count()) await retry.click()
      else await page.reload({ waitUntil: 'networkidle' })
      await panel.locator('[data-ai-availability]').waitFor()
      await page.waitForLoadState('networkidle')
      await panel.locator('[data-ai-field="topic"]').fill('实验活动')
      await panel.locator('[data-ai-field="goal"]').fill('保护人工改稿')
      await panel.locator('[data-ai-action="generate"]').click()
      const draft = panel.locator('[data-ai-output]')
      await draft.waitFor()
      await draft.fill('人工修改但尚未采用')
      check('ai:editedDraftRefreshProtected', await beforeUnloadProtected())
      // reload 分支会清空 SPA 历史，使用现有导航入口触发同一离开守卫。
      await page.locator('a[href="/org/1/activities"]').evaluate(link => link.click())
      const confirm = page.getByRole('dialog', { name: '未保存的修改', exact: true })
      const protectedDraft = await visible(confirm)
      check('ai:editedDraftLeaveProtected', protectedDraft)
      if (protectedDraft) {
        await confirm.getByRole('button', { name: '继续编辑', exact: true }).click()
        check('ai:cancelKeepsEditedDraft', await draft.inputValue() === '人工修改但尚未采用')
        await page.locator('a[href="/org/1/activities"]').evaluate(link => link.click())
        await confirm.getByRole('button', { name: '离开', exact: true }).click()
      }
    }
    check('page:noRuntimeError', errors.length === 0)
    const result = { status: checks.every(item => item.passed) ? 'PASS' : 'FAIL', case: only ?? 'all', checks, errors,
      scope: '正式页面、全部API替身；beforeunload为事件取消断言，不证明真实刷新弹窗或后端持久化。' }
    await writeFile(new URL('../验证截图/集中验收-前端交互结果.json', import.meta.url), JSON.stringify(result, null, 2))
    console.log(JSON.stringify(result))
    assert.ok(checks.every(item => item.passed), `失败项：${checks.filter(item => !item.passed).map(item => item.name).join('、')}`)
  } catch (error) {
    console.error(JSON.stringify({ case: only ?? 'all', checks, errors, failure: error.message,
      url: page.url(), pageText: await page.locator('main').innerText().catch(() => '') }))
    throw error
  } finally {
    releaseReads?.()
    releaseWrite?.()
    await browser.close()
  }
}

// 首读慢响应、失败、重试和编辑保留；不修改其他模式的断言，不透传任何 API。
async function auditLoadingStates() {
  const checks = []
  const errors = []
  const check = (name, passed) => checks.push({ name, passed })
  const output = new URL('../验证截图/加载状态/', import.meta.url)
  await mkdir(output, { recursive: true })
  try {
    for (const kind of ['expense', 'p3', 'registration']) {
      const context = await browser.newContext({ reducedMotion: 'reduce', viewport: { width: 1440, height: 1000 } })
      const page = await context.newPage()
      page.setDefaultTimeout(5000)
      page.on('pageerror', error => errors.push(error.message))
      let mode = 'failed'
      let releaseRead
      let readGate = new Promise(resolve => { releaseRead = resolve })
      let taskReadMode = 'failed'
      let taskReadGate = null
      let releaseTaskRead
      const org = { id: '1', name: '状态验收组织', role: 'OWNER', memberCount: 2, description: '', createdAt: '2026-10-04', joinedAt: '2026-10-04' }
      const activity = { id: '3', name: '状态验收活动', status: 'APPROVED', version: 1,
        ownerMemberId: '1', ownerUsername: '状态验收', description: '', proposal: '验收方案',
        registrationScope: 'INTERNAL', registrationOpen: true, capacity: 10, budget: '100.00',
        startTime: '2030-10-05T10:00:00+08:00', endTime: '2030-10-05T12:00:00+08:00', location: '活动室' }
      const expense = { expenseId: '11', activityId: '3', purpose: '接口返回支出', amount: '12.00',
        incurredOn: '2026-10-04', category: 'OTHER', handledNote: null, noReceiptNote: '无票说明',
        status: 'DRAFT', submittedBy: null, submittedAt: null, decidedBy: null, decidedAt: null,
        rejectReason: null, overBudgetNote: null, voidReason: null, voidedAt: null,
        correctsExpenseId: null, voucherCount: '0', version: 1 }
      const task = { taskId: '12', activityId: '3', title: '接口返回任务', description: null,
        assigneeMemberId: '1', assigneeUsername: '状态验收', priority: 'MEDIUM', dueAt: null,
        status: 'PENDING', completedAt: null, overdue: false, version: 1 }
      const material = { materialId: '13', activityId: '3', name: '接口返回物资', quantity: 1, unit: '件',
        assigneeMemberId: null, assigneeUsername: '', status: 'PENDING', note: null, version: 1 }
      const attachment = { attachmentId: '14', activityId: '3', taskId: null, originalName: '接口资料.txt',
        contentType: 'text/plain', sizeBytes: 3, uploaderUsername: '状态验收', purpose: 'ACTIVITY_DOC', createdAt: '2026-10-04' }
      const registration = { registrationId: '15', activityId: '3', accountId: '2', accountUsername: '报名成员',
        displayName: '接口返回报名', note: '', status: 'PENDING', version: 1, cancelReason: null, decidedAt: null,
        createdAt: '2026-10-04', activityName: activity.name, organizationName: org.name, location: '活动室',
        startTime: activity.startTime, endTime: activity.endTime, registrationOpen: true, adjusting: false }
      const ownRegistration = { ...registration, registrationId: '16', accountId: '1',
        accountUsername: '状态验收', displayName: '本人报名' }
      await context.route('**/api/**', async route => {
        const path = new URL(route.request().url()).pathname
        assert.equal(route.request().method(), 'GET', `专项禁止写请求：${path}`)
        const taskTarget = kind === 'p3' && path.endsWith('/tasks/12/attachments')
        if (taskTarget && taskReadGate) await taskReadGate
        if (taskTarget && taskReadMode === 'failed') {
          await route.fulfill({ status: 503, json: { status: 503, code: 'TEST_UNAVAILABLE', detail: '读取暂时失败，请重试。' } })
          return
        }
        const target = kind === 'expense'
          ? /\/activities\/3\/expenses(?:\/summary)?$/.test(path)
          : kind === 'p3' ? /\/activities\/3\/(?:tasks|materials|attachments)$/.test(path)
            : path === '/api/me/registrations' || path.endsWith('/activities/3/registrations')
        if (target && readGate) await readGate
        if (target && mode === 'failed') {
          await route.fulfill({ status: 503, json: { status: 503, code: 'TEST_UNAVAILABLE', detail: '读取暂时失败，请重试。' } })
          return
        }
        const hasRows = mode === 'rows'
        let data
        if (path === '/api/auth/me') data = { id: '1', username: '状态验收' }
        else if (path === '/api/auth/csrf') data = { token: 'fixture-token', headerName: 'X-CSRF-TOKEN' }
        else if (path === '/api/me/organizations') data = { items: [org] }
        else if (path === '/api/organizations/1') data = org
        else if (path.endsWith('/members')) data = { items: [{ memberId: '1', accountId: '1', username: '状态验收', role: 'OWNER', joinedAt: '2026-10-04' }], total: 1 }
        else if (path.endsWith('/activities')) data = { items: [activity], total: 1 }
        else if (path.endsWith('/activities/3')) data = activity
        else if (path.endsWith('/expenses')) data = { items: hasRows ? [expense] : [], total: hasRows ? 1 : 0, page: 1, pageSize: 20 }
        else if (path.endsWith('/expenses/summary')) data = { budget: '100.00', pendingAmount: '0.00', approvedAmount: '0.00', balance: '100.00', total: hasRows ? 1 : 0 }
        else if (path.endsWith('/tasks')) data = { items: hasRows ? [task] : [] }
        else if (path.endsWith('/materials')) data = { items: hasRows ? [material] : [] }
        else if (taskTarget) data = { items: taskReadMode === 'rows' ? [{ ...attachment, taskId: task.taskId, originalName: '任务接口资料.txt' }] : [] }
        else if (path.endsWith('/attachments')) data = { items: hasRows ? [attachment] : [] }
        else if (path === '/api/me/registrations') data = { items: hasRows ? [ownRegistration] : [] }
        else if (path.endsWith('/activities/3/registrations')) data = { items: hasRows ? [registration] : [], total: hasRows ? 1 : 0, page: 1, pageSize: 20 }
        else data = { items: [], total: 0, unread: 0 }
        await route.fulfill({ json: data })
      })
      const panel = page.locator(kind === 'expense' ? '.expense-section' : kind === 'p3' ? '.p3-sections' : '.reg-panel')
      const skeletons = panel.locator('[data-expense-skeleton], [data-p3-skeleton], [data-registration-skeleton]')
      const tableCount = kind === 'p3' ? 3 : 1
      const emptyLabels = kind === 'expense' ? ['暂无支出记录。'] : kind === 'p3'
        ? ['暂无任务。', '暂无物资。', '暂无资料。'] : ['暂无报名记录。', '尚未报名']
      const errorLabel = kind === 'expense' ? '经费信息加载失败，请重试。' : kind === 'p3'
        ? '任务与资料加载失败，请重试。' : '报名信息加载失败，请重试。'
      // 草稿阶段通过父页已可调用的 reload 暴露读取，不触发刷新或保存。
      const reloadPanel = () => panel.evaluate(element => {
        for (let component = element.__vueParentComponent; component; component = component.parent) {
          if (typeof component.exposed?.reload === 'function') { void component.exposed.reload(); return }
        }
        throw new Error('未找到面板现有 reload 暴露')
      })
      const noEmpty = async () => !emptyLabels.some(label => (panelText ?? '').includes(label))
      let panelText = ''
      try {
        await page.goto('http://127.0.0.1:5174/org/1/activities', { waitUntil: 'networkidle' })
        await page.getByRole('button', { name: activity.name, exact: true }).click()
        await page.getByRole('tab', { name: kind === 'expense' ? '经费' : kind === 'p3' ? '任务与物资' : '报名', exact: true }).click()
        await skeletons.first().waitFor()
        panelText = await panel.innerText()
        check(`${kind}:slowSkeleton`, await skeletons.count() === tableCount)
        check(`${kind}:slowNoEmptyOrError`, await noEmpty() && !panelText.includes(errorLabel))
        check(`${kind}:slowNoTables`, await panel.locator('.el-table').count() === 0)
        await page.screenshot({ path: fileURLToPath(new URL(`${kind}-慢读取.png`, output)), fullPage: true })
        releaseRead()
        readGate = null
        await panel.getByRole('alert').filter({ hasText: errorLabel }).first().waitFor()
        await page.waitForLoadState('networkidle')
        await skeletons.first().waitFor({ state: 'hidden' })
        panelText = await panel.innerText()
        check(`${kind}:failedHasError`, panelText.includes(errorLabel))
        check(`${kind}:failedNoLoading`, !panelText.includes('正在加载') && await skeletons.count() === 0)
        check(`${kind}:failedNoEmptyOrTables`, await noEmpty() && await panel.locator('.el-table').count() === 0)
        await page.screenshot({ path: fileURLToPath(new URL(`${kind}-503.png`, output)), fullPage: true })
        mode = 'empty'
        readGate = new Promise(resolve => { releaseRead = resolve })
        const retryButton = panel.getByRole('button', { name: '重新加载', exact: true })
        if (kind === 'registration') check(`${kind}:failedHasRetry`, await retryButton.count() === 1)
        if (await retryButton.count() === 1) await retryButton.click()
        else await reloadPanel()
        await skeletons.first().waitFor()
        panelText = await panel.innerText()
        check(`${kind}:retryLoadingOnly`, await noEmpty() && !panelText.includes(errorLabel) && await skeletons.count() === tableCount)
        releaseRead()
        readGate = null
        await page.waitForLoadState('networkidle')
        for (const label of emptyLabels) await panel.getByText(label, { exact: true }).waitFor()
        panelText = await panel.innerText()
        check(`${kind}:retryEmpty`, emptyLabels.every(label => panelText.includes(label)) && !panelText.includes('正在加载')
          && !panelText.includes(errorLabel) && await skeletons.count() === 0)
        mode = 'rows'
        await reloadPanel()
        await panel.getByText(kind === 'expense' ? expense.purpose : kind === 'p3' ? task.title : registration.displayName, { exact: true }).waitFor()
        await page.waitForLoadState('networkidle')
        check(`${kind}:rowsReplaceEmpty`, await panel.locator('.el-table__row').count() === tableCount && await skeletons.count() === 0)
        await page.screenshot({ path: fileURLToPath(new URL(`${kind}-条目.png`, output)), fullPage: true })
        const forms = kind === 'expense'
          ? [{ block: panel, title: '编辑支出', placeholder: '例如：横幅制作', initial: expense.purpose }]
          : kind === 'p3' ? [{ block: panel.locator('.p3-block').nth(0), title: '编辑任务', placeholder: '例如：联系场地并确认报价', initial: task.title },
            { block: panel.locator('.p3-block').nth(1), title: '编辑物资', placeholder: '1-128 字符', initial: material.name }]
            : [{ block: panel, title: '更新报名信息', action: '更新报名信息', placeholder: '报名姓名（1-64 字符）', initial: ownRegistration.displayName },
              { block: panel, title: '驳回报名', action: '驳回', placeholder: '不超过 500 字符', initial: '' }]
        for (const form of forms) {
          await form.block.getByRole('button', { name: form.action ?? (form.title === '编辑任务' ? '编辑/改派' : '编辑'), exact: true }).click()
          const dialog = kind === 'registration'
            ? page.getByRole('dialog').filter({ has: page.getByPlaceholder(form.placeholder, { exact: true }) })
            : page.getByRole('dialog', { name: form.title, exact: true })
          const field = dialog.getByPlaceholder(form.placeholder, { exact: true })
          const value = `${form.title}未保存草稿`
          await field.fill(value)
          mode = 'failed'
          await reloadPanel()
          await panel.getByRole('alert').filter({ hasText: errorLabel }).first().waitFor()
          await page.waitForLoadState('networkidle')
          check(`${kind}:${form.title}:failedKeepsDraft`, await field.inputValue() === value)
          mode = 'empty'
          await reloadPanel()
          for (const label of emptyLabels) await panel.getByText(label, { exact: true }).waitFor()
          check(`${kind}:${form.title}:emptyKeepsDraft`, await field.inputValue() === value)
          mode = 'rows'
          await reloadPanel()
          await panel.getByText(kind === 'expense' ? expense.purpose : kind === 'p3' ? task.title : registration.displayName, { exact: true }).waitFor()
          check(`${kind}:${form.title}:rowsKeepDraft`, await field.inputValue() === value)
          await field.fill(form.initial)
          await dialog.getByRole('button', { name: '取消', exact: true }).click()
        }
        if (kind === 'p3') {
          const openTaskFiles = () => panel.locator('.p3-block').nth(0).getByRole('button', { name: '资料', exact: true }).click()
          const dialog = page.getByRole('dialog', { name: `任务资料：${task.title}`, exact: true })
          const taskSkeleton = dialog.locator('[data-p3-skeleton="task-attachments"]')
          taskReadGate = new Promise(resolve => { releaseTaskRead = resolve })
          await openTaskFiles()
          await taskSkeleton.waitFor()
          check('p3:taskFiles:slowSkeleton', await taskSkeleton.count() === 1)
          check('p3:taskFiles:slowNoEmptyOrError', !(await dialog.innerText()).includes('该任务暂无资料。') && await dialog.getByRole('alert').count() === 0)
          check('p3:taskFiles:slowNoTables', await dialog.locator('.el-table').count() === 0)
          releaseTaskRead()
          taskReadGate = null
          await dialog.getByRole('alert').filter({ hasText: '读取暂时失败，请重试。' }).waitFor()
          await taskSkeleton.waitFor({ state: 'hidden' })
          const failedText = await dialog.innerText()
          check('p3:taskFiles:failedHasError', failedText.includes('读取暂时失败，请重试。'))
          check('p3:taskFiles:failedNoLoading', await taskSkeleton.count() === 0 && !failedText.includes('正在加载'))
          check('p3:taskFiles:failedNoEmptyOrTables', !failedText.includes('该任务暂无资料。') && await dialog.locator('.el-table').count() === 0)
          const retry = dialog.getByRole('button', { name: '重新加载', exact: true })
          check('p3:taskFiles:failedHasRetry', await retry.count() === 1)
          await page.screenshot({ path: fileURLToPath(new URL('p3-任务资料503.png', output)), fullPage: true })
          taskReadMode = 'empty'
          taskReadGate = new Promise(resolve => { releaseTaskRead = resolve })
          if (await retry.count() === 1) await retry.click()
          else {
            await dialog.locator('.el-dialog__headerbtn').click()
            await openTaskFiles()
          }
          await taskSkeleton.waitFor()
          check('p3:taskFiles:retryLoadingOnly', await dialog.getByRole('alert').count() === 0 && !(await dialog.innerText()).includes('该任务暂无资料。') && await dialog.locator('.el-table').count() === 0)
          releaseTaskRead()
          taskReadGate = null
          await dialog.getByText('该任务暂无资料。', { exact: true }).waitFor()
          check('p3:taskFiles:retryEmpty', await taskSkeleton.count() === 0 && await dialog.getByRole('alert').count() === 0)
          await dialog.locator('.el-dialog__headerbtn').click()
          taskReadMode = 'rows'
          await openTaskFiles()
          await dialog.getByText('任务接口资料.txt', { exact: true }).waitFor()
          check('p3:taskFiles:rowsReplaceEmpty', await dialog.locator('.el-table__row').count() === 1 && !(await dialog.innerText()).includes('该任务暂无资料。') && await taskSkeleton.count() === 0)
          await dialog.locator('.el-dialog__headerbtn').click()
        }
      } finally {
        releaseRead?.()
        releaseTaskRead?.()
        await context.close()
      }
    }
    await auditLifecycleLoadingStates(check, errors, output)
    check('page:noRuntimeError', errors.length === 0)
    const result = { status: checks.every(item => item.passed) ? 'PASS' : 'FAIL', checks, errors,
      scope: '正式5174页面、全部GET接口替身且不透传、不写库；延迟闸门模拟慢读取，编辑草稿通过组件现有reload暴露核对；Lifecycle覆盖OWNER与非活动负责人MEMBER，不触发历史读取；不证明后端权限、持久化或整站状态验收。' }
    await writeFile(new URL('结果.json', output), JSON.stringify(result, null, 2))
    console.log(JSON.stringify(result))
    assert.ok(checks.every(item => item.passed), `失败项：${checks.filter(item => !item.passed).map(item => item.name).join('、')}`)
  } finally {
    await browser.close()
  }
}

async function auditLifecycleLoadingStates(check, errors, output) {
  for (const role of ['OWNER', 'MEMBER']) {
    const context = await browser.newContext({ reducedMotion: 'reduce', viewport: { width: 1440, height: 1000 } })
    const page = await context.newPage()
    page.setDefaultTimeout(5000)
    page.on('pageerror', error => errors.push(error.message))
    const prefix = `lifecycle:${role}`
    let mode = 'failed'
    let releaseRead
    let readGate = new Promise(resolve => { releaseRead = resolve })
    const org = { id: '1', name: '复盘状态组织', role, memberCount: 2, description: '', createdAt: '2026-10-04', joinedAt: '2026-10-04' }
    const activity = { id: '3', name: '复盘状态活动', status: 'REVIEWING', version: 1,
      ownerMemberId: '2', ownerUsername: '其他负责人', description: '', proposal: '验收方案',
      summary: null, summaryConfirmedAt: null, archivedAt: null, cancelledAt: null,
      registrationScope: 'INTERNAL', registrationOpen: false, capacity: 10, budget: '100.00',
      startTime: '2026-10-01T10:00:00+08:00', endTime: '2026-10-01T12:00:00+08:00', location: '活动室' }
    const record = { recordId: '21', category: 'PROGRESS', content: '接口返回执行记录',
      recordedBy: '2', recordedByUsername: '其他负责人', createdAt: '2026-10-04T10:00:00+08:00' }
    const feedback = { feedbackId: '22', accountId: '1', accountUsername: '状态成员', rating: 4, version: 1,
      comment: '接口返回反馈', createdAt: '2026-10-04', updatedAt: '2026-10-04' }
    await context.route('**/api/**', async route => {
      const path = new URL(route.request().url()).pathname
      assert.equal(route.request().method(), 'GET', `专项禁止写请求：${path}`)
      assert.ok(!path.includes('/confirmations'), '本专项不触发确认历史')
      const target = /\/activities\/3\/(?:execution-records|feedback)$/.test(path)
      if (target && readGate) await readGate
      if (target && mode === 'failed') {
        await route.fulfill({ status: 503, json: { status: 503, code: 'TEST_UNAVAILABLE', detail: '读取暂时失败，请重试。' } })
        return
      }
      const hasRows = mode === 'rows'
      let data
      if (path === '/api/auth/me') data = { id: '1', username: '状态成员' }
      else if (path === '/api/auth/csrf') data = { token: 'fixture-token', headerName: 'X-CSRF-TOKEN' }
      else if (path === '/api/me/organizations') data = { items: [org] }
      else if (path === '/api/organizations/1') data = org
      else if (path.endsWith('/members')) data = { items: [{ memberId: '1', accountId: '1', username: '状态成员', role, joinedAt: '2026-10-04' }], total: 1 }
      else if (path.endsWith('/activities')) data = { items: [activity], total: 1 }
      else if (path.endsWith('/activities/3')) data = activity
      else if (path.endsWith('/execution-records')) data = { items: hasRows ? [record] : [] }
      else if (path.endsWith('/feedback')) data = { count: hasRows ? 1 : 0, averageRating: hasRows ? '4.0' : null,
        items: hasRows ? [feedback] : [], mineSubmitted: hasRows, myRating: hasRows ? 4 : null,
        myComment: hasRows ? feedback.comment : null, myVersion: hasRows ? 1 : null, canSubmit: true, submitBlockedReason: null }
      else data = { items: [], total: 0, unread: 0 }
      await route.fulfill({ json: data })
    })
    const panel = page.locator('.lifecycle-section')
    const loading = panel.getByRole('status').filter({ hasText: '加载执行记录与反馈中…' })
    const hasNoReadData = async () => {
      const value = await panel.innerText()
      return !value.includes('暂无执行记录。') && !value.includes('份数') && !value.includes('暂无反馈')
        && await panel.locator('.lifecycle-records, .lifecycle-feedback-list').count() === 0
    }
    try {
      await page.goto('http://127.0.0.1:5174/org/1/activities', { waitUntil: 'networkidle' })
      await page.getByRole('button', { name: activity.name, exact: true }).click()
      await page.getByRole('tab', { name: '执行与复盘', exact: true }).click()
      await loading.waitFor()
      check(`${prefix}:slowVisible`, await panel.isVisible())
      check(`${prefix}:slowLoadingOnly`, await hasNoReadData() && await panel.getByRole('alert').count() === 0)
      releaseRead()
      readGate = null
      await panel.getByRole('alert').filter({ hasText: '生命周期信息加载失败，请重试。' }).waitFor()
      await loading.waitFor({ state: 'hidden' })
      check(`${prefix}:failedVisible`, await panel.isVisible())
      check(`${prefix}:failedOnly`, await loading.count() === 0 && await hasNoReadData())
      const retry = panel.getByRole('button', { name: '重试', exact: true })
      check(`${prefix}:failedHasRetry`, await retry.count() === 1)
      await page.screenshot({ path: fileURLToPath(new URL(`lifecycle-${role}-503.png`, output)), fullPage: true })
      mode = 'empty'
      readGate = new Promise(resolve => { releaseRead = resolve })
      await retry.click()
      await loading.waitFor()
      check(`${prefix}:retryLoadingOnly`, await hasNoReadData() && await panel.getByRole('alert').count() === 0)
      releaseRead()
      readGate = null
      await panel.getByText('暂无执行记录。', { exact: true }).waitFor()
      const emptyText = await panel.innerText()
      check(`${prefix}:retryZeroState`, emptyText.includes('份数 0') && emptyText.includes('平均评分 —') && emptyText.includes('暂无反馈')
        && await loading.count() === 0 && await panel.getByRole('alert').count() === 0)
      mode = 'rows'
      await panel.evaluate(element => {
        for (let component = element.__vueParentComponent; component; component = component.parent) {
          if (typeof component.exposed?.reload === 'function') { void component.exposed.reload(); return }
        }
        throw new Error('未找到复盘区现有 reload 暴露')
      })
      await panel.getByText(record.content, { exact: true }).waitFor()
      check(`${prefix}:recordsReplaceEmpty`, await panel.locator('.lifecycle-records li').count() === 1 && !(await panel.innerText()).includes('暂无执行记录。'))
      const rowsText = await panel.innerText()
      check(`${prefix}:feedbackReplacesZero`, rowsText.includes('份数 1') && rowsText.includes('平均评分 4.0') && !rowsText.includes('暂无反馈')
        && await panel.getByPlaceholder('选填', { exact: true }).inputValue() === feedback.comment)
      check(`${prefix}:roleFixtureMatches`, await panel.getByRole('button', { name: '保存总结', exact: true }).count() === (role === 'OWNER' ? 1 : 0))
    } finally {
      releaseRead?.()
      await context.close()
    }
  }
}
