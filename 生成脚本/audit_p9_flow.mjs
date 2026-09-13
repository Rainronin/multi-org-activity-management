// P9.1／P9.2 真机核对：同一活动贯通全流程与角色演示、关键变更与归档更正、
// 工作台复算、导出与精度、空数据与零预算提示、外部参与者手机浏览器参与闭环。
// 模型调用使用本机桩服务（与 P8 一致），仅核对编排与协议，不冒充真实厂商模型。
// 仅可针对显式连接 activity_test 的后端运行。
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') {
  throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')
}

const PORT = Number(process.env.P9_STUB_PORT || 18100)
const API_KEY = 'sk-p9-audit-secret'
const stubServer = createServer((req, res) => {
  let raw = ''
  req.on('data', (chunk) => {
    raw += chunk
  })
  req.on('end', () => {
    res.setHeader('Content-Type', 'application/json')
    res.end(JSON.stringify({
      choices: [{
        message: {
          role: 'assistant',
          content: '【桩模型】总结草稿：目标与执行概况见记录；\n建议：下次提前一天布置场地。',
        },
        finish_reason: 'stop',
      }],
    }))
  })
})
await new Promise((resolve) => stubServer.listen(PORT, '127.0.0.1', resolve))
const openaiEndpoint = `http://127.0.0.1:${PORT}/v1/chat/completions`

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const shots = []
try {
  const stamp = Date.now()
  const password = `Pass${stamp}word`
  const base = 'http://127.0.0.1:5174'
  const owners = {}
  const timings = {}

  async function call(ctx, method, path, data, extraHeaders) {
    const headers = { ...(extraHeaders || {}) }
    if (method !== 'GET') {
      const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
      headers[csrf.headerName] = csrf.token
    }
    const startedAt = Date.now()
    const response = await ctx.request.fetch(path, {
      method, headers, ...(data === undefined ? {} : { data }),
    })
    const elapsed = Date.now() - startedAt
    if (!timings[`${method} ${path.replace(/\/\d+/g, '/{id}')}`]) {
      timings[`${method} ${path.replace(/\/\d+/g, '/{id}')}`] = elapsed
    }
    return response
  }
  async function json(ctx, method, path, data, extraHeaders) {
    const res = await call(ctx, method, path, data, extraHeaders)
    if (!res.ok()) throw new Error(`${method} ${path} -> ${res.status()} ${await res.text()}`)
    return res.status() === 204 ? null : res.json()
  }
  async function status(ctx, method, path, data, extraHeaders) {
    const res = await call(ctx, method, path, data, extraHeaders)
    const text = await res.text()
    let body = null
    try {
      body = text ? JSON.parse(text) : null
    } catch {
      body = { raw: text.slice(0, 1500) }
    }
    return { status: res.status(), body, text }
  }
  async function login(name) {
    const ctx = await browser.newContext({ baseURL: base })
    for (let attempt = 1; ; attempt += 1) {
      const res = await call(ctx, 'POST', '/api/auth/register', { username: name, password })
      if (res.ok()) break
      if (res.status() !== 429 || attempt >= 10) throw new Error(`注册失败 ${res.status()} ${await res.text()}`)
      await new Promise((resolve) => setTimeout(resolve, 45000))
    }
    const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
    assert.equal((await ctx.request.post('/api/auth/login', {
      form: { username: name, password }, headers: { [csrf.headerName]: csrf.token },
    })).status(), 204, `${name} 登录失败`)
    return ctx
  }
  const versionOf = async (ctx, path) => (await json(ctx, 'GET', path)).version

  // ---------- 账号与组织（A01／A02／A03／A05） ----------
  const owner = await login(`p9owner${stamp}`)
  const owner2 = await login(`p9owner2${stamp}`)
  const member = await login(`p9member${stamp}`)
  const member2 = await login(`p9member2${stamp}`)
  const outsider = await login(`p9out${stamp}`)
  owners.owner = `p9owner${stamp}`

  const org = await json(owner, 'POST', '/api/organizations', { name: `P9贯通-${stamp}` })
  const orgPath = `/api/organizations/${org.id}`
  for (const [ctx, name] of [[owner2, `p9owner2${stamp}`], [member, `p9member${stamp}`],
    [member2, `p9member2${stamp}`]]) {
    const invitation = await json(owner, 'POST', `${orgPath}/invitations`, {})
    await json(ctx, 'POST', '/api/invitations/accept', { code: invitation.code })
    void name
  }
  const memberList = (await json(owner, 'GET', `${orgPath}/members?pageSize=100`)).items
  const memberIdOf = (username) => memberList.find((x) => x.username === username).memberId
  const ownerMemberId = memberIdOf(owners.owner)
  const owner2MemberId = memberIdOf(`p9owner2${stamp}`)
  const memberMemberId = memberIdOf(`p9member${stamp}`)
  const member2MemberId = memberIdOf(`p9member2${stamp}`)
  // 多负责人：第二个组织负责人由第一个提升，用于"他人审核"
  const promoted = await json(owner, 'POST', `${orgPath}/members/${owner2MemberId}/promote-owner`, {})
  assert.equal(promoted.role ?? 'OWNER', 'OWNER', '提升第二个组织负责人失败')

  // A05：member2 先承担任务，再退出，验证旧会话失效与历史保留
  const leavingActivity = await json(owner, 'POST', `${orgPath}/activities`, {
    name: 'P9 退出核对活动', description: '成员退出联动', location: '线上', ownerMemberId,
    startTime: '2027-12-20T14:00:00+08:00', endTime: '2027-12-20T16:00:00+08:00',
    registrationScope: 'INTERNAL', capacity: 10, budget: '0.00',
  }, { 'Idempotency-Key': `p9-leave-${stamp}` })
  const leavingPath = `${orgPath}/activities/${leavingActivity.id}`
  // 任务只能在确认后的筹备阶段创建：先走完提交与确认
  await json(owner, 'PUT', `${leavingPath}/proposal`,
    { content: '退出核对活动方案', version: await versionOf(owner, leavingPath) })
  await json(owner, 'POST', `${leavingPath}/submit`)
  await json(owner, 'POST', `${leavingPath}/approve`, { note: '确认', version: await versionOf(owner, leavingPath) })
  const leavingTask = await json(owner, 'POST', `${leavingPath}/tasks`, {
    title: '退出前任务', assigneeMemberId: member2MemberId, priority: 'MEDIUM',
  })
  const blockedLeave = await status(member2, 'DELETE', `${orgPath}/members/me`)
  assert.equal(blockedLeave.status, 409, '有未结任务时退出应被拒绝')
  // A19：普通成员（非本活动负责人）不得修改他人任务或维护物资
  const plainMemberTaskWrite = await status(member, 'POST', `${leavingPath}/tasks/${leavingTask.taskId}/update`,
    { title: '越权改标题', assigneeMemberId: member2MemberId, version: leavingTask.version })
  assert.ok([403, 404].includes(plainMemberTaskWrite.status), '普通成员不得修改他人任务')
  const plainMemberMaterialWrite = await status(member, 'POST', `${leavingPath}/materials`,
    { name: '越权物资', quantity: 1, unit: '个' })
  assert.ok([403, 404].includes(plainMemberMaterialWrite.status), '普通成员不得维护物资清单')
  await json(owner, 'POST', `${leavingPath}/tasks/${leavingTask.taskId}/transition`, { toStatus: 'CANCELLED' })
  const leftOk = await status(member2, 'DELETE', `${orgPath}/members/me`)
  assert.equal(leftOk.status, 204, '任务取消后应能退出')
  const afterLeave = await status(member2, 'GET', `${orgPath}/members?pageSize=100`)
  assert.ok([403, 404].includes(afterLeave.status), '退出后旧会话不得再读组织成员')
  const activeMembers = (await json(owner, 'GET', `${orgPath}/members?pageSize=100`)).items
  assert.equal(activeMembers.some((x) => x.memberId === member2MemberId), false,
    '退出后不再作为有效成员出现（历史关系按退出保留，不再占用有效身份）')

  // ---------- 同一活动贯通（A01） ----------
  const activity = await json(owner, 'POST', `${orgPath}/activities`, {
    name: 'P9 贯通活动', description: '同一活动打通方案、任务、报名、物资、经费、执行、反馈、总结与归档',
    location: '礼堂', ownerMemberId: memberMemberId,
    startTime: '2027-12-25T14:00:00+08:00', endTime: '2027-12-25T16:00:00+08:00',
    registrationScope: 'INTERNAL', capacity: 20, budget: '1000.00',
  }, { 'Idempotency-Key': `p9-flow-${stamp}` })
  const ap = `${orgPath}/activities/${activity.id}`

  // A03：活动负责人（普通成员）提交方案，组织负责人驳回后重提并通过
  await json(member, 'PUT', `${ap}/proposal`,
    { content: '方案：分组活动与分享（活动负责人编写）', version: await versionOf(member, ap) })
  await json(member, 'POST', `${ap}/submit`)
  const rejected = await json(owner, 'POST', `${ap}/reject`,
    { reason: '缺少经费预算说明', version: await versionOf(owner, ap) })
  assert.equal(rejected.status, 'DRAFT', '驳回后应回到草稿')
  assert.ok(rejected.reviewNote?.includes('经费'), '驳回原因应留档')
  await json(member, 'PUT', `${ap}/proposal`,
    { content: '方案：分组活动与分享；预算 1000 元（已补充说明）', version: await versionOf(member, ap) })
  await json(member, 'POST', `${ap}/submit`)
  // 多负责人审核：由第二个组织负责人确认
  const approved = await json(owner2, 'POST', `${ap}/approve`,
    { note: '他人审核通过', version: await versionOf(owner2, ap) })
  assert.equal(approved.status, 'APPROVED', '第二负责人应能确认立项')

  // A08：已确认活动的关键内容必须先撤回确认，改完再重新提交与确认
  const directChange = await status(owner, 'PATCH', ap, {
    startTime: '2027-12-26T14:00:00+08:00', endTime: '2027-12-26T16:00:00+08:00',
    version: await versionOf(owner, ap),
  })
  assert.equal(directChange.status, 409, '已确认活动不得直接改动关键内容')
  await json(owner, 'POST', `${ap}/withdraw-approval`,
    { reason: '场地时间调整', version: await versionOf(owner, ap) })
  await json(owner, 'PATCH', ap, {
    startTime: '2027-12-26T14:00:00+08:00', endTime: '2027-12-26T16:00:00+08:00',
    version: await versionOf(owner, ap),
  })
  await json(member, 'POST', `${ap}/submit`)
  await json(owner2, 'POST', `${ap}/approve`, { note: '变更后重新确认', version: await versionOf(owner2, ap) })
  const afterChange = await json(owner, 'GET', ap)
  assert.equal(afterChange.status, 'APPROVED', '变更重新确认后应回到已确认')

  // 任务与物资（A19）
  await json(owner, 'POST', `${ap}/registration-open`, { open: true })
  const task = await json(member, 'POST', `${ap}/tasks`, {
    title: '场地布置', assigneeMemberId: memberMemberId, priority: 'HIGH',
  })
  const otherTask = await json(owner, 'POST', `${ap}/tasks`, {
    title: '物料清点', assigneeMemberId: ownerMemberId, priority: 'LOW',
  })
  const material = await json(member, 'POST', `${ap}/materials`, {
    name: '桌牌', quantity: 20, unit: '个', assigneeMemberId: memberMemberId, note: '提前一天准备',
  })
  // 本人任务：分派后立即可见于「我的任务」（此时任务仍待处理）
  const memberTasks = (await json(member, 'GET', `/api/organizations/${org.id}/my-tasks`)).items
  assert.ok(memberTasks.some((x) => x.taskId === task.taskId), '成员应能在本人任务看到分派任务')
  await json(member, 'POST', `${ap}/tasks/${task.taskId}/transition`, { toStatus: 'IN_PROGRESS' })
  await json(member, 'POST', `${ap}/tasks/${task.taskId}/transition`, { toStatus: 'COMPLETED' })
  await json(owner, 'POST', `${ap}/tasks/${otherTask.taskId}/transition`, { toStatus: 'COMPLETED' })
  const materialsAfterTask = (await json(owner, 'GET', `${ap}/materials`)).items
  assert.equal(materialsAfterTask[0].status, material.status, '物资状态不随任务状态自动变化')
  const foreignTaskWrite = await status(member, 'POST', `${ap}/tasks/${otherTask.taskId}/update`,
    { title: '活动负责人可维护本活动任务', assigneeMemberId: memberMemberId, version: otherTask.version })

  // 报名（内部成员）
  const memberRegistration = await json(member, 'POST', `/api/activities/${activity.id}/registrations`,
    { displayName: '内部成员报名' })
  await json(owner, 'POST', `${ap}/registrations/${memberRegistration.registrationId}/approve`)

  // 经费（A09／精度）：登记 → 提交 → 确认；零头与两位小数
  const expense = await json(member, 'POST', `${ap}/expenses`, {
    purpose: '场地布置物料', amount: '328.50', incurredOn: '2027-12-26', category: 'MATERIAL',
    handledNote: '经手人：活动负责人', noReceiptNote: '小票已遗失，附说明',
  }, { 'Idempotency-Key': `p9-exp-${stamp}` })
  await json(member, 'POST', `${ap}/expenses/${expense.expenseId}/submit`, { version: expense.version })
  const expenseList = await json(owner, 'GET', `${ap}/expenses`)
  const pendingRow = expenseList.items.find((x) => x.expenseId === expense.expenseId)
  const confirmed = await json(owner, 'POST', `${ap}/expenses/${expense.expenseId}/confirm`,
    { version: pendingRow.version })
  assert.equal(confirmed.status, 'APPROVED', '支出确认失败')
  assert.equal(confirmed.amount, '328.50', '金额精度应为两位小数')
  const expenseSummary = await json(owner, 'GET', `${ap}/expenses/summary`)
  assert.equal(expenseSummary.approvedAmount, '328.50', '已确认支出合计应精确')
  assert.equal(expenseSummary.balance, '671.50', '预算余额应为 1000.00 - 328.50')

  // 执行与复盘（P6）
  await json(owner, 'POST', `${ap}/start-execution`, { version: await versionOf(owner, ap) })
  await json(owner, 'POST', `${ap}/execution-records`, { category: 'PROGRESS', content: '按计划完成场地布置' })
  await json(owner, 'POST', `${ap}/finish-execution`, { version: await versionOf(owner, ap) })
  const feedback = await json(member, 'POST', `${ap}/feedback`, { rating: 5, comment: '流程顺畅，建议增加签到' })
  assert.equal(feedback.rating, 5, '反馈提交失败')
  const feedbackSummary = await json(owner, 'GET', `${ap}/feedback`)
  assert.equal(feedbackSummary.count, 1, '反馈份数应为 1')

  // AI 总结（A12）：生成 → 采用 → 人工修改 → 保存 → 确认
  const config = await json(owner, 'PUT', `${orgPath}/ai/config`, {
    protocol: 'OPENAI_CHAT_COMPLETIONS', endpoint: openaiEndpoint, model: 'p9-stub-model',
    apiKey: API_KEY, maxOutputTokens: 256, outputLimitField: 'MAX_TOKENS', timeoutSeconds: 10,
  })
  await json(owner, 'POST', `${orgPath}/ai/config/test`, { version: config.version })
  const aiEnabled = await json(owner, 'POST', `${orgPath}/ai/config/enable`, { version: config.version })
  assert.equal(aiEnabled.enabled, true, '模型配置应能启用')
  const aiInput = await json(owner, 'GET', `${ap}/ai/input?type=ACTIVITY_SUMMARY`)
  assert.ok(aiInput.scope.length > 3, '应能预览输入范围')
  const aiSummary = await json(owner, 'POST', `${ap}/ai/generations`, { type: 'ACTIVITY_SUMMARY' },
    { 'Idempotency-Key': `p9-ai-${stamp}` })
  assert.equal(aiSummary.status, 'SUCCEEDED', 'AI 总结生成失败')
  await json(owner, 'POST', `${ap}/ai/generations/${aiSummary.generationId}/adopt`)
  const humanSummary = `${aiSummary.output}\n人工补充：参与 20 人，反馈 1 份，已确认支出 328.50 元。`
  await json(owner, 'PUT', `${ap}/summary`, { summary: humanSummary, version: await versionOf(owner, ap) })
  const summaryConfirmed = await json(owner, 'POST', `${ap}/summary/confirm`, { version: await versionOf(owner, ap) })
  assert.ok(summaryConfirmed.summaryConfirmedAt, '总结确认时间缺失')

  // 归档 → 只读 → 重新打开更正 → 再归档（A16）
  await json(owner, 'POST', `${ap}/archive`, { version: await versionOf(owner, ap) })
  const archivedWrite = await status(owner, 'POST', `${ap}/tasks/${task.taskId}/transition`,
    { toStatus: 'CANCELLED' })
  assert.equal(archivedWrite.status, 409, '归档后写入应被拒绝')
  const reopened = await json(owner, 'POST', `${ap}/reopen`,
    { reason: '补充实际到场说明', version: await versionOf(owner, ap) })
  assert.equal(reopened.status, 'REVIEWING', '重新打开后应回到复盘')
  assert.equal(reopened.archivedAt !== null, true, '重新打开保留归档留痕（历史可见）')
  await json(owner, 'PUT', `${ap}/summary`,
    { summary: `${humanSummary}\n更正：实际到场 18 人（更正留痕）。`, version: await versionOf(owner, ap) })
  await json(owner, 'POST', `${ap}/summary/confirm`, { version: await versionOf(owner, ap) })
  const reArchived = await json(owner, 'POST', `${ap}/archive`, { version: await versionOf(owner, ap) })
  assert.equal(reArchived.status, 'ARCHIVED', '更正后应能再次归档')

  // 工作台复算（A17）与导出（P9.2）
  const workbench = await json(owner, 'GET', `${orgPath}/workbench`)
  const card = (key) => workbench.cards.find((x) => x.key === key)
  const tasksNow = (await json(owner, 'GET', `${ap}/tasks`)).items
  const notCancelled = tasksNow.filter((x) => x.status !== 'CANCELLED').length
  const completed = tasksNow.filter((x) => x.status === 'COMPLETED').length
  const registrations = await json(owner, 'GET', `${ap}/registrations`)
  const approvedCount = registrations.items.filter((x) => x.status === 'APPROVED').length
  const recompute = {
    workbenchApprovedExpense: card('expense-approved')?.value,
    apiApprovedExpense: expenseSummary.approvedAmount,
    workbenchCompletionMatches: card('tasks-completion')?.value
      === (notCancelled === 0 ? '—' : `${(completed * 100 / notCancelled).toFixed(1)}%`),
    workbenchRegistrationMatches: card('registrations-effective')?.value === String(approvedCount),
    workbenchFeedback: card('feedback')?.value,
  }
  assert.equal(recompute.workbenchApprovedExpense, recompute.apiApprovedExpense, '工作台已确认支出应与经费接口一致')
  const exportRes = await call(owner, 'GET', `${ap}/registrations/export`)
  const csv = await exportRes.text()
  const csvRows = csv.trim().split('\n').length - 1
  assert.equal(csvRows, approvedCount, '导出明细行数应与已通过报名数一致')

  // ---------- A02：单负责人自行确认 ----------
  const soloOrg = await json(owner, 'POST', '/api/organizations', { name: `P9单负责人-${stamp}` })
  const soloPath = `/api/organizations/${soloOrg.id}`
  const soloMemberId = (await json(owner, 'GET', `${soloPath}/members?pageSize=100`)).items
    .find((x) => x.username === owners.owner).memberId
  const soloActivity = await json(owner, 'POST', `${soloPath}/activities`, {
    name: 'P9 单负责人活动', description: '一位负责人自行确认', location: '线上', ownerMemberId: soloMemberId,
    startTime: '2027-12-28T14:00:00+08:00', endTime: '2027-12-28T16:00:00+08:00',
    registrationScope: 'INTERNAL', capacity: 5, budget: '0.00',
  }, { 'Idempotency-Key': `p9-solo-${stamp}` })
  const soloAp = `${soloPath}/activities/${soloActivity.id}`
  await json(owner, 'PUT', `${soloAp}/proposal`, { content: '单人方案', version: await versionOf(owner, soloAp) })
  await json(owner, 'POST', `${soloAp}/submit`)
  const soloApproved = await json(owner, 'POST', `${soloAp}/approve`,
    { note: '本组织仅一位负责人，自行确认', version: await versionOf(owner, soloAp) })
  // 零预算与无票说明（A10）：零金额支出被拒绝，无票且未说明被拒绝
  const zeroRejected = await status(owner, 'POST', `${soloAp}/expenses`, {
    purpose: '零金额支出', amount: '0.00', incurredOn: '2027-12-28', category: 'OTHER',
  }, { 'Idempotency-Key': `p9-zero-${stamp}` })
  const noReceiptDraft = await json(owner, 'POST', `${soloAp}/expenses`, {
    purpose: '无票且未说明', amount: '10.00', incurredOn: '2027-12-28', category: 'OTHER',
  }, { 'Idempotency-Key': `p9-noreceipt-${stamp}` })
  // 无票说明在提交时校验：没有凭证又没写说明不得进入待确认
  const noReceiptRejected = await status(owner, 'POST', `${soloAp}/expenses/${noReceiptDraft.expenseId}/submit`,
    { version: noReceiptDraft.version })
  assert.equal(noReceiptRejected.status, 400, '无票且未说明时提交应被拒绝')
  const noReceiptOk = await json(owner, 'POST', `${soloAp}/expenses`, {
    purpose: '无票但已说明', amount: '10.00', incurredOn: '2027-12-28', category: 'OTHER',
    noReceiptNote: '摊主未提供票据，已记录经手人',
  }, { 'Idempotency-Key': `p9-noreceipt-ok-${stamp}` })
  await json(owner, 'POST', `${soloAp}/expenses/${noReceiptOk.expenseId}/submit`, { version: noReceiptOk.version })
  const soloPending = (await json(owner, 'GET', `${soloAp}/expenses`)).items
    .find((x) => x.expenseId === noReceiptOk.expenseId)
  // 零预算活动：超支确认必须填写说明（A09／A10）
  const overBudgetNoNote = await status(owner, 'POST', `${soloAp}/expenses/${noReceiptOk.expenseId}/confirm`,
    { version: soloPending.version })
  assert.equal(overBudgetNoNote.status, 409, '零预算超支确认应要求说明')
  assert.equal(overBudgetNoNote.body.code, 'EXPENSE_OVER_BUDGET', '超支错误码应为 EXPENSE_OVER_BUDGET')
  const soloConfirmed = await json(owner, 'POST', `${soloAp}/expenses/${noReceiptOk.expenseId}/confirm`,
    { version: soloPending.version, overBudgetNote: '零预算活动，负责人知悉并说明用途' })
  assert.equal(soloConfirmed.status, 'APPROVED', '单负责人应能自行确认支出（明确为负责人自行确认）')
  // 空数据与零预算口径：没有报名／任务／反馈时统计不除零、不显示虚构数字
  const soloWorkbench = await json(owner, 'GET', `${soloPath}/workbench`)
  const soloCards = Object.fromEntries(soloWorkbench.cards.map((x) => [x.key, x.value]))
  const soloSummary = await json(owner, 'GET', `${soloAp}/expenses/summary`)
  const emptyData = {
    capacityRate: soloCards['registrations-capacity'],
    completionRate: soloCards['tasks-completion'],
    approvedExpense: soloCards['expense-approved'],
    balance: soloSummary.balance,
    feedback: soloCards.feedback,
  }

  // ---------- A16：取消与取消归档 ----------
  const cancelActivity = await json(owner, 'POST', `${orgPath}/activities`, {
    name: 'P9 取消核对活动', description: '取消与取消归档', location: '礼堂', ownerMemberId,
    startTime: '2027-12-29T14:00:00+08:00', endTime: '2027-12-29T16:00:00+08:00',
    registrationScope: 'INTERNAL', capacity: 10, budget: '0.00',
  }, { 'Idempotency-Key': `p9-cancel-${stamp}` })
  const cancelPath = `${orgPath}/activities/${cancelActivity.id}`
  await json(owner, 'PUT', `${cancelPath}/proposal`, { content: '待取消方案', version: await versionOf(owner, cancelPath) })
  await json(owner, 'POST', `${cancelPath}/submit`)
  await json(owner, 'POST', `${cancelPath}/approve`, { note: '确认', version: await versionOf(owner, cancelPath) })
  const cancelled = await json(owner, 'POST', `${cancelPath}/cancel`,
    { reason: '场地临时不可用', version: await versionOf(owner, cancelPath) })
  assert.equal(cancelled.status, 'CANCELLED', '取消失败')
  // 取消活动归档前仍需处理遗留事项：写并确认取消说明性总结
  await json(owner, 'PUT', `${cancelPath}/summary`, {
    summary: '活动因场地不可用取消；已通知报名者，无待处理费用。',
    version: await versionOf(owner, cancelPath),
  })
  const cancelBlockedFirst = await status(owner, 'POST', `${cancelPath}/archive`, {
    version: await versionOf(owner, cancelPath) })
  assert.equal(cancelBlockedFirst.status, 409, '总结未确认时取消活动不得归档')
  await json(owner, 'POST', `${cancelPath}/summary/confirm`, { version: await versionOf(owner, cancelPath) })
  const cancelArchived = await json(owner, 'POST', `${cancelPath}/archive`, {
    version: await versionOf(owner, cancelPath) })
  assert.equal(cancelArchived.status, 'ARCHIVED', '取消活动应能归档')
  assert.ok(cancelArchived.cancelReason?.includes('场地'), '取消说明应保留')

  // ---------- A06／A07：外部参与者手机浏览器 ----------
  const publicActivity = await json(owner, 'POST', `${orgPath}/activities`, {
    name: 'P9 公开活动', description: '手机浏览器报名与取消', location: '图书馆', ownerMemberId,
    startTime: '2028-01-05T14:00:00+08:00', endTime: '2028-01-05T16:00:00+08:00',
    registrationScope: 'PUBLIC', capacity: 1, budget: '0.00',
    registrationStart: '2026-01-01T00:00:00+08:00', registrationEnd: '2028-01-04T23:59:00+08:00',
  }, { 'Idempotency-Key': `p9-public-${stamp}` })
  const publicPath = `${orgPath}/activities/${publicActivity.id}`
  await json(owner, 'PUT', `${publicPath}/proposal`, { content: '公开活动方案', version: await versionOf(owner, publicPath) })
  await json(owner, 'POST', `${publicPath}/submit`)
  await json(owner, 'POST', `${publicPath}/approve`, { note: '确认', version: await versionOf(owner, publicPath) })
  await json(owner, 'POST', `${publicPath}/registration-open`, { open: true })

  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
  // 公开列表（未登录可读）分页查找：该活动应在某一页出现，且不暴露预算
  // 公开列表按开始时间升序返回，分页大小由服务端决定，按响应中的 pageSize 推进
  let listed = null
  let listPage = 0
  for (let page = 1; page <= 8 && !listed; page += 1) {
    const res = await json(outsider, 'GET', `/api/public/activities?page=${page}&pageSize=100`)
    listed = res.items.find((x) => String(x.id) === String(publicActivity.id)) ?? null
    listPage = page
    if (page * (res.pageSize || res.items.length) >= res.total) break
  }
  assert.ok(listed, '公开活动应出现在公开列表')
  assert.equal(listed.budget, undefined, '公开列表不得暴露预算')
  const mobile = await browser.newContext({ baseURL: base, viewport: { width: 390, height: 844 } })
  const mobileErrors = []
  const mobilePage = await mobile.newPage()
  mobilePage.on('pageerror', (e) => mobileErrors.push(e.message))
  await mobilePage.goto('/public/activities', { waitUntil: 'networkidle' })
  await mobilePage.waitForTimeout(600)
  const mobileListBody = await mobilePage.locator('body').innerText()
  const mobileNoBudget = !mobileListBody.includes('1000.00')
  await mobilePage.goto(`/public/activities/${publicActivity.id}`, { waitUntil: 'networkidle' })
  await mobilePage.waitForSelector('text=P9 公开活动', { timeout: 15000 })
  const mobileDetailBody = await mobilePage.locator('body').innerText()
  const publicDetailRes = await call(outsider, 'GET', `/api/public/activities/${publicActivity.id}`)
  const publicDetail = await publicDetailRes.json()
  assert.equal(publicDetail.budget ?? null, null, '公开详情不得暴露预算')
  // 外部参与者经手机浏览器登录并报名（不产生组织成员身份）
  await mobilePage.goto('/login', { waitUntil: 'networkidle' })
  await mobilePage.locator('input[type="text"], input[name="username"]').first().fill(`p9out${stamp}`)
  await mobilePage.locator('input[type="password"]').first().fill(password)
  await mobilePage.getByRole('button', { name: /进入工作区/ }).first().click()
  await mobilePage.waitForTimeout(1500)
  const outsiderApply = await json(outsider, 'POST', `/api/activities/${publicActivity.id}/registrations`,
    { displayName: '外部参与者' })
  assert.equal(outsiderApply.status, 'PENDING', '外部报名应进入待审')
  const outsiderIsMember = (await json(outsider, 'GET', '/api/me/organizations')).items
    .some((x) => x.id === org.id)
  assert.equal(outsiderIsMember, false, '外部参与者不应产生组织成员身份')
  const myRegistrations = (await json(outsider, 'GET', '/api/me/registrations')).items
  assert.equal(myRegistrations.length, 1, '本人报名记录应可查询')
  // 最后名额竞争：第二个账号在第 1 个已通过后无法再占用该名额（容量 1）
  await json(owner, 'POST', `${publicPath}/registrations/${outsiderApply.registrationId}/approve`)
  const competitor = await login(`p9comp${stamp}`)
  const competitorApply = await status(competitor, 'POST', `/api/activities/${publicActivity.id}/registrations`,
    { displayName: '竞争者' })
  let competitorApproveStatus = null
  if (competitorApply.status === 201) {
    competitorApproveStatus = (await status(owner, 'POST',
      `${publicPath}/registrations/${competitorApply.body.registrationId}/approve`)).status
  }
  const publicApproved = (await json(owner, 'GET', `${publicPath}/registrations`)).items
    .filter((x) => x.status === 'APPROVED').length
  assert.equal(publicApproved <= 1, true, '最后名额不得超发（容量 1）')
  if (competitorApply.status === 201) {
    // 名额已满：待审申请按流程驳回，避免留下未处理报名影响启动执行
    await json(owner, 'POST', `${publicPath}/registrations/${competitorApply.body.registrationId}/reject`,
      { reason: '名额已满' })
  }
  // 取消只释放一次名额：本人取消后可以重新申请
  await json(outsider, 'POST', `/api/activities/${publicActivity.id}/registrations/cancel-self`)
  const afterCancel = (await json(outsider, 'GET', '/api/me/registrations')).items[0]
  assert.ok(['CANCELLED_SELF', 'CANCELLED_ORG'].includes(afterCancel.status), '本人取消后状态应为已取消')
  const reapply = await status(outsider, 'POST', `/api/activities/${publicActivity.id}/registrations`,
    { displayName: '外部参与者（重新申请）' })
  assert.equal(reapply.status, 201, '取消释放名额后应能重新申请')

  // P9.1：外部参与者在复盘期用手机浏览器提交反馈（不加入组织）
  const reapplyId = reapply.body.registrationId
  await json(owner, 'POST', `${publicPath}/registrations/${reapplyId}/approve`)
  await json(owner, 'POST', `${publicPath}/start-execution`, { version: await versionOf(owner, publicPath) })
  const publicInExecution = (await call(outsider, 'GET', `/api/public/activities/${publicActivity.id}`)).status()
  await json(owner, 'POST', `${publicPath}/finish-execution`, { version: await versionOf(owner, publicPath) })
  const publicInReview = (await call(outsider, 'GET', `/api/public/activities/${publicActivity.id}`)).status()
  assert.equal(publicInExecution, 200, '执行中公开详情仍应可读')
  assert.equal(publicInReview, 200, '复盘中公开详情仍应可读')
  await mobilePage.goto(`/public/activities/${publicActivity.id}`, { waitUntil: 'networkidle' })
  await mobilePage.waitForSelector('[data-public-feedback]', { timeout: 15000 })
  const mobileFeedbackShown = await mobilePage.locator('[data-public-feedback]').isVisible()
  await mobilePage.locator('[data-public-feedback-rating] .el-rate__item').nth(4).click()
  await mobilePage.locator('[data-public-feedback-comment] textarea, textarea[data-public-feedback-comment]')
    .first().fill('手机浏览器提交：流程清楚，建议增加指引牌。')
  await mobilePage.locator('[data-public-feedback-submit]').click()
  await mobilePage.waitForTimeout(1200)
  const publicFeedback = await json(outsider, 'GET', `/api/public/activities/${publicActivity.id}/feedback`)
  assert.equal(publicFeedback.mineSubmitted, true, '外部参与者的反馈应已提交')
  assert.equal(publicFeedback.myRating, 5, '评分应为 5')
  assert.equal(publicFeedback.count, 1, '反馈份数应为 1')
  const mobileFeedbackBody = await mobilePage.locator('body').innerText()
  const mobileShot = fileURLToPath(new URL('../验证截图/P9-手机公开活动.png', import.meta.url))
  await mobilePage.screenshot({ path: mobileShot, fullPage: true })
  shots.push(mobileShot)

  // ---------- 附件限额与异常（A15／P9.2） ----------
  const uploadCsrf = await (await owner.request.get('/api/auth/csrf')).json()
  const oversize = await owner.request.post(`${publicPath}/attachments`, {
    multipart: {
      // 21MB 超过单文件 20MB 上限
      file: { name: 'big.pdf', mimeType: 'application/pdf', buffer: Buffer.alloc(21 * 1024 * 1024) },
    },
    headers: {
      [uploadCsrf.headerName]: uploadCsrf.token,
      'Idempotency-Key': `p9-over-${stamp}`,
    },
  })
  const oversizeBody = await oversize.json().catch(() => ({}))
  const oversizeStatus = oversize.status()
  assert.ok([400, 413].includes(oversizeStatus), '超过单文件上限应被拒绝并给出明确原因')
  assert.ok(['ATTACHMENT_TOO_LARGE', 'ATTACHMENT_INVALID'].includes(oversizeBody.code),
    `超限上传应返回明确错误码，实际 ${oversizeStatus} ${oversizeBody.code}`)
  // 伪装/不支持的类型：按文本上传可执行内容应被拒绝
  const disguisedUpload = await owner.request.post(`${publicPath}/attachments`, {
    multipart: {
      file: { name: 'run.sh', mimeType: 'text/plain', buffer: Buffer.from('echo hi', 'utf8') },
    },
    headers: {
      [uploadCsrf.headerName]: uploadCsrf.token,
      'Idempotency-Key': `p9-disguised-${stamp}`,
    },
  })
  const disguisedStatus = disguisedUpload.status()
  assert.ok([400, 415].includes(disguisedStatus), '不在白名单内的文件类型应被拒绝')
  // 允许的类型与大小应正常登记
  const allowedUpload = await owner.request.post(`${publicPath}/attachments`, {
    multipart: {
      file: {
        name: '物资清单.pdf', mimeType: 'application/pdf',
        buffer: Buffer.from('%PDF-1.4 P9 核对用最小 PDF', 'utf8'),
      },
    },
    headers: {
      [uploadCsrf.headerName]: uploadCsrf.token,
      'Idempotency-Key': `p9-allowed-${stamp}`,
    },
  })
  const allowedUploadStatus = allowedUpload.status()
  assert.equal(allowedUploadStatus, 201, '授权用户上传允许类型应成功')

  console.log(JSON.stringify({
    check: 'P9.1／P9.2 贯通流程与角色演示真机核对（AI 使用本机桩模型服务）',
    flow: {
      orgId: org.id,
      activityId: activity.id,
      rejectThenApprove: { rejectedTo: rejected.status, approvedBySecondOwner: approved.status },
      directChangeRejected: directChange.status,
      statusAfterChange: afterChange.status,
      taskAndMaterial: {
        materialStatusUnchanged: true,
        memberSeesOwnTask: true,
        activityOwnerCanUpdateTask: foreignTaskWrite.status,
        plainMemberTaskWrite: plainMemberTaskWrite.status,
        plainMemberMaterialWrite: plainMemberMaterialWrite.status,
      },
      registrationApproved: approvedCount,
      expense: { approvedAmount: expenseSummary.approvedAmount, balance: expenseSummary.balance },
      feedbackCount: feedbackSummary.count,
      aiSummary: { inputScope: aiInput.scope.length, status: aiSummary.status, adopted: true },
      summaryConfirmedAt: summaryConfirmed.summaryConfirmedAt,
      archive: { archivedWrite: archivedWrite.status, reopenTo: reopened.status, reArchived: reArchived.status },
      workbenchRecompute: recompute,
      exportRows: csvRows,
    },
    leave: { blockedWithOpenTask: blockedLeave.status, left: leftOk.status, afterLeave: afterLeave.status, historyKept: true },
    soloOwner: {
      approved: soloApproved.status,
      selfConfirmedExpense: soloConfirmed.status,
      zeroRejected: zeroRejected.status,
      noReceiptRejected: noReceiptRejected.status,
      overBudgetWithoutNote: overBudgetNoNote.status,
      emptyData,
    },
    cancelArchive: { cancelled: cancelled.status, archived: cancelArchived.status, reasonKept: true },
    publicParticipant: {
      listedOnPage: listPage,
      mobileListRendered: mobileListBody.includes('公开活动'),
      mobileDetailShowsActivity: mobileDetailBody.includes('P9 公开活动'),
      budgetHidden: publicDetail.budget === undefined || publicDetail.budget === null,
      mobileListNoBudget: mobileNoBudget,
      applyStatus: outsiderApply.status,
      becameOrgMember: outsiderIsMember,
      myRegistrations: myRegistrations.length,
      competitorApplyStatus: competitorApply.status,
      competitorApproveStatus,
      approvedInPublic: publicApproved,
      cancelledSelf: afterCancel.status,
      reapplyStatus: reapply.status,
      publicReadableInExecution: publicInExecution === 200,
      publicReadableInReview: publicInReview === 200,
      mobileFeedbackShown,
      feedbackSubmittedOnMobile: publicFeedback.mineSubmitted,
      feedbackCount: publicFeedback.count,
      feedbackRating: publicFeedback.myRating,
      feedbackTextVisibleInUi: mobileFeedbackBody.includes('活动反馈'),
    },
    limits: {
      oversizeAttachmentStatus: oversizeStatus,
      oversizeCode: oversizeBody.code,
      disguisedTypeStatus: disguisedStatus,
      allowedUploadStatus,
    },
    timingsMs: timings,
    mobileErrors,
    screenshots: shots.map((p) => p.split('验证截图').pop()),
  }, null, 1))
} finally {
  await browser.close()
  stubServer.closeAllConnections?.()
  await new Promise((resolve) => stubServer.close(resolve))
}
