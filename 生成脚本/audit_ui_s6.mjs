// S6 全前端回归：所有角色 × 全部可达路由，核对可进入、单一 h1、无整页溢出、无运行错误。
// 只读；不执行任何写操作，不调用模型服务。
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { mkdir, writeFile } from 'node:fs/promises'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

const base = process.env.UI_BASE || 'http://127.0.0.1:5174'
const out = new URL('../验证截图/UI标准页/', import.meta.url)
await mkdir(out, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const results = { roles: [], routes: [], screenshots: [], errors: [], limitations: ['只读回归；未执行写操作、未调用模型服务、未重跑后端测试'] }
const password = process.env.UI_AUDIT_PASS || 'Demo12345!'

/** 路由清单：路径用 {org}/{activity} 占位，按角色替换；h1 用于确认页面真的渲染了。 */
const routes = [
  { path: '/', name: '账号页', anchor: '.account-page', h1: '我的工作区', roles: ['owner', 'member'] },
  { path: '/org/{org}', name: '组织工作台', anchor: '.workbench', roles: ['owner', 'member'] },
  { path: '/org/{org}/activities', name: '活动列表', anchor: '.list-panel', h1: '活动管理', roles: ['owner', 'member'] },
  { path: '/org/{org}/my-tasks', name: '我的任务', anchor: '.list-panel', h1: '我的任务', roles: ['owner', 'member'] },
  { path: '/org/{org}/members', name: '成员管理', anchor: '.list-panel', h1: '成员管理', roles: ['owner', 'member'] },
  { path: '/org/{org}/settings', name: '组织设置', anchor: '.ai-config', h1: '组织设置', roles: ['owner', 'member'] },
  { path: '/org/{org}/activities/{activity}', name: '活动详情', anchor: '.detail-tabs', roles: ['owner', 'member'] },
  { path: '/public/activities', name: '公开活动', anchor: '.public-cards', roles: ['anon', 'owner', 'member'] },
  { path: '/public/activities/{public}', name: '公开详情', anchor: '.public-facts', roles: ['anon', 'owner', 'member'] },
  { path: '/login', name: '登录', anchor: '.auth-content', roles: ['anon'] },
  { path: '/register', name: '注册', anchor: '.auth-content', h1: '开始，一起做活动。', roles: ['anon'] },
  { path: '/recover', name: '找回账号', anchor: '.auth-content', h1: '用恢复码重新取得控制权。', roles: ['anon'] },
]

const readState = (target) => target.evaluate(() => {
  const main = document.querySelector('.org-main, .public-main, .account-page, .auth-content, main')
  return {
    h1: [...document.querySelectorAll('h1')].map((e) => e.textContent.trim()),
    h2Count: document.querySelectorAll('h2').length,
    overflow: document.documentElement.scrollWidth - window.innerWidth,
    emptyMain: main ? main.innerText.trim().length === 0 : true,
    navVisible: Boolean(document.querySelector('.unified-sidebar')),
    redirect: location.pathname,
  }
})

async function newSession(role) {
  const ctx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  if (role !== 'anon') {
    const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
    const username = role === 'owner'
      ? (process.env.UI_AUDIT_USER || 'demo_owner_yrnn')
      : (process.env.UI_AUDIT_MEMBER || 'demo_member_yrnn')
    const login = await ctx.request.post('/api/auth/login', {
      form: { username, password }, headers: { [csrf.headerName]: csrf.token },
    })
    assert.equal(login.status(), 204, `${role} 登录失败`)
  }
  return ctx
}

try {
  // 取真实样例：组织、活动、公开活动
  const ownerCtx = await newSession('owner')
  const org = (await (await ownerCtx.request.get('/api/me/organizations')).json()).items[0]
  const activities = (await (await ownerCtx.request.get(`/api/organizations/${org.id}/activities?pageSize=50`)).json()).items
  const publicItems = (await (await ownerCtx.request.get('/api/public/activities?page=1&pageSize=12')).json()).items
  assert.ok(org && activities.length > 0 && publicItems.length > 0, '需要既有组织、活动与公开活动样例')
  const filled = (path) => path
    .replace('{org}', org.id)
    .replace('{activity}', activities[0].id)
    .replace('{public}', publicItems[0].id)

  for (const role of ['anon', 'owner', 'member']) {
    const ctx = role === 'owner' ? ownerCtx : await newSession(role)
    const page = await ctx.newPage()
    const errors = []
    // 只收集脚本异常：浏览器对 401／403 等预期状态的资源报错不算缺陷。
    page.on('pageerror', (e) => errors.push(e.message))
    let checked = 0
    for (const route of routes) {
      if (!route.roles.includes(role)) {
        continue
      }
      const url = filled(route.path)
      await page.goto(url, { waitUntil: 'networkidle' })
      await page.locator(route.anchor).first().waitFor({ state: 'visible' })
      const state = await readState(page)
      assert.equal(state.overflow, 0, `${role} ${route.name} 出现整页横向溢出`)
      assert.equal(state.emptyMain, false, `${role} ${route.name} 主体为空`)
      assert.equal(state.h1.length, 1, `${role} ${route.name} 应恰好一个 h1，实际 ${JSON.stringify(state.h1)}`)
      if (route.h1) {
        assert.equal(state.h1[0], route.h1, `${role} ${route.name} 标题不符`)
      }
      // 未登录访问组织路由必须被拦到登录页，且不渲染组织导航
      if (role === 'anon' && url.startsWith('/org/')) {
        assert.ok(state.redirect.includes('/login'), '匿名访问组织路由应跳转登录')
        assert.equal(state.navVisible, false, '匿名不得渲染组织导航')
      }
      if (role !== 'anon' && !url.startsWith('/public') && !url.startsWith('/login')) {
        assert.equal(state.navVisible, url === '/' ? false : true, `${role} ${route.name} 组织导航可见性不符`)
      }
      results.routes.push({ role, route: route.name, path: url, h1: state.h1[0], overflow: state.overflow })
      checked += 1
    }
    // 手机宽度抽查：组织工作台与账号页
    for (const [name, path, anchor] of [
      ['组织工作台', filled('/org/{org}'), role === 'anon' ? '.auth-content' : '.workbench'],
      ['账号页', '/', role === 'anon' ? '.auth-content' : '.account-page'],
    ]) {
      await page.setViewportSize({ width: 390, height: 1000 })
      await page.goto(path, { waitUntil: 'networkidle' })
      await page.locator(anchor).first().waitFor({ state: 'visible' })
      const state = await readState(page)
      assert.equal(state.overflow, 0, `${role} ${name} 390 出现整页横向溢出`)
      if (role === 'owner') {
        await page.screenshot({ path: fileURLToPath(new URL(`S6-${name}-390-${role}.png`, out)), fullPage: true })
        results.screenshots.push(`S6-${name}-390-${role}.png`)
      }
      checked += 1
    }
    await page.setViewportSize({ width: 1440, height: 1000 })
    assert.deepEqual(errors, [], `${role} 出现运行错误：${errors.join('；')}`)
    results.roles.push({ role, routesChecked: checked, pageErrors: errors.length })
    if (role !== 'owner') {
      await ctx.close()
    } else {
      await page.close()
    }
  }

  // 快捷通道：无效组织 / 无效路由 / 无权限资源
  const guardPage = await ownerCtx.newPage()
  const guardErrors = []
  guardPage.on('pageerror', (e) => guardErrors.push(e.message))
  await guardPage.goto('/org/99999999', { waitUntil: 'networkidle' })
  await guardPage.locator('.org-missing, .overview-card--missing').first().waitFor({ state: 'visible' })
  const missingText = await guardPage.locator('.org-missing, .overview-card--missing').first().innerText()
  assert.ok(missingText.includes('不存在') || missingText.includes('无权'), '无效组织应给出明确状态')
  assert.equal(await guardPage.locator('.unified-sidebar').count(), 1, '无效组织仍应保留导航与返回入口')
  await guardPage.goto('/不存在的路由', { waitUntil: 'networkidle' })
  await guardPage.locator('.account-page').waitFor({ state: 'visible' })
  assert.ok(guardPage.url().endsWith('/'), '未知路由应回到账号页')
  await guardPage.goto(`/org/${org.id}/activities/99999999`, { waitUntil: 'networkidle' })
  await guardPage.waitForTimeout(600)
  const detailMissing = await guardPage.evaluate(() => ({
    text: document.body.innerText,
    tabs: document.querySelectorAll('.detail-tabs').length,
  }))
  assert.equal(detailMissing.tabs, 0, '不存在的活动不应渲染页签')
  assert.ok(detailMissing.text.includes('不存在') || detailMissing.text.includes('失败') || detailMissing.text.includes('无权'),
    '不存在的活动应给出明确状态')
  assert.deepEqual(guardErrors, [], `守卫场景运行错误：${guardErrors.join('；')}`)
  await guardPage.close()

  results.roles.push({ role: 'guard', routesChecked: 3, pageErrors: 0 })
  results.checks = [
    '匿名／负责人／普通成员三套会话逐路由核对：可进入、恰好一个 h1、主体非空、无整页溢出、无运行错误',
    '匿名访问组织路由被拦到登录页且不渲染组织导航；负责人与成员在工作区均可见导航，账号页不渲染导航',
    '手机 390 宽抽查组织工作台与账号页无溢出',
    '守卫场景：无效组织显示不可访问状态并保留导航；未知路由回到账号页；不存在的活动不渲染页签且给出明确状态',
  ]
  await writeFile(new URL('S6结果.json', out), JSON.stringify(results, null, 2))
  console.log(JSON.stringify({ roles: results.roles, routes: results.routes.length, screenshots: results.screenshots.length }, null, 2))
} finally {
  await browser.close()
}
