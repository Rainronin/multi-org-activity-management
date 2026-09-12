// P1.3 审核复现。仅针对已显式连接 activity_test 的本地后端运行；会新增独立测试账号与组织。
// 依赖已有 UI 演示的 Playwright，不安装新依赖。输出不含密码、邀请码或恢复码。
import assert from 'node:assert/strict'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
import { mkdir } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
const base = 'http://127.0.0.1:5174'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('须先核实后端连接测试库，再设置 AUDIT_TEST_DATABASE=activity_test')
const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const owner = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const member = await browser.newContext({ baseURL: base })
  async function call(ctx, method, path, data) {
    const headers = {}
    if (method !== 'GET') {
      const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
      headers[csrf.headerName] = csrf.token
    }
    return ctx.request.fetch(path, { method, headers, ...(data === undefined ? {} : { data }) })
  }
  async function signup(ctx, suffix) {
    const username = `audit${Date.now()}${suffix}`
    const password = `Audit${Date.now()}Pass`
    assert.equal((await call(ctx, 'POST', '/api/auth/register', { username, password })).status(), 201)
    const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
    assert.equal((await ctx.request.post('/api/auth/login', { form: { username, password }, headers: { [csrf.headerName]: csrf.token } })).status(), 204)
    return username
  }
  const ownerName = await signup(owner, 'a')
  const memberName = await signup(member, 'b')
  const org1 = await (await call(owner, 'POST', '/api/organizations', { name: '审核组织甲' })).json()
  const org2 = await (await call(owner, 'POST', '/api/organizations', { name: '审核组织乙' })).json()
  const invitation = await (await call(owner, 'POST', `/api/organizations/${org1.id}/invitations`, {})).json()
  assert.equal((await call(member, 'POST', '/api/invitations/accept', { code: invitation.code })).status(), 200)
  const before = await (await call(owner, 'GET', `/api/organizations/${org1.id}/members`)).json()
  const memberId = before.items.find(x => x.username === memberName).memberId
  assert.equal((await call(owner, 'DELETE', `/api/organizations/${org1.id}/members/${memberId}`)).status(), 204)
  const repeatedRemoval = await call(owner, 'DELETE', `/api/organizations/${org1.id}/members/${memberId}`)
  const oldInvite = await call(member, 'POST', '/api/invitations/accept', { code: invitation.code })
  const after = await (await call(owner, 'GET', `/api/organizations/${org1.id}/members`)).json()
  const nextId = after.items.find(x => x.username === memberName)?.memberId
  console.log(JSON.stringify({ check: '实际 HTTP 成员链路', repeatedRemovalStatus: repeatedRemoval.status(), oldInviteRejoinStatus: oldInvite.status(), membershipIdChanged: memberId !== nextId }))

  const invitation2 = await (await call(owner, 'POST', `/api/organizations/${org2.id}/invitations`, {})).json()
  // 测试库短暂锁住新建组织，确保两个真实请求都在首次读邀请后等待同一组织锁。
  const lock = spawn('C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe', ['-h127.0.0.1', '-uroot', '--unbuffered', '-N', 'activity_test', '-e', `START TRANSACTION; SELECT id FROM organization WHERE id=${Number(org2.id)} FOR UPDATE; SELECT 'AUDIT_LOCKED'; DO SLEEP(2); COMMIT;`], { windowsHide: true })
  await new Promise((resolve, reject) => {
    lock.stdout.on('data', data => { if (String(data).includes('AUDIT_LOCKED')) resolve() })
    lock.on('error', reject)
    lock.on('exit', code => { if (code) reject(new Error('测试库锁准备失败')) })
  })
  const parallelAccept = await Promise.all([1, 2].map(() => call(member, 'POST', '/api/invitations/accept', { code: invitation2.code })))
  console.log(JSON.stringify({ check: '同账号并发接受邀请（真实 MySQL 锁等待）', statuses: parallelAccept.map(x => x.status()) }))
  const secondMembers = await (await call(owner, 'GET', `/api/organizations/${org2.id}/members`)).json()
  const secondMemberId = secondMembers.items.find(x => x.username === memberName).memberId
  assert.equal((await call(owner, 'DELETE', `/api/organizations/${org2.id}/members/${secondMemberId}`)).status(), 204)

  const page = await owner.newPage()
  await page.goto(`/org/${org1.id}/members`)
  await page.getByRole('cell', { name: memberName, exact: true }).waitFor()
  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
  await page.screenshot({ path: fileURLToPath(new URL('../验证截图/审核-P13-桌面.png', import.meta.url)), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  const mobile = await page.evaluate(() => ({ viewport: innerWidth, scrollWidth: document.documentElement.scrollWidth }))
  await page.screenshot({ path: fileURLToPath(new URL('../验证截图/审核-P13-手机.png', import.meta.url)), fullPage: true })
  console.log(JSON.stringify({ check: '成员页手机布局', ...mobile }))
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.evaluate(async (id) => { const { router } = await import('/src/router/index.ts'); await router.push(`/org/${id}/members`) }, org2.id)
  await page.getByText('审核组织乙', { exact: true }).first().waitFor()
  console.log(JSON.stringify({ check: '同组件切换组织路由', oldMemberStillVisible: await page.getByRole('cell', { name: memberName, exact: true }).count() > 0 }))

  const stale = await page.evaluate(async () => {
    const pinia = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia
    const { useSessionStore } = await import('/src/stores/session.ts')
    const { useOrganizationsStore } = await import('/src/stores/organizations.ts')
    const { ApiError } = await import('/src/shared/http/error.ts')
    const session = useSessionStore(pinia)
    const directory = useOrganizationsStore(pinia)
    const originalFetch = window.fetch
    let release
    window.fetch = async (path, options) => String(path).includes('/api/me/organizations')
      ? new Promise(resolve => { release = () => resolve(new Response(JSON.stringify({ items: [{ id: 'old-account-org', name: '旧账号组织', role: 'OWNER', joinedAt: '2026-09-12' }] }), { headers: { 'Content-Type': 'application/json' } })) })
      : originalFetch(path, options)
    const loading = directory.fetchDirectory(true)
    await new Promise(resolve => setTimeout(resolve, 0))
    directory.clear()
    release()
    await loading
    window.fetch = originalFetch
    const oldDataRestoredAfterClear = directory.items.some(x => x.id === 'old-account-org')
    const old401 = new ApiError({ kind: 'server', status: 401, code: 'AUTH_REQUIRED', detail: '旧请求测试' })
    await session.bootstrap()
    const beforeStatus = session.status
    session.observeUnauthorized(old401)
    return { oldDataRestoredAfterClear, beforeStatus, afterLate401: session.status, directoryClearedOn401: directory.items.length === 0 }
  })
  console.log(JSON.stringify({ check: '会话与目录迟到响应（浏览器内定向控制响应）', ...stale }))
  console.log(JSON.stringify({ check: '测试数据边界', database: 'activity_test', accountsCreated: 2, organizationsCreated: 2, realBackendAuth: true }))
} finally { await browser.close() }
