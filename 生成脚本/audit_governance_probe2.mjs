// 审核临时探针 3（用完即删）：明确区分两套会话身份，验证管理者专属读取与凭证下载。
const BASE = process.env.PROBE_BASE || 'http://127.0.0.1:8080'
function S() {
  const jar = new Map()
  const ck = () => [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ')
  const ab = (r) => { for (const c of r.headers.getSetCookie?.() ?? []) { const [p] = c.split(';'); const i = p.indexOf('='); if (i > 0) jar.set(p.slice(0, i).trim(), p.slice(i + 1).trim()) } }
  async function call(p, i = {}) { const r = await fetch(BASE + p, { ...i, headers: { ...(i.headers || {}), ...(jar.size ? { cookie: ck() } : {}) }, redirect: 'manual' }); ab(r); return r }
  async function json(p, i) { const r = await call(p, i); const t = await r.text(); let b = null; try { b = JSON.parse(t) } catch {} return { status: r.status, body: b, text: t, headers: r.headers } }
  async function csrf() { const r = await json('/api/auth/csrf'); return { name: r.body.headerName, value: r.body.token } }
  async function login(u, pw) { const c = await csrf(); return json('/api/auth/login', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded', [c.name]: c.value }, body: new URLSearchParams({ username: u, password: pw }).toString() }) }
  return { call, json, csrf, login }
}

const OWNER_USER = process.env.PROBE_OWNER
const MEMBER_USER = process.env.PROBE_MEMBER
const org = process.env.PROBE_ORG || '1'
const act = process.env.PROBE_SEED_ACTIVITY || '1'

const o = S()
const m = S()
const lo = await o.login(OWNER_USER, 'Demo12345!')
const lm = await m.login(MEMBER_USER, 'Demo12345!')
const io = await o.json('/api/auth/me')
const im = await m.json('/api/auth/me')
console.log('身份核对:', JSON.stringify({
  期望负责人: OWNER_USER, 实际: io.body?.username, 登录: lo.status,
  期望普通成员: MEMBER_USER, 实际: im.body?.username, 登录: lm.status,
  两者不同: io.body?.username !== im.body?.username,
}))

const detail = await o.json(`/api/organizations/${org}/activities/${act}`)
console.log('活动:', JSON.stringify({ 名称: detail.body?.name, 状态: detail.body?.status, 负责人memberId: detail.body?.ownerMemberId }))

// 管理者专属：经费列表
const expensesOwner = await o.json(`/api/organizations/${org}/activities/${act}/expenses?pageSize=20`)
const expensesMember = await m.json(`/api/organizations/${org}/activities/${act}/expenses?pageSize=20`)
console.log('经费列表:', JSON.stringify({
  负责人: expensesOwner.status, 负责人条数: expensesOwner.body?.items?.length,
  普通成员: expensesMember.status, 普通成员code: expensesMember.body?.code,
}))
const eid = expensesOwner.body?.items?.[0]?.expenseId

if (eid) {
  const vsOwner = await o.json(`/api/organizations/${org}/activities/${act}/expenses/${eid}/attachments`)
  const vsMember = await m.json(`/api/organizations/${org}/activities/${act}/expenses/${eid}/attachments`)
  const vid = vsOwner.body?.items?.[0]?.id ?? vsOwner.body?.items?.[0]?.attachmentId
  console.log('凭证列表:', JSON.stringify({
    负责人: vsOwner.status, 条数: vsOwner.body?.items?.length,
    普通成员: vsMember.status, 普通成员code: vsMember.body?.code, 条数: vsMember.body?.items?.length,
    voucherId: vid,
  }))
  if (vid) {
    const dlOwner = await o.call(`/api/organizations/${org}/activities/${act}/attachments/${vid}/download`)
    const ob = (await dlOwner.arrayBuffer()).byteLength
    const dlMember = await m.call(`/api/organizations/${org}/activities/${act}/attachments/${vid}/download`)
    const mb = (await dlMember.arrayBuffer()).byteLength
    console.log('凭证下载:', JSON.stringify({
      负责人: dlOwner.status, 负责人字节: ob,
      普通成员: dlMember.status, 普通成员字节: mb,
      结论: dlMember.status === 200 && mb > 0 ? '普通成员可下载票据（越权成立）' : '普通成员被拒（无越权）',
    }))
  }
}
