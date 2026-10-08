// AI 保存即生效与工作台入口：全部 API 替身，禁止访问实际数据库或厂商。
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

const output = new URL('../验证截图/AI即存即用/', import.meta.url)
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const context = await browser.newContext({ reducedMotion: 'reduce', viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()
  page.setDefaultTimeout(6000)
  const errors = [], writes = []
  page.on('pageerror', error => errors.push(error.message))
  let enabled = false, version = 1, role = 'OWNER', activities = []
  const config = () => ({ viewerRole: role === 'OWNER' ? 'OWNER' : 'MEMBER', available: enabled, enabled,
    availabilityHint: enabled ? '可用：model-1' : '模型配置已停用，可继续手工编写',
    configState: enabled ? 'ENABLED' : 'DISABLED', protocol: 'OPENAI_CHAT_COMPLETIONS',
    endpoint: 'https://api.example.com/chat/completions', model: 'model-1', authScheme: 'BEARER',
    maxOutputTokens: 1024, outputLimitField: 'MAX_TOKENS', timeoutSeconds: 20, hasApiKey: true,
    version, connectionRevision: 1, testedRevision: null, testedForCurrentRevision: false, testedAt: null, testedBy: null })
  const org = () => ({ id: '1', name: '入口测试组织', description: '', memberCount: 1,
    role: role === 'OWNER' ? 'OWNER' : 'MEMBER', joinedAt: '2026-10-08', createdAt: '2026-10-08' })
  await context.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (request.method() !== 'GET') writes.push({ path, method: request.method() })
    let data
    if (path === '/api/auth/me') data = { id: '1', username: '入口测试' }
    else if (path === '/api/auth/csrf') data = { token: 'fixture', headerName: 'X-CSRF-TOKEN' }
    else if (path === '/api/me/organizations') data = { items: [org()] }
    else if (path === '/api/organizations/1') data = org()
    else if (path === '/api/organizations/1/workbench') data = { role, cards: [], todos: [], myCards: [] }
    else if (path === '/api/organizations/1/ai/config') {
      if (request.method() === 'PUT') {
        assert.equal(request.postDataJSON().version, version)
        enabled = true
        version++
      }
      data = config()
    } else if (path === '/api/organizations/1/ai/config/disable') {
      enabled = false
      version++
      data = config()
    } else if (path === '/api/organizations/1/activities') data = { items: activities, page: 1, pageSize: 50, total: activities.length }
    else if (path === '/api/organizations/1/activities/7') data = activities[0]
    else if (path === '/api/organizations/1/members') data = { items: [{ memberId: '1', username: '入口测试', role: 'OWNER' }], total: 1 }
    else {
      assert.equal(request.method(), 'GET', `未预设写请求：${path}`)
      data = { items: [], total: 0, unread: 0 }
    }
    await route.fulfill({ json: data })
  })
  const navigate = async path => {
    await page.evaluate(path => document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(path), path)
    await page.waitForLoadState('networkidle')
  }
  await page.goto('http://127.0.0.1:5174/org/1/settings', { waitUntil: 'networkidle' })
  await page.locator('[data-ai-action="save"]').click()
  await page.getByText('配置已保存并生效', { exact: false }).waitFor()
  assert.deepEqual(writes, [{ path: '/api/organizations/1/ai/config', method: 'PUT' }], '保存只发一次 PUT，不自动测试或另发启用')
  await page.getByText('已启用', { exact: true }).waitFor()
  await page.locator('[data-ai-action="disable"]').click()
  await page.getByText('已停用', { exact: true }).waitFor()
  await navigate('/org/1')
  await page.locator('[data-ai-settings]').click()
  await page.waitForURL('**/org/1/settings')
  await page.locator('[data-ai-action="save"]').click()
  await page.getByText('已启用', { exact: true }).waitFor()
  await navigate('/org/1')
  await page.locator('[data-ai-card]').getByRole('button', { name: '活动方案' }).click()
  assert.equal(await page.locator('[data-ai-open]').isEnabled(), true)
  assert.equal(await page.locator('[data-ai-settings]').count(), 0, '回主页刷新生效状态')
  await page.locator('[data-ai-open]').click()
  const dialog = page.getByRole('dialog', { name: '创建活动草稿', exact: true })
  await dialog.waitFor()
  assert.equal(new URL(page.url()).searchParams.has('create'), false, '创建意图只消费一次')
  await dialog.locator('.el-dialog__headerbtn').click()
  await navigate('/org/1')
  await page.locator('[data-ai-open]').click()
  await page.waitForURL('**/org/1/activities')
  assert.equal(await dialog.count(), 0, '无复盘活动应前往列表')
  activities = [{ id: '7', name: '入口测试草稿', description: '', status: 'DRAFT', ownerMemberId: '1',
    ownerUsername: '入口测试', proposal: '', budget: '0.00', version: 0, registrationScope: 'INTERNAL',
    registrationOpen: false, startTime: null, endTime: null, createdAt: '2026-10-08', updatedAt: '2026-10-08' }]
  await navigate('/org/1')
  await page.locator('[data-ai-card]').getByRole('button', { name: '活动方案' }).click()
  await page.locator('[data-ai-open]').click()
  await page.waitForURL('**/org/1/activities/7?tab=proposal')
  role = 'ACTIVITY_OWNER'
  activities = []
  await navigate('/org/1')
  await page.locator('[data-ai-card]').getByRole('button', { name: '活动方案' }).click()
  assert.equal((await page.locator('[data-ai-open]').innerText()).trim(), '查看活动')
  await page.locator('[data-ai-open]').click()
  await page.waitForURL('**/org/1/activities')
  assert.equal(await dialog.count(), 0, '活动负责人不能通过入口创建草稿')
  role = 'MEMBER'
  await navigate('/org/1')
  assert.equal(await page.locator('[data-ai-card]').count(), 0)
  role = 'OWNER'
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('http://127.0.0.1:5174/org/1', { waitUntil: 'networkidle' })
    await page.locator('[data-ai-card]').scrollIntoViewIfNeeded()
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await page.screenshot({ path: fileURLToPath(new URL(`主页入口-${width}.png`, output)) })
    if (width === 1440) {
      await page.getByRole('button', { name: '折叠侧栏', exact: true }).click()
      await page.screenshot({ path: fileURLToPath(new URL('主页入口-折叠.png', output)) })
    }
    await navigate('/org/1/settings')
    await page.locator('[data-ai-action="save"]').waitFor()
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await page.screenshot({ path: fileURLToPath(new URL(`保存即生效-${width}.png`, output)), fullPage: true })
  }
  assert.deepEqual(errors, [])
  assert.ok(writes.every(item => item.path.endsWith('/ai/config') || item.path.endsWith('/ai/config/disable')))
  await writeFile(new URL('结果.json', output), JSON.stringify({ status: 'PASS', writes,
    checks: ['保存仅一次PUT且立即生效', '停用后保存恢复', '回主页刷新状态', '无草稿打开原创建表单',
      '无复盘前往列表', '草稿跳转方案页签', '活动负责人不越权创建', '成员无生成入口', '桌面展开折叠和390无溢出'],
    pageErrors: errors, scope: '正式 Vue 页面、全部 API 替身、reduced-motion；未写库或调用厂商' }, null, 2))
  console.log('PASS：AI 即存即用与工作台入口（全部 API 替身）')
} finally {
  await browser.close()
}
