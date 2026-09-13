// F05／F06／F07 真机流程核对：界面创建活动（防重键）、界面编辑（版本前置）、界面与接口上传资料、
// 中文文件名下载头、同键重放与过期版本拒绝。
// 仅可针对显式连接 activity_test 的后端运行，会新增专用测试数据。
import assert from 'node:assert/strict'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')

/** 最小合法 PNG（魔数 + 少量数据），用于上传核对。 */
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4])

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const shots = []
try {
  const stamp = Date.now()
  const username = `f5owner${stamp}`
  const password = `Pass${stamp}word`
  const base = 'http://127.0.0.1:5174'

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
  async function login(ctx, name) {
    await json(ctx, 'POST', '/api/auth/register', { username: name, password })
    const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
    const res = await ctx.request.post('/api/auth/login', {
      form: { username: name, password }, headers: { [csrf.headerName]: csrf.token },
    })
    assert.equal(res.status(), 204, '准备流程：登录失败')
  }

  const owner = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  await login(owner, username)
  const org = await json(owner, 'POST', '/api/organizations', { name: `F05核对-${stamp}` })
  const me = (await json(owner, 'GET', `/api/organizations/${org.id}/members?pageSize=100`)).items
    .find((x) => x.username === username)
  const ownerMemberId = me.memberId

  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
  const page = await owner.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))

  // 1. 界面创建活动：F06 缺防重键会 400，这条路径此前一直失败
  await page.goto(`/org/${org.id}/activities`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '创建活动' }).click()
  await page.getByPlaceholder('1-128 字符').fill('F05 界面创建活动')
  await page.locator('.el-dialog .el-select').first().click()
  await page.locator('.el-select-dropdown__item').filter({ hasText: username }).first().click()
  await page.getByRole('button', { name: '保存草稿' }).click()
  await page.waitForSelector('text=F05 界面创建活动', { timeout: 15000 })
  const createShot = fileURLToPath(new URL('../验证截图/F05-界面创建活动.png', import.meta.url))
  await page.screenshot({ path: createShot, fullPage: true })
  shots.push(createShot)
  const created = (await json(owner, 'GET', `/api/organizations/${org.id}/activities?pageSize=20`)).items
    .find((x) => x.name === 'F05 界面创建活动')
  assert.ok(created, '界面创建的活动未出现在列表')
  const activityId = created.id

  // 2. 界面编辑（F05 版本前置）：保存地点，版本递增
  await page.goto(`/org/${org.id}/activities/${activityId}`, { waitUntil: 'networkidle' })
  await page.waitForSelector('text=编辑信息', { timeout: 15000 })
  await page.getByRole('button', { name: '编辑信息' }).click()
  await page.locator('.el-dialog').getByLabel('地点').fill('F05 改过的地点')
  await page.locator('.el-dialog').getByRole('button', { name: '保存' }).click()
  await page.waitForSelector('text=F05 改过的地点', { timeout: 15000 })
  const afterEdit = await json(owner, 'GET', `/api/organizations/${org.id}/activities/${activityId}`)
  assert.equal(afterEdit.location, 'F05 改过的地点')
  assert.ok(afterEdit.version > created.version, '界面保存后版本未递增')

  // 3. 过期版本编辑被拒（F05）：用旧版本号再存一次
  const stale = await call(owner, 'PATCH', `/api/organizations/${org.id}/activities/${activityId}`,
    { location: '过期覆盖', version: created.version })
  const staleBody = await stale.json().catch(() => null)
  const missingVersion = await call(owner, 'PATCH', `/api/organizations/${org.id}/activities/${activityId}`,
    { location: '缺版本' })

  // 4. 提交确认后再走界面资料区（P3 区块只在非草稿时渲染），验证上传防重键与权限
  const v1 = (await json(owner, 'GET', `/api/organizations/${org.id}/activities/${activityId}`)).version
  await json(owner, 'PUT', `/api/organizations/${org.id}/activities/${activityId}/proposal`,
    { content: 'F05 方案', version: v1 })
  const v2 = (await json(owner, 'GET', `/api/organizations/${org.id}/activities/${activityId}`)).version
  await json(owner, 'PATCH', `/api/organizations/${org.id}/activities/${activityId}`, {
    description: 'F05 核对活动', startTime: '2027-07-01T14:00:00+08:00', endTime: '2027-07-01T16:00:00+08:00',
    location: 'F05 改过的地点', version: v2,
  })
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activityId}/submit`)
  const pendingVersion = (await json(owner, 'GET', `/api/organizations/${org.id}/activities/${activityId}`)).version
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activityId}/approve`,
    { note: '核对确认', version: pendingVersion })

  await page.goto(`/org/${org.id}/activities/${activityId}`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '上传资料' }).first().click().catch(() => undefined)
  await page.setInputFiles('input[type=file]', { name: 'F05 资料.png', mimeType: 'image/png', buffer: PNG })
  await page.waitForSelector('text=F05 资料.png', { timeout: 20000 })
  const uploadShot = fileURLToPath(new URL('../验证截图/F05-界面上传资料.png', import.meta.url))
  await page.screenshot({ path: uploadShot, fullPage: true })
  shots.push(uploadShot)
  const attachments = (await json(owner, 'GET',
    `/api/organizations/${org.id}/activities/${activityId}/attachments`)).items
  const uploaded = attachments.find((x) => x.originalName === 'F05 资料.png')
  assert.ok(uploaded, '界面上传的资料未出现在列表中')

  const download = await call(owner, 'GET',
    `/api/organizations/${org.id}/activities/${activityId}/attachments/${uploaded.attachmentId}/download`)
  const disposition = download.headers()['content-disposition'] || ''

  // 5. 接口防重：同键重放返回原资源；活动被编辑后仍返回原资源（F06）
  const replayKey = `f05-${stamp}`
  const body = {
    name: 'F06 防重活动', ownerMemberId: Number(ownerMemberId), description: '防重核对',
    startTime: '2027-06-01T14:00:00+08:00', endTime: '2027-06-01T16:00:00+08:00',
  }
  const first = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, body,
    { 'Idempotency-Key': replayKey })
  await json(owner, 'PATCH', `/api/organizations/${org.id}/activities/${first.id}`,
    { location: '防重活动改过地点', version: first.version })
  const replay = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, body,
    { 'Idempotency-Key': replayKey })
  const conflict = await call(owner, 'POST', `/api/organizations/${org.id}/activities`,
    { ...body, name: 'F06 另一个活动' }, { 'Idempotency-Key': replayKey })
  const noKey = await call(owner, 'POST', `/api/organizations/${org.id}/activities`, body)

  console.log(JSON.stringify({
    check: 'F05 版本前置 / F06 创建防重 / F07 上传与文件名',
    uiCreateActivity: { created: !!created, version: created.version },
    uiEditBumpsVersion: { before: created.version, after: afterEdit.version },
    staleVersionEdit: { status: stale.status(), code: staleBody?.code },
    missingVersionEdit: { status: missingVersion.status() },
    uiUploadAttachment: { listed: !!uploaded, count: attachments.length },
    downloadDisposition: {
      hasUtf8Star: disposition.includes("filename*=UTF-8''"),
      doubleEncoded: disposition.includes('%25'),
      value: disposition,
    },
    replayAfterEdit: { sameId: replay.id === first.id, status: 201 },
    reusedKeyDifferentContent: { status: conflict.status(), code: (await conflict.json().catch(() => null))?.code },
    createWithoutKey: { status: noKey.status(), code: (await noKey.json().catch(() => null))?.code },
    browserErrors: errors,
    screenshots: shots.map((p) => p.split('验证截图').pop()),
  }))
} finally {
  await browser.close()
}
