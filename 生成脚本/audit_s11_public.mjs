// S11 公开浏览与导出观察脚本：只验证匿名可读性与内部字段隔离，会新增专用测试数据。
// 仅可针对显式连接 activity_test 的后端运行。
import assert from 'node:assert/strict'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')

const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const stamp = Date.now()
  const ownerName = `pubowner${stamp}`
  const memberName = `pubmember${stamp}`
  const password = `Pass${stamp}word`
  const anon = await browser.newContext({ baseURL: 'http://127.0.0.1:5174' })
  const owner = await browser.newContext({ baseURL: 'http://127.0.0.1:5174' })
  const member = await browser.newContext({ baseURL: 'http://127.0.0.1:5174' })

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
    return (await json(owner, 'GET', `/api/organizations/${org.id}/activities/${activityId}`)).version
  }
  async function login(ctx, username) {
    await json(ctx, 'POST', '/api/auth/register', { username, password })
    const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
    const res = await ctx.request.post('/api/auth/login', { form: { username, password }, headers: { [csrf.headerName]: csrf.token } })
    assert.equal(res.status(), 204, '准备流程：登录失败')
  }
  await login(owner, ownerName)
  await login(member, memberName)
  const org = await json(owner, 'POST', '/api/organizations', { name: `公开核对-${stamp}` })
  const invitation = await json(owner, 'POST', `/api/organizations/${org.id}/invitations`, {})
  await json(member, 'POST', '/api/invitations/accept', { code: invitation.code })
  const members = (await json(owner, 'GET', `/api/organizations/${org.id}/members?pageSize=100`)).items
  const ownerMember = members.find((x) => x.username === ownerName).memberId
  const memberMember = members.find((x) => x.username === memberName).memberId

  let keySeq = 0
  async function createActivity(name, scope) {
    return json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
      name, description: '公开核对简介', ownerMemberId: ownerMember,
      startTime: new Date(Date.now() + 9 * 86400000).toISOString(),
      endTime: new Date(Date.now() + 9 * 86400000 + 7200000).toISOString(),
      location: '礼堂', registrationScope: scope, capacity: 20,
      registrationStart: new Date(Date.now() - 86400000).toISOString(),
      registrationEnd: new Date(Date.now() + 8 * 86400000).toISOString(),
    }, { 'Idempotency-Key': `pub-${stamp}-${++keySeq}` })
  }
  async function approve(activity) {
    await json(owner, 'PUT', `/api/organizations/${org.id}/activities/${activity.id}/proposal`, { content: '方案', version: await currentVersion(activity.id) })
    await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/submit`)
    await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/approve`, { version: await currentVersion(activity.id) })
  }
  const publicActivity = await createActivity('公开活动', 'PUBLIC')
  await approve(publicActivity)
  const internalActivity = await createActivity('内部活动', 'INTERNAL')
  await approve(internalActivity)
  const draftActivity = await createActivity('未确认草稿', 'PUBLIC')
  await json(owner, 'PUT', `/api/organizations/${org.id}/activities/${draftActivity.id}/proposal`, { content: '方案', version: await currentVersion(draftActivity.id) })

  // 匿名（无会话 Cookie）：公开端点可读，组织内端点必须 401
  const anonPublicList = await anon.request.get('/api/public/activities')
  const anonPublicDetail = await anon.request.get(`/api/public/activities/${publicActivity.id}`)
  const anonInternalDetail = await anon.request.get(`/api/public/activities/${internalActivity.id}`)
  const anonDraftDetail = await anon.request.get(`/api/public/activities/${draftActivity.id}`)
  const anonOrgEndpoint = await anon.request.get(`/api/organizations/${org.id}/activities`)
  const detailBody = await anonPublicDetail.text()
  const bannedFields = ['budget', 'proposal', 'reviewNote', 'ownerMemberId', 'ownerUsername', 'version']
  console.log(JSON.stringify({
    check: 'S11 公开浏览匿名可读性与字段隔离',
    anonymousPublicList: anonPublicList.status(),
    anonymousPublicDetail: anonPublicDetail.status(),
    internalActivityPublicDetail: anonInternalDetail.status(),
    draftPublicDetail: anonDraftDetail.status(),
    anonymousOrgEndpoint: anonOrgEndpoint.status(),
    leakedFields: bannedFields.filter((f) => detailBody.includes(f)),
  }))

  // 导出：管理者可用且转义公式；报名者非管理者被拒
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${internalActivity.id}/registration-open`, { open: true })
  await json(member, 'POST', `/api/activities/${internalActivity.id}/registrations`, { displayName: '=1+1', note: '@SUM(A1)' })
  const exportRes = await call(owner, 'GET', `/api/organizations/${org.id}/activities/${internalActivity.id}/registrations/export`)
  const csv = await exportRes.text()
  const memberExport = await call(member, 'GET', `/api/organizations/${org.id}/activities/${internalActivity.id}/registrations/export`)
  console.log(JSON.stringify({
    check: 'S11 名单导出',
    ownerExport: exportRes.status(),
    contentType: exportRes.headers()['content-type'],
    hasBom: csv.charCodeAt(0) === 0xFEFF,
    escapesFormulaName: csv.includes("\"'=1+1\""),
    escapesFormulaNote: csv.includes("\"'@SUM(A1)\""),
    rawFormulaLeaked: csv.includes('"=1+1"'),
    memberExport: memberExport.status(),
  }))
} finally {
  await browser.close()
}
