// 8bfa94c 修复复核观察脚本：仅可针对显式连接 activity_test 的后端运行，会新增专用测试数据。
import assert from 'node:assert/strict'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')
const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const contexts = await Promise.all([1, 2, 3].map(() => browser.newContext({ baseURL: 'http://127.0.0.1:5174', viewport: { width: 1440, height: 1000 } })))
  const [owner, member, outsider] = contexts
  async function call(ctx, method, path, data, multipart) {
    const headers = {}
    if (method !== 'GET') {
      const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
      headers[csrf.headerName] = csrf.token
    }
    return ctx.request.fetch(path, { method, headers, ...(data === undefined ? {} : { data }), ...(multipart ? { multipart } : {}) })
  }
  async function json(ctx, method, path, data) {
    const res = await call(ctx, method, path, data)
    assert.ok(res.ok(), `准备流程失败: ${method} ${path} ${res.status()}`)
    return res.json()
  }
  const usernames = []
  for (let i = 0; i < contexts.length; i++) {
    const ctx = contexts[i], username = `audit${Date.now()}p${i}`, password = `Pass${Date.now()}word`
    usernames.push(username)
    await json(ctx, 'POST', '/api/auth/register', { username, password })
    const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
    assert.equal((await ctx.request.post('/api/auth/login', { form: { username, password }, headers: { [csrf.headerName]: csrf.token } })).status(), 204)
  }
  const org = await json(owner, 'POST', '/api/organizations', { name: '阶段审核甲' })
  const foreignOrg = await json(outsider, 'POST', '/api/organizations', { name: '阶段审核乙' })
  const invitation = await json(owner, 'POST', `/api/organizations/${org.id}/invitations`, {})
  await json(member, 'POST', '/api/invitations/accept', { code: invitation.code })
  const members = (await json(owner, 'GET', `/api/organizations/${org.id}/members`)).items
  const ownerMember = members.find(x => x.username === usernames[0]).memberId
  const memberId = members.find(x => x.username === usernames[1]).memberId
  const path = `/api/organizations/${org.id}/activities`
  async function createActivity(name, assignee = ownerMember) {
    const activity = await json(owner, 'POST', path, { name, description: '阶段审核活动', ownerMemberId: assignee, startTime: new Date(Date.now()+86400000).toISOString(), endTime: new Date(Date.now()+90000000).toISOString(), location: '会议室', capacity: 2, registrationScope: 'INTERNAL' })
    await json(owner, 'PUT', `${path}/${activity.id}/proposal`, { content: '人工方案审核样例' })
    return activity
  }
  async function approveActivity(activity) {
    await json(owner, 'POST', `${path}/${activity.id}/submit`)
    await json(owner, 'POST', `${path}/${activity.id}/approve`, { note: '审核通过' })
  }
  const activity = await createActivity('正式活动审核', memberId)
  await approveActivity(activity)
  const aPath = `${path}/${activity.id}`
  console.log(JSON.stringify({ check: '活动负责人列表', total: (await json(member, 'GET', `${path}?status=APPROVED`)).total }))
  const task = await json(owner, 'POST', `${aPath}/tasks`, { title: '本人任务', assigneeMemberId: memberId, priority: 'MEDIUM' })
  const cancelTask = await call(member, 'POST', `${aPath}/tasks/${task.taskId}/advance`, { toStatus: 'CANCELLED' })
  console.log(JSON.stringify({ check: '普通成员通过 advance 取消本人任务', status: cancelTask.status(), state: (await cancelTask.json()).status }))

  const foreignApply = await call(outsider, 'POST', `/api/activities/${activity.id}/registrations`, { displayName: '外部账号' })
  await foreignApply.json()
  const registration = await json(member, 'POST', `/api/activities/${activity.id}/registrations`, { displayName: '本人成员' })
  const leaked = await call(outsider, 'GET', `/api/organizations/${foreignOrg.id}/activities/${activity.id}/registrations`)
  console.log(JSON.stringify({ check: '报名资格与跨组织读取', outsiderInternalApply: foreignApply.status(), foreignOrgList: leaked.status(), leakedRows: (await leaked.json()).items?.length }))
  const delegatedApproval = await call(member, 'POST', `${aPath}/registrations/${registration.registrationId}/approve`)
  console.log(JSON.stringify({ check: '指定活动负责人审核', status: delegatedApproval.status() }))
  if (delegatedApproval.status() !== 200) await json(owner, 'POST', `${aPath}/registrations/${registration.registrationId}/approve`)
  console.log(JSON.stringify({ check: '活动负责人管理名单', status: (await call(member, 'GET', `${aPath}/registrations`)).status() }))
  console.log(JSON.stringify({ check: '活动负责人管理取消', status: (await call(member, 'POST', `${aPath}/registrations/${registration.registrationId}/cancel-org`, { reason: '管理取消测试' })).status() }))
  const selfCancel = await call(member, 'POST', `/api/activities/${activity.id}/registrations/cancel-self`)
  const afterCancel = (await json(member, 'GET', '/api/me/registrations')).items.find(x => x.activityId === activity.id)
  console.log(JSON.stringify({ check: '取消已批准报名', httpStatus: selfCancel.status(), actualState: afterCancel.status }))

  const page = await owner.newPage(), browserErrors = [], warnings = []
  page.on('pageerror', e => browserErrors.push(e.message))
  page.on('console', m => { if (m.type() === 'warning') warnings.push(m.text()) })
  await page.goto(`/org/${org.id}/activities/${activity.id}`, { waitUntil: 'networkidle' })
  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
  await page.screenshot({ path: fileURLToPath(new URL('../验证截图/复核-8bfa94c-活动详情.png', import.meta.url)), fullPage: true })
  console.log(JSON.stringify({ check: 'P3 页面渲染', browserErrors, unresolvedComponents: warnings.filter(x => x.includes('Failed to resolve component')).map(x => x.split('\n')[0]), hasTaskText: (await page.locator('body').innerText()).includes('本人任务') }))

  const directComplete = await call(member, 'POST', `${aPath}/tasks/${task.taskId}/advance`, { toStatus: 'COMPLETED' })
  console.log(JSON.stringify({ check: '待处理直接完成', status: directComplete.status() }))
  await json(owner, 'POST', `${aPath}/withdraw-approval`, { reason: '调整安排' })
  await json(member, 'GET', '/api/me/registrations')
  const approvedWhileDraft = await call(owner, 'POST', `${aPath}/registrations/${registration.registrationId}/approve`)
  const patch = await call(owner, 'PATCH', aPath, { capacity: 1, registrationScope: 'PUBLIC' })
  const taskWhileDraft = await call(owner, 'POST', `${aPath}/tasks`, { title: '调整期新增任务', assigneeMemberId: ownerMember })
  console.log(JSON.stringify({ check: '关键变更冻结', changeScopeWithRegistrations: patch.status(), createTaskInWithdrawnDraft: taskWhileDraft.status(), approveRegistrationInDraft: approvedWhileDraft.status() }))

  const past = await createActivity('已到开始时间的活动')
  await json(owner, 'PATCH', `${path}/${past.id}`, { startTime: new Date(Date.now()-3600000).toISOString(), endTime: new Date(Date.now()+3600000).toISOString() })
  await approveActivity(past)
  const lateApply = await call(member, 'POST', `/api/activities/${past.id}/registrations`, { displayName: '晚报名' })
  const lateRegistration = await lateApply.json()
  const lateApprove = await call(owner, 'POST', `${path}/${past.id}/registrations/${lateRegistration.registrationId}/approve`)
  const lateCancel = await call(member, 'POST', `/api/activities/${past.id}/registrations/cancel-self`)
  console.log(JSON.stringify({ check: '开始时间边界', apply: lateApply.status(), approve: lateApprove.status(), cancelSelf: lateCancel.status() }))
  const pending = await createActivity('不应向普通成员公开的待确认活动')
  await json(owner, 'POST', `${path}/${pending.id}/submit`)
  const pendingList = await json(member, 'GET', `${path}?status=PENDING_APPROVAL`)
  console.log(JSON.stringify({ check: '普通成员待确认列表泄露', total: pendingList.total, containsPrivateActivity: pendingList.items.some(x => x.id === pending.id), detailStatus: (await call(member, 'GET', `${path}/${pending.id}`)).status() }))
  const draft = await createActivity('未发布草稿')
  const draftTask = await json(owner, 'POST', `${path}/${draft.id}/tasks`, { title: '内部草稿任务', assigneeMemberId: ownerMember })
  const upload = await call(owner, 'POST', `${path}/${draft.id}/attachments`, undefined, { file: { name: 'fake.webp', mimeType: 'image/webp', buffer: Buffer.from('This is not a WebP image') } })
  const attachment = await upload.json()
  console.log(JSON.stringify({ check: '草稿子资源与文件内容', parentDetail: (await call(member, 'GET', `${path}/${draft.id}`)).status(), tasks: (await call(member, 'GET', `${path}/${draft.id}/tasks`)).status(), fakeWebpUpload: upload.status(), draftDownload: attachment.attachmentId ? (await call(member, 'GET', `${path}/${draft.id}/attachments/${attachment.attachmentId}/download`)).status() : null, taskCreated: Boolean(draftTask.taskId) }))
  const deletable = await createActivity('曾确认后撤回删除')
  await approveActivity(deletable)
  await json(owner, 'POST', `${path}/${deletable.id}/withdraw-approval`, { reason: '调整' })
  console.log(JSON.stringify({ check: '删除已确认后撤回活动', status: (await call(owner, 'DELETE', `${path}/${deletable.id}`)).status() }))
  
  console.log(JSON.stringify({ check: '隔离边界', database: 'activity_test', newAccounts: 3, newOrganizations: 2, uploadDirectory: '验证截图/audit-p234-files' }))
} finally { await browser.close() }

