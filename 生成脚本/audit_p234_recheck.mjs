// 修复复核观察脚本（初版针对 8bfa94c；第二轮更新 S02／S04／S05／S06；第三轮更新 S07 关键变更冻结）：
// 仅可针对显式连接 activity_test 的后端运行，会新增专用测试数据。
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const MYSQL_EXE = process.env.AUDIT_MYSQL_EXE || 'C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe'
// 本机开发实例约定：root 空密码仅限 127.0.0.1 回环（README）。
// 只用于构造「已开始」这个时间边界：合法路径必须撤回草稿，会同时清空报名记录
async function dbExec(sql) {
  execFileSync(MYSQL_EXE, ['-h', '127.0.0.1', '-u', 'root', '-D', 'activity_test', '-e', sql],
    { stdio: 'inherit', env: { ...process.env, MYSQL_PWD: process.env.AUDIT_MYSQL_PASSWORD || '' } })
}
try {
  const contexts = await Promise.all([1, 2, 3, 4].map(() => browser.newContext({ baseURL: 'http://127.0.0.1:5174', viewport: { width: 1440, height: 1000 } })))
  const [owner, member, outsider, delegate] = contexts
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
  const delegateInvitation = await json(owner, 'POST', `/api/organizations/${org.id}/invitations`, {})
  await json(delegate, 'POST', '/api/invitations/accept', { code: delegateInvitation.code })
  const members = (await json(owner, 'GET', `/api/organizations/${org.id}/members`)).items
  const ownerMember = members.find(x => x.username === usernames[0]).memberId
  const memberId = members.find(x => x.username === usernames[1]).memberId
  const delegateMember = members.find(x => x.username === usernames[3]).memberId
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
  async function openRegistration(activity) {
    return json(owner, 'POST', `${path}/${activity.id}/registration-open`, { open: true })
  }
  const activity = await createActivity('正式活动审核', delegateMember)
  await approveActivity(activity)
  console.log(JSON.stringify({ check: '报名开关默认状态', closed: (await json(member, 'GET', `${path}/${activity.id}`)).registrationOpen }))
  const aPath = `${path}/${activity.id}`
  const closedApply = await call(member, 'POST', `/api/activities/${activity.id}/registrations`, { displayName: '开关关闭申请' })
  await closedApply.json()
  console.log(JSON.stringify({ check: '开关关闭时申请', status: closedApply.status() }))
  // S04：指定活动负责人（非组织负责人）开开关、读名单、管理取消
  await json(delegate, 'POST', `${aPath}/registration-open`, { open: true })
  console.log(JSON.stringify({ check: '指定活动负责人开开关', open: (await json(member, 'GET', aPath)).registrationOpen }))
  console.log(JSON.stringify({ check: '活动负责人列表', total: (await json(member, 'GET', `${path}?status=APPROVED`)).total }))
  const task = await json(owner, 'POST', `${aPath}/tasks`, { title: '本人任务', assigneeMemberId: memberId, priority: 'MEDIUM' })
  const cancelTask = await call(member, 'POST', `${aPath}/tasks/${task.taskId}/advance`, { toStatus: 'CANCELLED' })
  console.log(JSON.stringify({ check: '普通成员通过 advance 取消本人任务', status: cancelTask.status(), state: (await cancelTask.json()).status }))

  const foreignApply = await call(outsider, 'POST', `/api/activities/${activity.id}/registrations`, { displayName: '外部账号' })
  await foreignApply.json()
  const registration = await json(member, 'POST', `/api/activities/${activity.id}/registrations`, { displayName: '本人成员' })
  const leaked = await call(outsider, 'GET', `/api/organizations/${foreignOrg.id}/activities/${activity.id}/registrations`)
  console.log(JSON.stringify({ check: '报名资格与跨组织读取', outsiderInternalApply: foreignApply.status(), foreignOrgList: leaked.status(), leakedRows: (await leaked.json()).items?.length }))
  const delegatedApproval = await call(delegate, 'POST', `${aPath}/registrations/${registration.registrationId}/approve`)
  console.log(JSON.stringify({ check: '指定活动负责人审核', status: delegatedApproval.status() }))
  if (delegatedApproval.status() !== 200) await json(owner, 'POST', `${aPath}/registrations/${registration.registrationId}/approve`)
  console.log(JSON.stringify({ check: '活动负责人管理名单', status: (await call(delegate, 'GET', `${aPath}/registrations`)).status() }))
  const selfCancel = await call(member, 'POST', `/api/activities/${activity.id}/registrations/cancel-self`)
  const afterCancel = (await json(member, 'GET', '/api/me/registrations')).items.find(x => x.activityId === activity.id)
  console.log(JSON.stringify({ check: '取消已批准报名', httpStatus: selfCancel.status(), actualState: afterCancel.status }))
  await json(member, 'POST', `/api/activities/${activity.id}/registrations`, { displayName: '重新申请' })
  console.log(JSON.stringify({ check: '活动负责人管理取消', status: (await call(delegate, 'POST', `${aPath}/registrations/${registration.registrationId}/cancel-org`, { reason: '管理取消测试' })).status() }))

  const page = await owner.newPage(), browserErrors = [], warnings = []
  page.on('pageerror', e => browserErrors.push(e.message))
  page.on('console', m => { if (m.type() === 'warning') warnings.push(m.text()) })
  await page.goto(`/org/${org.id}/activities/${activity.id}`, { waitUntil: 'networkidle' })
  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
  await page.screenshot({ path: fileURLToPath(new URL('../验证截图/复核-8bfa94c-活动详情.png', import.meta.url)), fullPage: true })
  console.log(JSON.stringify({ check: 'P3 页面渲染', browserErrors, unresolvedComponents: warnings.filter(x => x.includes('Failed to resolve component')).map(x => x.split('\n')[0]), hasTaskText: (await page.locator('body').innerText()).includes('本人任务') }))

  const directComplete = await call(member, 'POST', `${aPath}/tasks/${task.taskId}/advance`, { toStatus: 'COMPLETED' })
  console.log(JSON.stringify({ check: '待处理直接完成', status: directComplete.status() }))

  // S07 关键变更冻结：撤回待重新确认期间任务只读、范围锁定、已确认过的活动不能删除
  await json(owner, 'POST', `${aPath}/withdraw-approval`, { reason: '调整安排' })
  await json(member, 'GET', '/api/me/registrations')
  const approvedWhileDraft = await call(owner, 'POST', `${aPath}/registrations/${registration.registrationId}/approve`)
  const patch = await call(owner, 'PATCH', aPath, { capacity: 1, registrationScope: 'PUBLIC' })
  const taskWhileDraft = await call(owner, 'POST', `${aPath}/tasks`, { title: '调整期新增任务', assigneeMemberId: ownerMember })
  const materialWhileDraft = await call(owner, 'POST', `${aPath}/materials`, { name: '调整期物资', quantity: 1, unit: '条' })
  const deleteWithdrawn = await call(owner, 'DELETE', aPath)
  const patchBody = await patch.json()
  const deleteBody = await deleteWithdrawn.json()
  console.log(JSON.stringify({ check: 'S07 关键变更冻结', changeScopeOrCapacityWithRegistrations: patch.status(), patchCode: patchBody.code, createTaskInWithdrawnDraft: taskWhileDraft.status(), createMaterialInWithdrawnDraft: materialWhileDraft.status(), approveRegistrationInDraft: approvedWhileDraft.status(), deleteWithdrawnConfirmed: deleteWithdrawn.status(), deleteCode: deleteBody.code }))

  // 撤回后仍可调整容量到与已通过人数一致（容量下限规则）
  const capacityOnly = await call(owner, 'PATCH', aPath, { capacity: 1 })
  console.log(JSON.stringify({ check: 'S07 调整期容量调整', status: capacityOnly.status(), capacity: (await capacityOnly.json()).capacity }))

  const past = await createActivity('已到开始时间的活动')
  await approveActivity(past)
  await openRegistration(past)
  const beforeStart = await json(member, 'POST', `/api/activities/${past.id}/registrations`, { displayName: '时间边界报名' })
  // 直接改状态列构造「已开始」：合法路径需撤回草稿，会同时清空报名，无法保留开始前记录
  await dbExec(`UPDATE activity SET start_time = NOW() - INTERVAL 1 HOUR, end_time = NOW() + INTERVAL 1 HOUR WHERE id = ${past.id}`)
  const startedApply = await call(member, 'POST', `/api/activities/${past.id}/registrations`, { displayName: '开始后申请' })
  const startedApprove = await call(owner, 'POST', `${path}/${past.id}/registrations/${beforeStart.registrationId}/approve`)
  const startedSelfCancel = await call(member, 'POST', `/api/activities/${past.id}/registrations/cancel-self`)
  const startedReject = await call(owner, 'POST', `${path}/${past.id}/registrations/${beforeStart.registrationId}/reject`, { reason: '开始后清理待审' })
  console.log(JSON.stringify({ check: '开始时间边界', applyBeforeStart: beforeStart.registrationId ? 201 : beforeStart, applyAfterStart: startedApply.status(), approveAfterStart: startedApprove.status(), selfCancelAfterStart: startedSelfCancel.status(), rejectAfterStart: startedReject.status() }))
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
  
  console.log(JSON.stringify({ check: '隔离边界', database: 'activity_test', newAccounts: usernames.length, newOrganizations: 2, uploadDirectory: '验证截图/audit-p234-files' }))
} finally { await browser.close() }

