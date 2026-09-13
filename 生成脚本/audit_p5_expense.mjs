// P5 经费真机核对：预算与统计、登记支出（含凭证）、提交、超支确认弹框、作废与更正、窄屏布局。
// 仅可针对显式连接 activity_test 的后端运行，会新增专用测试数据。
import assert from 'node:assert/strict'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')

const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4])

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const shots = []
try {
  const stamp = Date.now()
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
  const org = await json(owner, 'POST', '/api/organizations', { name: `P5核对-${stamp}` })
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

  const detailPath = `/org/${org.id}/activities/${activity.id}`
  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
  const page = await owner.newPage({ viewport: { width: 1440, height: 1000 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))

  // 1. 经费区块与初始统计（预算 100.00，尚无支出）
  await page.goto(detailPath, { waitUntil: 'networkidle' })
  await page.waitForSelector('text=经费', { timeout: 15000 })
  const initialBody = await page.locator('body').innerText()

  // 2. 界面登记两笔支出：60 + 60 触发超支
  async function addExpense(purpose, amount) {
    await page.getByRole('button', { name: '登记支出' }).click()
    await page.locator('.el-dialog').getByPlaceholder('例如：横幅制作').fill(purpose)
    await page.locator('.el-dialog').locator('input[role="spinbutton"]').fill(String(amount))
    await page.locator('.el-dialog').getByPlaceholder('没有票据时必填').fill('供应商未开票')
    await page.locator('.el-dialog').getByRole('button', { name: '保存' }).click()
    await page.waitForSelector(`text=${purpose}`, { timeout: 15000 })
  }
  await addExpense('场地费', 60)
  await addExpense('餐饮费', 60)
  const afterCreateBody = await page.locator('body').innerText()

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
  await voidedRow.getByRole('button', { name: '更正登记' }).click()
  await page.waitForSelector('.el-message-box', { timeout: 10000 })
  await page.locator('.el-message-box').getByRole('button', { name: '建立更正' }).click()
  await page.waitForTimeout(1000)
  const correctionRows = (await json(owner, 'GET', expensesPath)).items
    .filter((x) => x.purpose.includes('更正'))
  assert.equal(correctionRows.length, 1, '更正登记未建立')
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

  console.log(JSON.stringify({
    check: 'P5 经费真机核对',
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
} finally {
  await browser.close()
}
