// 演示数据准备：注册两个演示账号，建一个组织并造出可浏览的活动（复盘中／草稿／公开报名）。
// 只写入 activity_test；重复运行会新建一组同名前缀账号（用户名带时间戳后缀）。
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') {
  throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')
}
const base = 'http://127.0.0.1:5174'
const stamp = Date.now().toString(36).slice(-4)
const password = 'Demo12345!'
const ownerName = `demo_owner_${stamp}`
const memberName = `demo_member_${stamp}`

const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  async function login(ctx, name) {
    for (let attempt = 1; ; attempt += 1) {
      // 注册入口匿名可用但不豁免 CSRF，先取令牌再提交
      const csrfBefore = await (await ctx.request.get('/api/auth/csrf')).json()
      const res = await ctx.request.post('/api/auth/register', {
        data: { username: name, password },
        headers: { [csrfBefore.headerName]: csrfBefore.token },
      })
      if (res.ok()) break
      if (res.status() !== 429 || attempt >= 8) throw new Error(`注册失败 ${res.status()} ${await res.text()}`)
      await new Promise((r) => setTimeout(r, 45000))
    }
    const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
    const login = await ctx.request.post('/api/auth/login', {
      form: { username: name, password }, headers: { [csrf.headerName]: csrf.token },
    })
    if (login.status() !== 204) throw new Error(`登录失败 ${login.status()}`)
  }
  async function call(ctx, method, path, data, extra) {
    const headers = { ...(extra || {}) }
    if (method !== 'GET') {
      const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
      headers[csrf.headerName] = csrf.token
    }
    const res = await ctx.request.fetch(path, { method, headers, ...(data === undefined ? {} : { data }) })
    if (!res.ok()) throw new Error(`${method} ${path} -> ${res.status()} ${await res.text()}`)
    return res.status() === 204 ? null : res.json()
  }
  const owner = await browser.newContext({ baseURL: base })
  const member = await browser.newContext({ baseURL: base })
  await login(owner, ownerName)
  await login(member, memberName)

  const org = await call(owner, 'POST', '/api/organizations', { name: `演示组织 ${stamp}` })
  const op = `/api/organizations/${org.id}`
  const invitation = await call(owner, 'POST', `${op}/invitations`, {})
  await call(member, 'POST', '/api/invitations/accept', { code: invitation.code })
  const members = (await call(owner, 'GET', `${op}/members?pageSize=100`)).items
  const ownerMemberId = members.find((x) => x.username === ownerName).memberId
  const memberMemberId = members.find((x) => x.username === memberName).memberId

  const activityBody = (name, scope, capacity, budget, start, end, extra) => ({
    name, description: '演示活动：用于查看方案、任务、报名、经费、执行与复盘界面',
    location: '礼堂', ownerMemberId: memberMemberId, startTime: start, endTime: end,
    registrationScope: scope, capacity, budget, ...(extra || {}),
  })
  const versionOf = async (path) => (await call(owner, 'GET', path)).version

  // 1) 复盘中的活动：任务、物资、报名、经费、执行记录、反馈、总结都已具备
  const flow = await call(owner, 'POST', `${op}/activities`,
    activityBody('演示活动·已复盘', 'INTERNAL', 20, '1000.00',
      '2027-06-01T14:00:00+08:00', '2027-06-01T16:00:00+08:00'),
    { 'Idempotency-Key': `demo-flow-${stamp}` })
  const fp = `${op}/activities/${flow.id}`
  await call(member, 'PUT', `${fp}/proposal`,
    { content: '方案：分组活动与分享；预算 1000 元，含物料 328.50 元。', version: await versionOf(fp) })
  await call(member, 'POST', `${fp}/submit`)
  await call(owner, 'POST', `${fp}/approve`, { note: '演示确认', version: await versionOf(fp) })
  await call(owner, 'POST', `${fp}/registration-open`, { open: true })
  const task = await call(owner, 'POST', `${fp}/tasks`, {
    title: '场地布置', assigneeMemberId: memberMemberId, priority: 'HIGH',
    description: '提前一天完成桌椅与指引牌布置',
  })
  await call(member, 'POST', `${fp}/tasks/${task.taskId}/transition`, { toStatus: 'IN_PROGRESS' })
  await call(owner, 'POST', `${fp}/tasks`, {
    title: '物料清点', assigneeMemberId: ownerMemberId, priority: 'MEDIUM',
  })
  await call(owner, 'POST', `${fp}/materials`, {
    name: '桌牌', quantity: 20, unit: '个', assigneeMemberId: memberMemberId, note: '提前一天准备',
  })
  const reg = await call(member, 'POST', `/api/activities/${flow.id}/registrations`,
    { displayName: '演示成员', note: '希望参加分享环节' })
  await call(owner, 'POST', `${fp}/registrations/${reg.registrationId}/approve`)
  const expense = await call(member, 'POST', `${fp}/expenses`, {
    purpose: '场地布置物料', amount: '328.50', incurredOn: '2027-06-01', category: 'MATERIAL',
    handledNote: '经手人：演示成员', noReceiptNote: '小票已遗失，附说明',
  }, { 'Idempotency-Key': `demo-exp-${stamp}` })
  await call(member, 'POST', `${fp}/expenses/${expense.expenseId}/submit`, { version: expense.version })
  const pending = (await call(owner, 'GET', `${fp}/expenses`)).items
    .find((x) => x.expenseId === expense.expenseId)
  await call(owner, 'POST', `${fp}/expenses/${expense.expenseId}/confirm`,
    { version: pending.version })
  await call(owner, 'POST', `${fp}/start-execution`, { version: await versionOf(fp) })
  await call(owner, 'POST', `${fp}/execution-records`, { category: 'PROGRESS', content: '按计划完成场地布置' })
  await call(owner, 'POST', `${fp}/execution-records`, { category: 'ISSUE', content: '签到台排队较长，已加开一条通道' })
  await call(owner, 'POST', `${fp}/finish-execution`, { version: await versionOf(fp) })
  await call(member, 'POST', `${fp}/feedback`, { rating: 5, comment: '流程顺畅，建议增加指引牌' })
  await call(owner, 'PUT', `${fp}/summary`, {
    summary: '活动目标：帮助新成员熟悉组织。执行概况：按计划完成，参与 20 人。\n'
      + '任务与报名：任务 2 个已完成，报名 1 人已通过。经费：已确认支出 328.50 元。\n'
      + '反馈要点：流程顺畅，建议增加指引牌。\n改进建议：下次提前一天布置并增加指引牌。',
    version: await versionOf(fp),
  })
  await call(owner, 'POST', `${fp}/tasks/${task.taskId}/transition`, { toStatus: 'COMPLETED' })
  const secondTaskId = (await call(owner, 'GET', `${fp}/tasks`)).items
    .find((x) => x.title === '物料清点').taskId
  await call(owner, 'POST', `${fp}/tasks/${secondTaskId}/transition`, { toStatus: 'COMPLETED' })
  await call(owner, 'POST', `${fp}/summary/confirm`, { version: await versionOf(fp) })
  await call(owner, 'POST', `${fp}/archive`, { version: await versionOf(fp) })
  await call(owner, 'POST', `${fp}/reopen`, { reason: '补充现场说明', version: await versionOf(fp) })

  // 2) 草稿活动：方案编辑与 AI 方案生成入口
  await call(owner, 'POST', `${op}/activities`,
    activityBody('演示活动·草稿', 'INTERNAL', 30, '3000.00',
      '2027-06-20T18:00:00+08:00', '2027-06-20T20:00:00+08:00'),
    { 'Idempotency-Key': `demo-draft-${stamp}` })

  // 3) 公开活动：开放报名，可用手机浏览器体验外部参与
  const publicActivity = await call(owner, 'POST', `${op}/activities`,
    activityBody('演示活动·公开报名', 'PUBLIC', 5, '0.00',
      '2027-07-01T14:00:00+08:00', '2027-07-01T16:00:00+08:00',
      { registrationStart: '2026-01-01T00:00:00+08:00', registrationEnd: '2027-06-28T00:00:00+08:00' }),
    { 'Idempotency-Key': `demo-public-${stamp}` })
  const pp = `${op}/activities/${publicActivity.id}`
  await call(owner, 'PUT', `${pp}/proposal`, { content: '公开活动方案：面向所有登录账号开放报名。', version: await versionOf(pp) })
  await call(owner, 'POST', `${pp}/submit`)
  await call(owner, 'POST', `${pp}/approve`, { note: '演示确认', version: await versionOf(pp) })
  await call(owner, 'POST', `${pp}/registration-open`, { open: true })

  console.log(JSON.stringify({
    ok: true,
    login: { owner: ownerName, member: memberName, password },
    frontend: base,
    org: `${base}/org/${org.id}`,
    activityReviewing: `${base}/org/${org.id}/activities/${flow.id}`,
    activityPublic: `${base}/public/activities/${publicActivity.id}`,
    settings: `${base}/org/${org.id}/settings`,
  }, null, 1))
} finally {
  await browser.close()
}
