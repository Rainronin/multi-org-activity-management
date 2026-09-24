// 审核临时探针（用完即删）：把静态审查结论转成运行时定点证据。
// 目标：本机 8080（本轮指向回归库 activity_p9_build）+ 5174 前端代理。
const BASE = process.env.PROBE_BASE || 'http://127.0.0.1:8080'
const results = []
const note = (id, detail) => { results.push({ id, ...detail }); console.log(id, JSON.stringify(detail)) }

function newSession() {
  const jar = new Map()
  const cookieHeader = () => [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ')
  const absorb = (res) => {
    for (const c of res.headers.getSetCookie?.() ?? []) {
      const [pair] = c.split(';')
      const i = pair.indexOf('=')
      if (i > 0) jar.set(pair.slice(0, i).trim(), pair.slice(i + 1).trim())
    }
  }
  async function call(path, init = {}) {
    const res = await fetch(BASE + path, {
      ...init,
      headers: { ...(init.headers || {}), ...(jar.size ? { cookie: cookieHeader() } : {}) },
      redirect: 'manual',
    })
    absorb(res)
    return res
  }
  async function json(path, init) {
    const res = await call(path, init)
    const text = await res.text()
    let body = null
    try { body = JSON.parse(text) } catch { /* 非 JSON 保留原文 */ }
    return { status: res.status, body, text, headers: res.headers }
  }
  async function csrf() {
    const r = await json('/api/auth/csrf')
    return { name: r.body.headerName, value: r.body.token }
  }
  async function login(username, password) {
    const c = await csrf()
    return json('/api/auth/login', {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded', [c.name]: c.value },
      body: new URLSearchParams({ username, password }).toString(),
    })
  }
  // 写请求统一走这里：每次重新取 CSRF 令牌，避免复用旧令牌
  async function write(method, path, body, extraHeaders = {}) {
    const c = await csrf()
    const init = { method, headers: { [c.name]: c.value, ...extraHeaders } }
    if (body !== undefined) {
      init.headers['content-type'] = 'application/json'
      init.body = JSON.stringify(body)
    }
    return json(path, init)
  }
  return { call, json, csrf, login, write }
}

const owner = newSession()
const member = newSession()
const loginOwner = await owner.login(process.env.PROBE_OWNER, 'Demo12345!')
const loginMember = await member.login(process.env.PROBE_MEMBER, 'Demo12345!')
note('登录', { owner: loginOwner.status, member: loginMember.status })

const orgId = process.env.PROBE_ORG || '1'
const members = await owner.json(`/api/organizations/${orgId}/members?pageSize=100`)
const plainMemberId = members.body?.items?.find((m) => m.username === process.env.PROBE_MEMBER)?.memberId
note('成员', { status: members.status, plainMemberId })

const now = Date.now()
const localIso = (offsetDays) => `${new Date(now + offsetDays * 86400000 + 8 * 3600000).toISOString().slice(0, 19)}+08:00`
const localDate = (offsetDays) => new Date(now + offsetDays * 86400000 + 8 * 3600000).toISOString().slice(0, 10)
const versionOf = async (actId) => (await owner.json(`/api/organizations/${orgId}/activities/${actId}`)).body

// ---------- 新建活动并确认 ----------
const created = await owner.write('POST', `/api/organizations/${orgId}/activities`, {
  name: `审核取证活动 ${new Date().toISOString().slice(11, 19)}`,
  description: '审核临时活动：验证凭证越权下载、分页边界与归档只读',
  location: '审核测试地点', ownerMemberId: plainMemberId,
  startTime: localIso(3), endTime: localIso(5),
  registrationScope: 'INTERNAL', capacity: 10, budget: '1000.00',
}, { 'Idempotency-Key': `audit-act-${now}` })
const activityId = created.body?.id
note('创建活动', { status: created.status, activityId })

// 走真实链路确认活动：PUT proposal → submit → approve
const p1 = await versionOf(activityId)
const putProposal = await owner.write('PUT', `/api/organizations/${orgId}/activities/${activityId}/proposal`, {
  content: '审核取证方案：用于把活动推进到已确认筹备期，验证附件下载与归档只读。',
  version: p1.version,
})
note('保存方案', { status: putProposal.status, code: putProposal.body?.code })
const submit = await owner.write('POST', `/api/organizations/${orgId}/activities/${activityId}/submit`)
note('提交方案', { status: submit.status, code: submit.body?.code, nowStatus: (await versionOf(activityId)).status })
const a1 = await versionOf(activityId)
const approve = await owner.write('POST', `/api/organizations/${orgId}/activities/${activityId}/approve`, { note: '审核取证确认', version: a1.version })
note('确认活动', { status: approve.status, code: approve.body?.code, nowStatus: (await versionOf(activityId)).status })

// ---------- FILE-01：普通成员能否下载非凭证附件（同一 download 端点，对照 list 权限）----------
{
  const fd = new FormData()
  fd.append('file', new Blob([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0])], { type: 'image/png' }), 'material.png')
  const c = await owner.csrf()
  const up = await owner.json(`/api/organizations/${orgId}/activities/${activityId}/attachments`, {
    method: 'POST', headers: { [c.name]: c.value, 'Idempotency-Key': `audit-att-${now}` }, body: fd,
  })
  const attId = up.body?.id ?? up.body?.attachmentId
  note('上传活动资料(负责人)', { status: up.status, attId, code: up.body?.code })

  if (attId) {
    const listMember = await member.json(`/api/organizations/${orgId}/activities/${activityId}/attachments`)
    const dlMember = await member.call(`/api/organizations/${orgId}/activities/${activityId}/attachments/${attId}/download`)
    const bytes = (await dlMember.arrayBuffer()).byteLength
    note('FILE-01 资料下载(普通成员)', {
      memberListStatus: listMember.status, memberListCount: listMember.body?.items?.length,
      memberDownloadStatus: dlMember.status, memberDownloadBytes: bytes,
      disposition: dlMember.headers.get('content-disposition'),
      nosniff: dlMember.headers.get('x-content-type-options'),
      cacheControl: dlMember.headers.get('cache-control'),
    })
  }
}

