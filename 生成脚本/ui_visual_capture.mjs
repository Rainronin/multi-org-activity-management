// UI 评估截图（审核用，一次性）：登录负责人会话，遍历主要页面并截图，供人眼评估构图与美感。
// 只读页面 + 一次登录，不写业务数据。
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

const base = process.env.UI_BASE || 'http://127.0.0.1:5174'
const username = process.env.UI_AUDIT_USER || 'demo_owner_yrnn'
const password = process.env.UI_AUDIT_PASS || 'Demo12345!'
const outDir = fileURLToPath(new URL('../验证截图/UI评估/', import.meta.url))
await mkdir(outDir, { recursive: true })

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const shots = []
async function shot(page, name, full = false) {
  await page.screenshot({ path: path.join(outDir, `${name}.png`), fullPage: full })
  shots.push(name)
  console.log('shot', name)
}

// 排版度量：抽取关键元素的最终字号／字重／间距，用于客观评估视觉层级
const metrics = {}
async function measure(page, key) {
  metrics[key] = await page.evaluate(() => {
    const pick = (sel) => {
      const el = document.querySelector(sel)
      if (!el) return null
      const s = getComputedStyle(el)
      const r = el.getBoundingClientRect()
      return {
        fontSize: s.fontSize, fontWeight: s.fontWeight, lineHeight: s.lineHeight,
        color: s.color, bg: s.backgroundColor, radius: s.borderRadius,
        w: Math.round(r.width), h: Math.round(r.height),
      }
    }
    return {
      h1: pick('h1'),
      h2: pick('h2'),
      panel: pick('.panel, .el-card, [data-focus-activity]'),
      primaryBtn: pick('.el-button--primary'),
      tableRow: pick('.el-table__row'),
      nav: pick('.sidebar, [data-nav], nav'),
      emptyImg: document.querySelector('.el-empty__image') ? 'el-empty__image' : 'none',
    }
  })
}

try {
  // 负责人会话
  const ctx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
  const login = await ctx.request.post('/api/auth/login', {
    form: { username, password }, headers: { [csrf.headerName]: csrf.token },
  })
  console.log('登录:', login.status(), username)
  const orgs = await (await ctx.request.get('/api/me/organizations')).json()
  const orgId = orgs.items[0].id
  const acts = await (await ctx.request.get(`/api/organizations/${orgId}/activities?pageSize=20`)).json()
  const actId = acts.items[0].id
  console.log('orgId:', orgId, 'actId:', actId, '活动数:', acts.items.length)

  const page = await ctx.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))

  const visit = async (path, name, opts = {}) => {
    await page.goto(path, { waitUntil: 'networkidle' })
    await page.waitForTimeout(opts.wait ?? 900)
    await shot(page, name, opts.full === true)
    if (opts.measure) await measure(page, opts.measure)
  }

  await visit(`/org/${orgId}`, '01-工作台', { full: true, measure: 'workbench' })
  await visit(`/org/${orgId}/activities`, '02-活动列表', { measure: 'activities' })
  await visit(`/org/${orgId}/my-tasks`, '03-我的任务', { measure: 'tasks' })
  await visit(`/org/${orgId}/members`, '04-成员管理', { measure: 'members' })
  await visit(`/org/${orgId}/settings`, '05-组织设置', { full: true })
  await visit('/', '06-账号页', { measure: 'account' })
  await visit(`/org/${orgId}/activities/${actId}`, '07-活动详情-概览', { full: true, measure: 'detail' })
  await visit(`/org/${orgId}/activities/${actId}?tab=proposal`, '08-活动详情-方案')
  await visit(`/org/${orgId}/activities/${actId}?tab=tasks`, '09-活动详情-任务与物资')
  await visit(`/org/${orgId}/activities/${actId}?tab=expense`, '10-活动详情-经费')
  await visit(`/org/${orgId}/activities/${actId}?tab=lifecycle`, '11-活动详情-执行与复盘')

  // 手机宽度
  const mobile = await ctx.newPage()
  await mobile.setViewportSize({ width: 390, height: 720 })
  await mobile.goto(`/org/${orgId}/activities`, { waitUntil: 'networkidle' })
  await mobile.waitForTimeout(800)
  await shot(mobile, '12-手机-活动列表')
  await mobile.goto(`/org/${orgId}`, { waitUntil: 'networkidle' })
  await mobile.waitForTimeout(800)
  await shot(mobile, '13-手机-工作台首屏')

  console.log('页面错误:', errors.length, errors.slice(0, 3))

  // 公开页（未登录）
  const anon = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const pub = await anon.newPage()
  await pub.goto('/public/activities', { waitUntil: 'networkidle' })
  await pub.waitForTimeout(800)
  await shot(pub, '14-公开活动列表')
  await pub.goto('/public/activities/3', { waitUntil: 'networkidle' })
  await pub.waitForTimeout(800)
  await shot(pub, '15-公开活动详情', true)

  console.log('\n全部截图:', shots.join(', '))
  console.log('\n排版度量:', JSON.stringify(metrics, null, 2))
} finally {
  await browser.close()
}
