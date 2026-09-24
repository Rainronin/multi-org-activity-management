// 审核临时探针 5（用完即删）：复现 AI 生成并发同 Idempotency-Key 的唯一键冲突（AI-01）。
// 桩模型：本地 9999 端口返回 OpenAI 兼容响应，延迟 2s 以保证两个请求都通过幂等预检。
import http from 'node:http'

const API = 'http://127.0.0.1:8080'
const STUB_PORT = 9999
const U = process.env.PROBE_OWNER
const PW = 'Demo12345!'

// ---------- 桩模型 ----------
const stub = http.createServer((req, res) => {
  let body = ''
  req.on('data', (c) => { body += c })
  req.on('end', () => {
    setTimeout(() => {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({
        choices: [{ message: { role: 'assistant', content: '审核取证：这是桩模型返回的总结草稿内容。' } }],
        usage: { prompt_tokens: 10, completion_tokens: 20 },
      }))
    }, 2000)
  })
})
await new Promise((r) => stub.listen(STUB_PORT, '127.0.0.1', r))
console.log(`桩模型已启动 http://127.0.0.1:${STUB_PORT}`)

function S() {
  const jar = new Map()
  const ck = () => [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ')
  const ab = (r) => { for (const c of r.headers.getSetCookie?.() ?? []) { const [p] = c.split(';'); const i = p.indexOf('='); if (i > 0) jar.set(p.slice(0, i).trim(), p.slice(i + 1).trim()) } }
  async function call(p, i = {}) { const r = await fetch(API + p, { ...i, headers: { ...(i.headers || {}), ...(jar.size ? { cookie: ck() } : {}) }, redirect: 'manual' }); ab(r); return r }
  async function json(p, i) { const r = await call(p, i); const t = await r.text(); let b = null; try { b = JSON.parse(t) } catch {} return { status: r.status, body: b, text: t } }
  async function csrf() { const r = await json('/api/auth/csrf'); return { name: r.body.headerName, value: r.body.token } }
  async function login(u, pw) { const c = await csrf(); return json('/api/auth/login', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded', [c.name]: c.value }, body: new URLSearchParams({ username: u, password: pw }).toString() }) }
  async function write(m, p, b, e = {}) { const c = await csrf(); const i = { method: m, headers: { [c.name]: c.value, ...e } }; if (b !== undefined) { i.headers['content-type'] = 'application/json'; i.body = JSON.stringify(b) } return json(p, i) }
  return { call, json, csrf, login, write }
}

const o = S()
const lg = await o.login(U, PW)
const me = await o.json('/api/auth/me')
console.log('登录:', lg.status, '身份:', me.body?.username)
const orgs = await o.json('/api/me/organizations')
const org = orgs.body?.items?.[0]?.id ?? orgs.body?.[0]?.id
console.log('orgId:', org)

// ---------- 1) 保存 AI 配置并测试连接 ----------
const now = Date.now()
const cfg = await o.write('PUT', `/api/organizations/${org}/ai/config`, {
  protocol: 'OPENAI_CHAT_COMPLETIONS',
  endpoint: `http://127.0.0.1:${STUB_PORT}/v1/chat/completions`,
  model: 'audit-stub-model',
  authScheme: 'BEARER',
  apiKey: 'audit-stub-key-000',
  maxOutputTokens: 500,
  outputLimitField: 'MAX_TOKENS',
  timeoutSeconds: 30,
})
console.log('保存配置:', cfg.status, cfg.body?.code ?? '', JSON.stringify(cfg.body).slice(0, 200))

const test = await o.write('POST', `/api/organizations/${org}/ai/config/test`, { version: cfg.body?.version })
console.log('连接测试:', test.status, test.body?.code ?? '', test.body?.detail ?? '')

const en = await o.write('POST', `/api/organizations/${org}/ai/config/enable`, { version: test.body?.version ?? cfg.body?.version })
console.log('启用 AI:', en.status, en.body?.code ?? '', 'available=', en.body?.available)

// ---------- 2) 造一个 REVIEWING 活动并提供反馈事实 ----------
const iso = (d) => `${new Date(now + d * 86400000 + 8 * 3600000).toISOString().slice(0, 19)}+08:00`
const act = await o.write('POST', `/api/organizations/${org}/activities`, {
  name: `审核取证·AI 并发 ${new Date(now).toISOString().slice(11, 19)}`,
  description: '验证 AI 生成并发同键的唯一键兜底', location: '审核地点', ownerMemberId: '1',
  startTime: iso(3), endTime: iso(5), registrationScope: 'INTERNAL', capacity: 5, budget: '500.00',
}, { 'Idempotency-Key': `audit-ai-act-${now}` })
const actId = act.body?.id
const dst = async () => (await o.json(`/api/organizations/${org}/activities/${actId}`)).body
let v = await dst()
await o.write('PUT', `/api/organizations/${org}/activities/${actId}/proposal`, { content: '审核取证方案。', version: v.version })
await o.write('POST', `/api/organizations/${org}/activities/${actId}/submit`)
v = await dst()
await o.write('POST', `/api/organizations/${org}/activities/${actId}/approve`, { note: '审核确认', version: v.version })

// 执行记录 + 反馈事实（总结生成的输入需要）
v = await dst()
await o.write('POST', `/api/organizations/${org}/activities/${actId}/start-execution`, { version: v.version })
await o.write('POST', `/api/organizations/${org}/activities/${actId}/execution-records`, { category: 'PROGRESS', content: '审核取证：完成场地布置' })
v = await dst()
await o.write('POST', `/api/organizations/${org}/activities/${actId}/finish-execution`, { version: v.version })
const after = await dst()
console.log('活动状态:', actId, after.status)

const input = await o.json(`/api/organizations/${org}/activities/${actId}/ai/input?type=ACTIVITY_SUMMARY`)
console.log('AI 输入:', input.status, input.body?.code ?? '', input.body?.blockedReason ?? '')

// ---------- 3) 并发同键生成（AI-01）----------
if (after.status === 'REVIEWING') {
  const key = `audit-ai-concurrent-${now}`
  const body = { type: 'ACTIVITY_SUMMARY' }
  const [r1, r2] = await Promise.all([
    o.write('POST', `/api/organizations/${org}/activities/${actId}/ai/generations`, body, { 'Idempotency-Key': key }),
    o.write('POST', `/api/organizations/${org}/activities/${actId}/ai/generations`, body, { 'Idempotency-Key': key }),
  ])
  console.log('AI-01 并发同键:', JSON.stringify({
    响应1: r1.status, code1: r1.body?.code, generationId1: r1.body?.generationId, status1: r1.body?.status,
    响应2: r2.status, code2: r2.body?.code, generationId2: r2.body?.generationId, status2: r2.body?.status,
    结论: [r1.status, r2.status].some((s) => s >= 500) ? '出现 5xx → 唯一键未兜底成立' : '未出现 5xx',
  }))
  const list = await o.json(`/api/organizations/${org}/activities/${actId}/ai/generations?type=ACTIVITY_SUMMARY`)
  console.log('生成记录数:', list.body?.items?.length ?? list.body?.length)
}

stub.close()
console.log('桩模型已关闭')