// ---------- CONC-02 / CONC-09：分页边界 ----------
for (const q of ['page=1&pageSize=20', 'page=2147483647&pageSize=20', 'page=100000&pageSize=100', 'page=1&pageSize=101']) {
  const r = await owner.json(`/api/organizations/${orgId}/activities/${activityId}/registrations?${q}`)
  note(`分页 ${q}`, { status: r.status, code: r.body?.code, items: r.body?.items?.length })
}

// ---------- 费用与凭证 ----------
const exp = await owner.write('POST', `/api/organizations/${orgId}/activities/${activityId}/expenses`, {
  purpose: '审核凭证越权验证', amount: '120.50', incurredOn: localDate(0), category: 'MATERIAL',
  handledNote: '经手人：审核探针',
}, { 'Idempotency-Key': `audit-exp-${now}` })
const expenseId = exp.body?.expenseId ?? exp.body?.id
note('登记费用', { status: exp.status, expenseId, code: exp.body?.code })

let voucherId
if (expenseId) {
  const fd = new FormData()
  fd.append('file', new Blob([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0])], { type: 'image/png' }), 'voucher.png')
  const c = await owner.csrf()
  const up = await owner.json(`/api/organizations/${orgId}/activities/${activityId}/expenses/${expenseId}/attachments`, {
    method: 'POST', headers: { [c.name]: c.value, 'Idempotency-Key': `audit-voucher-${now}` }, body: fd,
  })
  voucherId = up.body?.id ?? up.body?.attachmentId ?? up.body?.voucherId
  note('上传凭证(负责人)', { status: up.status, voucherId, code: up.body?.code })

  const listAsOwner = await owner.json(`/api/organizations/${orgId}/activities/${activityId}/expenses/${expenseId}/attachments`)
  const listAsMember = await member.json(`/api/organizations/${orgId}/activities/${activityId}/expenses/${expenseId}/attachments`)
  note('凭证列表(FILE-01 对照)', { owner: listAsOwner.status, member: listAsMember.status, memberCode: listAsMember.body?.code })
}

