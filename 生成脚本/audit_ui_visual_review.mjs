// 独立视觉复核：匿名认证页与 Demo 同视口对照；只读设置页、响应替身失败及失焦检查，不保存配置。
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const out = new URL('../验证截图/UI复核/', import.meta.url)
await mkdir(out, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const results = { auth: [], settings: {}, detailLoading: [], loadingWarnings: [], limitations: ['只读及浏览器响应替身，不进行业务提交或模型调用；尺寸是观察值，不等于视觉验收。'] }
const shot = (page, name) => page.screenshot({ path: fileURLToPath(new URL(`${name}.png`, out)), fullPage: true })
try {
  const anon = await browser.newContext()
  const page = await anon.newPage()
  for (const path of ['login', 'register', 'recover', 'demo']) {
    for (const width of [1440, 1024, 768, 390, 320]) {
      await page.setViewportSize({ width, height: width > 700 ? 1000 : 720 })
      await page.goto(path === 'demo' ? 'http://127.0.0.1:5173/?view=login' : `http://127.0.0.1:5174/${path}`, { waitUntil: 'networkidle' })
      const state = await page.evaluate(() => {
        const box = (selector) => {
          const node = document.querySelector(selector)
          if (!node) return null
          const rect = node.getBoundingClientRect(); const css = getComputedStyle(node)
          return { x: Math.round(rect.x), y: Math.round(rect.y), width: Math.round(rect.width), height: Math.round(rect.height), fontSize: css.fontSize, padding: css.padding, display: css.display }
        }
        return {
          overflow: document.documentElement.scrollWidth - innerWidth,
          brand: box('.auth-brand, .login-brand'), slogan: box('.auth-slogan, .login-story h1'),
          form: box('.auth-content, .login-form-content'), input: box('.el-input__wrapper'), button: box('.auth-submit, .login-submit'),
          touchToken: getComputedStyle(document.documentElement).getPropertyValue('--ui-control-min-touch').trim(),
        }
      })
      results.auth.push({ path, width, ...state })
      if ([1440, 390].includes(width)) await shot(page, `${path}-${width}`)
    }
  }
  const touch = await browser.newContext({ viewport: { width: 390, height: 720 }, isMobile: true, hasTouch: true })
  const mobile = await touch.newPage()
  await mobile.goto('http://127.0.0.1:5174/login', { waitUntil: 'networkidle' })
  results.touchButton = await mobile.locator('.auth-submit').evaluate(e => ({ height: e.getBoundingClientRect().height, coarse: matchMedia('(pointer: coarse)').matches, localSize: getComputedStyle(e).getPropertyValue('--el-button-size'), token: getComputedStyle(e).getPropertyValue('--ui-control-min-touch') }))
  const ctx = await browser.newContext({ baseURL: 'http://127.0.0.1:5174', viewport: { width: 1440, height: 1000 } })
  const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
  const login = await ctx.request.post('/api/auth/login', { form: { username: process.env.UI_AUDIT_USER || 'demo_owner_yrnn', password: process.env.UI_AUDIT_PASS || 'Demo12345!' }, headers: { [csrf.headerName]: csrf.token } })
  if (login.status() !== 204) throw new Error('测试账号登录失败')
  const org = (await (await ctx.request.get('/api/me/organizations')).json()).items[0]
  const owner = await ctx.newPage()
  owner.on('console', message => { if (message.text().includes('Failed to resolve directive')) results.loadingWarnings.push(message.text().split('\n')[0]) })
  await owner.goto(`/org/${org.id}/settings`, { waitUntil: 'networkidle' })
  const endpoint = owner.locator('input[data-ai-field="endpoint"], [data-ai-field="endpoint"] input').first()
  await endpoint.fill('not-a-url'); await endpoint.blur()
  results.settings.invalidEndpointErrors = await owner.locator('.el-form-item__error').allTextContents()
  results.settings.invalidEndpointAria = await endpoint.getAttribute('aria-invalid')
  await shot(owner, '设置-非法地址失焦')
  await owner.route('**/api/organizations/*/ai/config', route => route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({ status: 503, code: 'UNAVAILABLE', detail: '复核失败状态' }) }))
  await owner.reload({ waitUntil: 'networkidle' })
  results.settings.failureText = await owner.locator('.ai-config').innerText()
  results.settings.retryButtons = await owner.locator('.ai-config').getByRole('button', { name: /重试|重新加载/ }).count()
  await shot(owner, '设置-失败无重试')
  await owner.unrouteAll()
  await owner.goto('/', { waitUntil: 'networkidle' })
  await shot(owner, '账号-正常')
  const activities = (await (await ctx.request.get(`/api/organizations/${org.id}/activities?pageSize=50`)).json()).items
  const activity = activities.find(a => a.status === 'APPROVED')
  if (!activity) throw new Error('缺少已确认活动用于页签核对')
  // 用任务区验证共用加载指令；经费与报名的同类调用另作源码核对，不冒充逐区运行通过。
  for (const tab of ['tasks']) {
    let release
    const held = new Promise(resolve => { release = resolve })
    let started
    const requested = new Promise(resolve => { started = resolve })
    const resource = { tasks: 'tasks', expense: 'expenses', registration: 'registrations' }[tab]
    await owner.route(url => url.pathname === `/api/organizations/${org.id}/activities/${activity.id}/${resource}`, async route => { started(); await held; await route.continue() })
    await owner.goto(`/org/${org.id}/activities/${activity.id}?tab=${tab}`, { waitUntil: 'domcontentloaded' })
    let timer
    try {
      await Promise.race([requested, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('任务读取未触发，核对当前角色与页签')), 10000) })])
    } finally { clearTimeout(timer) }
    await owner.locator('.el-table:visible').first().waitFor({ state: 'visible' })
    results.detailLoading.push({ tab, emptyText: await owner.locator('.el-table__empty-text').allTextContents(), skeleton: await owner.locator('.el-skeleton').count(), overlays: await owner.locator('.el-loading-mask').count() })
    await shot(owner, `详情-${tab}-慢响应`)
    release()
    await owner.unrouteAll({ behavior: 'wait' })
  }
  await writeFile(new URL('结果.json', out), JSON.stringify(results, null, 2))
  await writeFile(new URL('登录对照.html', out), '<!doctype html><meta charset="utf-8"><title>登录独立复核</title><style>body{font:16px system-ui;background:#eee;margin:24px}main{display:grid;grid-template-columns:1fr 1fr;gap:20px}img{width:100%}figure{margin:0}figcaption{padding:12px}</style><h1>登录视觉复核：左 Demo，右正式</h1><p>同视口匿名会话；截图仅用于审核，不代表通过。</p><main>' + [['Demo 1440','demo-1440'],['正式 1440','login-1440'],['Demo 390','demo-390'],['正式 390','login-390']].map(([label,file])=>`<figure><figcaption>${label}</figcaption><img src="${file}.png"></figure>`).join('') + '</main>')
  console.log(JSON.stringify(results, null, 2))
} finally {
  await browser.close()
}
