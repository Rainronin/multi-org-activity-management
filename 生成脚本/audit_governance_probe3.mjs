// 审核临时探针 4（用完即删）：复现归档后任务/物资写入（AUTH-02）与反馈并发唯一键冲突（CONC-01）。
// 前置：8080 指向回归库、5174 可用、已有 owner/member 账号。
const BASE = process.env.PROBE_BASE || 'http://127.0.0.1:8080'
const out = []
const note = (id, d) => { out.push({ id, ...d }); console.log(id, JSON.stringify(d)) }

function S() {
  const jar = new Map()
  const ck = () => [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ')
  const ab = (r) => { for (const c of r.headers.getSetCookie?.() ?? []) { const [p] = c.split(';'); const i = p.indexOf('='); if (i > 0) jar.set(p.slice(0, i).trim(), p.slice(i + 1).trim()) } }
  async function call(p, i = {}) { const r = await fetch(BASE + p, { ...i, headers: { ...(i.headers || {}), ...(jar.size ? { cookie: ck() } : {}) }, redirect: 'manual' }); ab(r); return r }
  async function json(p, i) { const r = await call(p, i); const t = await r.text(); let b = null; try { b = JSON.parse(t) } catch {} return { status: r.status, body: b, text: t, headers: r.headers } }
  async function csrf() { const r = await json('/api/auth/csrf'); return { name: r.body.headerName, value: r.body.token } }
  async function login(u, pw) { const c = await csrf(); return json('/api/auth/login', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded', [c.name]: c.value }, body: new URLSearchParams({ username: u, password: pw }).toString() }) }
  async function write(method, p, body, extra = {}) { const c = await csrf(); const i = { method, headers: { [c.name]: c.value, ...extra } }; if (body !== undefined) { i.headers['content-type'] = 'application/json'; i.body = JSON.stringify(body) } return json(p, i) }
  return { call, json, csrf, login, write }
}

const owner = S()
const member = S()
const OWNER_USER = process.env.PROBE_OWNER
const MEMBER_USER = process.env.PROBE_MEMBER
await owner.login(OWNER_USER, 'Demo12345!')
await member.login(MEMBER_USER, 'Demo12345!')

const org = process.env.PROBE_ORG || '1'
const now = Date.now()
const iso = (d) => `${new Date(now + d * 86400000 + 8 * 3600000).toISOString().slice(0, 19)}+08:00`
const date = (d) => new Date(now + d * 86400000 + 8 * 3600000).toISOString().slice(0, 10)
const act = async (id) => (await owner.json(`/api/organizations/${org}/activities/${id}`)).body

// ================= A. 归档后任务与物资是否仍可写（AUTH-02）=================
const a = await owner.write('POST', `/api/organizations/${org}/activities`, {
  name: `审核取证·归档写入 ${new Date(now).toISOString().slice(11, 19)}`,
  description: '验证归档后任务与物资的写入边界', location: '审核地点', ownerMemberId: '1',
  startTime: iso(6), endTime: iso(8), registrationScope: 'INTERNAL', capacity: 5, budget: '500.00',
}, { 'Idempotency-Key': `audit-ar-${now}` })
const actId = a.body?.id
let v = await act(actId)
await owner.write('PUT', `/api/organizations/${org}/activities/${actId}/proposal`, { content: '审核取证方案。', version: v.version })
await owner.write('POST', `/api/organizations/${org}/activities/${actId}/submit`)
v = await act(actId)
await owner.write('POST', `/api/organizations/${org}/activities/${actId}/approve`, { note: '审核确认', version: v.version })
v = await act(actId)
note('A.活动已确认', { id: actId, status: v.status, version: v.version })

// 物资（保持 PENDING）+ 任务（保持 PENDING）
const mat = await owner.write('POST', `/api/organizations/${org}/activities/${actId}/materials`, {
  name: '审核取证物资', quantity: 3, unit: '个', assigneeMemberId: '1', note: '归档后写入验证',
})
const materialId = mat.body?.materialId ?? mat.body?.id
const task = await owner.write('POST', `/api/organizations/${org}/activities/${actId}/tasks`, {
  title: '审核取证任务', assigneeMemberId: '1', priority: 'LOW', description: '归档后写入验证',
})
const taskId = task.body?.taskId ?? task.body?.id
note('A.创建子资源', { materialId: mat.status, taskId: task.status, matCode: mat.body?.code, taskCode: task.body?.code })

// 直接归档：先取消（避免补总结流程），再写总结并归档
v = await act(actId)
const cancelled = await owner.write('POST', `/api/organizations/${org}/activities/${actId}/cancel`, { reason: '审核取证：进入可归档状态', version: v.version })
v = await act(actId)
await owner.write('PUT', `/api/organizations/${org}/activities/${actId}/summary`, {
  summary: '审核取证总结。\n目标：验证归档写入边界。\n执行：探针完成。\n反馈：无。\n改进：无。', version: v.version,
})
v = await act(actId)
await owner.write('POST', `/api/organizations/${org}/activities/${actId}/summary/confirm`, { version: v.version })
v = await act(actId)
const archived = await owner.write('POST', `/api/organizations/${org}/activities/${actId}/archive`, { version: v.version })
const afterArchive = await act(actId)
note('A.归档结果', { cancel: cancelled.status, archive: archived.status, status: afterArchive.status, version: afterArchive.version })

if (afterArchive.status === 'ARCHIVED') {
  // ① 物资状态流转（无版本、无状态守卫）
  if (materialId) {
    const r1 = await owner.write('POST', `/api/organizations/${org}/activities/${actId}/materials/${materialId}/transition`, { toStatus: 'READY' })
    note('AUTH-02 归档后·物资状态流转', { status: r1.status, code: r1.body?.code, detail: r1.body?.detail })
    const r2 = await owner.write('POST', `/api/organizations/${org}/activities/${actId}/materials/${materialId}/update`, { name: '归档后改名物资', quantity: 9, unit: '个', assigneeMemberId: '1', version: (await owner.json(`/api/organizations/${org}/activities/${actId}`)).body?.version })
    note('AUTH-02 归档后·物资编辑', { status: r2.status, code: r2.body?.code, detail: r2.body?.detail })
  }
  // ② 任务编辑与重开
  if (taskId) {
    const t = await owner.json(`/api/organizations/${org}/activities/${actId}/tasks`)
    const cur = t.body?.items?.find((x) => String(x.taskId) === String(taskId))
    const r3 = await owner.write('POST', `/api/organizations/${org}/activities/${actId}/tasks/${taskId}/update`, {
      title: '归档后改名任务', assigneeMemberId: '1', priority: 'HIGH', description: '归档后写入验证', version: cur?.version,
    })
    note('AUTH-02 归档后·任务编辑', { status: r3.status, code: r3.body?.code, detail: r3.body?.detail, taskVersion: cur?.version })
  }
}

// ================= B. 反馈并发提交撞唯一键（CONC-01）=================
const b = await owner.write('POST', `/api/organizations/${org}/activities`, {
  name: `审核取证·反馈并发 ${new Date(now).toISOString().slice(11, 19)}`,
  description: '验证反馈并发提交的唯一键兜底', location: '审核地点', ownerMemberId: '1',
  startTime: iso(9), endTime: iso(11), registrationScope: 'INTERNAL', capacity: 5, budget: '500.00',
}, { 'Idempotency-Key': `audit-fb-${now}` })
const fbActId = b.body?.id
let bv = await act(fbActId)
await owner.write('PUT', `/api/organizations/${org}/activities/${fbActId}/proposal`, { content: '审核取证方案。', version: bv.version })
await owner.write('POST', `/api/organizations/${org}/activities/${fbActId}/submit`)
bv = await act(fbActId)
await owner.write('POST', `/api/organizations/${org}/activities/${fbActId}/approve`, { note: '审核确认', version: bv.version })

// 负责人本人报名（内部报名 + 执行前须清空待审）
const members = await owner.json(`/api/organizations/${org}/members?pageSize=100`)
const ownerMid = members.body?.items?.find((m) => m.username === OWNER_USER)?.memberId
const reg = await owner.write('POST', `/api/activities/${fbActId}/registrations`,
  { displayName: '审核负责人', note: '审核取证报名', version: 0 })
const regId = reg.body?.registrationId ?? reg.body?.id
const appr = await owner.write('POST', `/api/organizations/${org}/activities/${fbActId}/registrations/${regId}/approve`,
  { version: reg.body?.version })
note('B.报名与审批', { reg: reg.status, regId, approve: appr.status, ownerMid })
bv = await act(fbActId)
const start = await owner.write('POST', `/api/organizations/${org}/activities/${fbActId}/start-execution`, { version: bv.version })
note('B.开始执行', { status: start.status, code: start.body?.code, detail: start.body?.detail })
bv = await act(fbActId)
const fin = await owner.write('POST', `/api/organizations/${org}/activities/${fbActId}/finish-execution`, { version: bv.version })
const afterFin = await act(fbActId)
note('B.结束执行', { status: fin.status, code: fin.body?.code, detail: fin.body?.detail, nowStatus: afterFin.status })

if (afterFin.status === 'REVIEWING') {
  // 两个独立会话并发提交同一账号的反馈
  const s1 = S(); const s2 = S()
  await s1.login(OWNER_USER, 'Demo12345!'); await s2.login(OWNER_USER, 'Demo12345!')
  const loadedFeedback = await s1.json(`/api/organizations/${org}/activities/${fbActId}/feedback`)
  const loadedFeedbackVersion = loadedFeedback.body?.myVersion ?? 0
  const body = { rating: 5, comment: '审核并发提交取证', version: loadedFeedbackVersion }
  const [r1, r2] = await Promise.all([
    s1.write('POST', `/api/organizations/${org}/activities/${fbActId}/feedback`, body),
    s2.write('POST', `/api/organizations/${org}/activities/${fbActId}/feedback`, body),
  ])
  const summary = await owner.json(`/api/organizations/${org}/activities/${fbActId}/feedback`)
  note('CONC-01 反馈并发提交', {
    响应1: r1.status, code1: r1.body?.code,
    响应2: r2.status, code2: r2.body?.code,
    反馈总数: summary.body?.count,
    结论: [r1.status, r2.status].includes(500) ? '出现 500 → 唯一键未兜底成立' : '未出现 500 → 未复现',
  })
  // 串行重复提交（双击场景）：沿用点击时的原版本，不刷新版本掩盖重复提交。
  const s3 = await s1.write('POST', `/api/organizations/${org}/activities/${fbActId}/feedback`,
    { rating: 4, comment: '串行重复提交', version: loadedFeedbackVersion })
  note('CONC-01 串行重复提交', { status: s3.status, code: s3.body?.code })
}

console.log('\n---JSON---')
console.log(JSON.stringify(out, null, 2))