// 用一个参照物：seed 造出的活动 1（已复盘）做凭证下载越权验证
const seedActivity = Number(process.env.PROBE_SEED_ACTIVITY || '1')
const seedExpenses = await owner.json(`/api/organizations/${orgId}/activities/${seedActivity}/expenses?pageSize=20`)
const seedExpenseId = seedExpenses.body?.items?.[0]?.expenseId
note('既有活动费用', { status: seedExpenses.status, seedExpenseId })
if (seedExpenseId) {
  const vouchers = await owner.json(`/api/organizations/${orgId}/activities/${seedActivity}/expenses/${seedExpenseId}/attachments`)
  const seedVoucherId = vouchers.body?.items?.[0]?.id ?? vouchers.body?.items?.[0]?.attachmentId
  note('既有凭证', { ownerList: vouchers.status, count: vouchers.body?.items?.length, seedVoucherId })
  if (seedVoucherId) {
    const asMember = await member.call(`/api/organizations/${orgId}/activities/${seedActivity}/attachments/${seedVoucherId}/download`)
    const bytes = (await asMember.arrayBuffer()).byteLength
    const asOwner = await owner.call(`/api/organizations/${orgId}/activities/${seedActivity}/attachments/${seedVoucherId}/download`)
    const listAsMember = await member.json(`/api/organizations/${orgId}/activities/${seedActivity}/expenses/${seedExpenseId}/attachments`)
    note('FILE-01 凭证下载越权', {
      memberListStatus: listAsMember.status,
      memberDownloadStatus: asMember.status, memberDownloadBytes: bytes,
      ownerDownloadStatus: asOwner.status,
      disposition: asMember.headers.get('content-disposition'),
      nosniff: asMember.headers.get('x-content-type-options'),
    })
  }
}

// ---------- ACT-01：归档/取消后 PATCH 是否仍可写 ----------
async function patchProbe(label, actId) {
  const before = await versionOf(actId)
  const r = await owner.write('PATCH', `/api/organizations/${orgId}/activities/${actId}`, { name: `归档后改名-${label}`, version: before.version })
  const after = await versionOf(actId)
  note(`ACT-01 PATCH(${label})`, {
    beforeStatus: before.status, beforeName: before.name, version: before.version,
    patchStatus: r.status, patchCode: r.body?.code,
    afterName: after.name, afterVersion: after.version,
  })
}

// 取消 → 归档 → PATCH
{
  const v = await versionOf(activityId)
  const cancelled = await owner.write('POST', `/api/organizations/${orgId}/activities/${activityId}/cancel`, { reason: '审核取证：取消后验证归档只读', version: v.version })
  note('取消活动', { status: cancelled.status, code: cancelled.body?.code, nowStatus: (await versionOf(activityId)).status })
  if (cancelled.status === 200) {
    await patchProbe('CANCELLED', activityId)
    const c2 = await versionOf(activityId)
    await owner.write('PUT', `/api/organizations/${orgId}/activities/${activityId}/summary`, {
      summary: '审核取证总结。\n目标：验证归档只读。\n执行：探针自动完成。\n反馈：无。\n改进：无。',
      version: c2.version,
    })
    const c3 = await versionOf(activityId)
    const sumConfirmed = await owner.write('POST', `/api/organizations/${orgId}/activities/${activityId}/summary/confirm`, { version: c3.version })
    note('确认总结', { status: sumConfirmed.status, code: sumConfirmed.body?.code })
    const c4 = await versionOf(activityId)
    const archived = await owner.write('POST', `/api/organizations/${orgId}/activities/${activityId}/archive`, { version: c4.version })
    note('归档', { status: archived.status, code: archived.body?.code, detail: archived.body?.detail, nowStatus: (await versionOf(activityId)).status })
    if (archived.status === 200) await patchProbe('ARCHIVED', activityId)
  }
}

// ---------- FILE-07：畸形路径变量 ----------
{
  const bad = await owner.json(`/api/organizations/${orgId}/activities/${seedActivity}/attachments/abc/download`)
  const badId = await owner.json(`/api/organizations/${orgId}/activities/${seedActivity}/attachments/99999999999999999999/download`)
  note('FILE-07 畸形路径变量', { nonNumeric: bad.status, nonNumericCode: bad.body?.code, overflow: badId.status, overflowCode: badId.body?.code })
}

console.log('\n---JSON---')
console.log(JSON.stringify(results, null, 2))
