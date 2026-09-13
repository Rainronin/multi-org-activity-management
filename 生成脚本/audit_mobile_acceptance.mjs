// P3／P4 手机与窄屏验收核对：1440／1024／768／390／320 五档宽度、短屏（390×600）与 200% 缩放模拟。
// 逐页断言：无 pageerror、页面级无横向溢出、关键内容可见；截图存 验证截图/。
// 仅可针对显式连接 activity_test 的后端运行，会新增专用测试数据。
import assert from 'node:assert/strict'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')

const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4])
const WIDTHS = [1440, 1024, 768, 390, 320]
const SHORT_SCREEN = { width: 390, height: 600 }
const ZOOM_200 = { width: 720, height: 900, deviceScaleFactor: 2 }

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const shots = []
const results = []
try {
  const stamp = Date.now()
  const ownerName = `mobowner${stamp}`
  const memberName = `mobmember${stamp}`
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
    // 注册限流为 10 次／5 分钟／来源 IP；被限流时按提示等待重试，不绕过限制
    for (let attempt = 1; ; attempt += 1) {
      const res = await call(ctx, 'POST', '/api/auth/register', { username: name, password })
      if (res.ok()) {
        break
      }
      if (res.status() !== 429 || attempt >= 4) {
        throw new Error(`POST /api/auth/register -> ${res.status()} ${await res.text()}`)
      }
      await new Promise((resolve) => setTimeout(resolve, 60000))
    }
    await signIn(ctx, name)
  }
  /** 已注册账号的登录（不改动账号，用于逐宽度新建浏览器上下文）。 */
  async function signIn(ctx, name) {
    const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
    const res = await ctx.request.post('/api/auth/login', {
      form: { username: name, password }, headers: { [csrf.headerName]: csrf.token },
    })
    assert.equal(res.status(), 204, '准备流程：登录失败')
  }

  // 准备：一个组织、一个已确认活动（含任务、物资、资料）与一条报名
  const owner = await browser.newContext({ baseURL: base })
  await login(owner, ownerName)
  const member = await browser.newContext({ baseURL: base })
  await login(member, memberName)
  const org = await json(owner, 'POST', '/api/organizations', { name: `手机验收-${stamp}` })
  const ownerMemberId = (await json(owner, 'GET', `/api/organizations/${org.id}/members?pageSize=100`)).items
    .find((x) => x.username === ownerName).memberId
  const invitation = await json(owner, 'POST', `/api/organizations/${org.id}/invitations`, {})
  const accepted = await call(member, 'POST', '/api/invitations/accept', { code: invitation.code })
  assert.equal(accepted.status(), 200, '准备流程：接受邀请失败')
  const memberMemberId = (await json(owner, 'GET', `/api/organizations/${org.id}/members?pageSize=100`)).items
    .find((x) => x.username === memberName).memberId

  const activity = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
    name: '手机验收活动', description: '窄屏核对用活动', location: '线上',
    ownerMemberId,
    startTime: '2027-09-01T14:00:00+08:00', endTime: '2027-09-01T16:00:00+08:00',
    registrationScope: 'PUBLIC', capacity: 10,
    registrationStart: new Date(Date.now() - 3600000).toISOString(), registrationEnd: '2027-08-31T00:00:00+08:00',
  }, { 'Idempotency-Key': `mob-${stamp}` })
  await json(owner, 'PUT', `/api/organizations/${org.id}/activities/${activity.id}/proposal`,
    { content: '窄屏核对方案', version: activity.version })
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/submit`)
  const pending = await json(owner, 'GET', `/api/organizations/${org.id}/activities/${activity.id}`)
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/approve`,
    { note: '核对确认', version: pending.version })
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/registration-open`, { open: true })
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/tasks`, {
    title: '窄屏任务', assigneeMemberId: memberMemberId, priority: 'HIGH',
  })
  await json(owner, 'POST', `/api/organizations/${org.id}/activities/${activity.id}/materials`, {
    name: '窄屏物资', quantity: 2, unit: '件',
  })
  const csrfOwner = await (await owner.request.get('/api/auth/csrf')).json()
  await owner.request.fetch(`/api/organizations/${org.id}/activities/${activity.id}/attachments`, {
    method: 'POST',
    headers: { [csrfOwner.headerName]: csrfOwner.token, 'Idempotency-Key': `mob-file-${stamp}` },
    multipart: { file: { name: '窄屏资料.png', mimeType: 'image/png', buffer: PNG } },
  })
  await json(member, 'POST', `/api/activities/${activity.id}/registrations`, { displayName: memberName })

  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })

  /** 单页单宽度核对：无 pageerror、页面级无横向溢出、关键文案可见。 */
  async function check(label, path, viewport, expectText, shot, readySelector) {
    const context = await browser.newContext({ baseURL: base, viewport })
    try {
      if (label.includes('匿名')) {
        // 公开页用未登录上下文
      } else {
        await signIn(context, label.includes('成员') ? memberName : ownerName)
      }
      const page = await context.newPage()
      const errors = []
      page.on('pageerror', (e) => errors.push(e.message))
      await page.goto(path, { waitUntil: 'networkidle' })
      if (readySelector) {
        await page.waitForSelector(readySelector, { timeout: 15000 }).catch(() => undefined)
      }
      if (expectText) {
        // 关键文案作为“页面已就绪”的信号；找不到时按测量结果记录，不中断整轮核对
        await page.waitForSelector(`text=${expectText}`, { timeout: 15000 }).catch(() => undefined)
      }
      await page.waitForTimeout(300)
      const overflow = await page.evaluate(() => {
        const el = document.documentElement
        return { scrollWidth: el.scrollWidth, clientWidth: el.clientWidth }
      })
      const body = await page.locator('body').innerText()
      const entry = {
        label,
        viewport: `${viewport.width}x${viewport.height}${viewport.deviceScaleFactor ? `@${viewport.deviceScaleFactor}x` : ''}`,
        horizontalOverflow: overflow.scrollWidth > overflow.clientWidth + 1,
        overflowPx: overflow.scrollWidth - overflow.clientWidth,
        keyTextVisible: expectText ? body.includes(expectText) : true,
        pageErrors: errors,
      }
      if (expectText && !entry.keyTextVisible) {
        // 关键文案缺失时留证：表格行数与正文片段，便于判断是页面缺陷还是脚本预期过时
        entry.tableRows = await page.locator('.el-table__row').count()
        entry.bodyPreview = body.replace(/\s+/g, ' ').slice(0, 300)
      }
      results.push(entry)
      if (shot) {
        const file = fileURLToPath(new URL(`../验证截图/${shot}.png`, import.meta.url))
        await page.screenshot({ path: file, fullPage: true })
        shots.push(file)
      }
    } finally {
      await context.close()
    }
  }

  const detailPath = `/org/${org.id}/activities/${activity.id}`
  for (const width of WIDTHS) {
    await check('负责人-活动详情', detailPath, { width, height: 900 }, '窄屏任务',
      width === 390 || width === 320 ? `手机验收-详情-${width}` : null)
  }
  await check('负责人-活动列表', `/org/${org.id}/activities`, { width: 390, height: 900 }, '手机验收活动',
    '手机验收-列表-390', '.el-table__row')
  await check('负责人-成员列表', `/org/${org.id}/members`, { width: 390, height: 900 }, ownerName,
    '手机验收-成员-390', '.el-table__row')
  await check('成员-本人任务', `/org/${org.id}/my-tasks`, { width: 390, height: 900 }, '窄屏任务',
    '手机验收-成员首页-390')
  await check('成员-活动详情', detailPath, { width: 320, height: 900 }, '窄屏任务', null)
  // 公开页同样要过五档宽度：F10 收尾前这里曾在窄屏横向溢出（.public-main 内容盒）
  for (const width of WIDTHS) {
    await check('匿名-公开列表', '/public/activities', { width, height: 900 }, '公开活动',
      width === 390 ? '手机验收-公开列表-390' : null)
    await check('匿名-公开详情', `/public/activities/${activity.id}`, { width, height: 900 }, '手机验收活动',
      width === 390 ? '手机验收-公开详情-390' : null)
  }
  await check('负责人-活动详情-短屏', detailPath, SHORT_SCREEN, '窄屏任务', '手机验收-短屏-390x600')
  await check('负责人-活动详情-200%缩放', detailPath, ZOOM_200, '窄屏任务', '手机验收-缩放200-详情')

  const failing = results.filter((r) => r.horizontalOverflow || r.pageErrors.length > 0 || !r.keyTextVisible)
  console.log(JSON.stringify({
    check: 'P3／P4 窄屏与缩放核对',
    widths: WIDTHS,
    checks: results.length,
    failing: failing.length,
    details: failing,
    all: results.map((r) => ({
      label: r.label, viewport: r.viewport,
      overflowPx: r.overflowPx, pageErrors: r.pageErrors.length, keyTextVisible: r.keyTextVisible,
    })),
    screenshots: shots.map((p) => p.split('验证截图').pop()),
  }, null, 1))
} finally {
  await browser.close()
}
