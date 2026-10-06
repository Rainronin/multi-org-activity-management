// S6 全前端回归：所有角色 × 全部可达路由，核对可进入、单一 h1、无整页溢出、无运行错误。
// 默认模式只读；矩阵模式截获全部API，POST等写入仅为响应替身，不落真实业务数据、不调用模型。
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

if (process.argv.includes('--state-matrix')) {
  try { await auditStateMatrix() } finally { await browser.close() }
  process.exit(0)
}
if (process.argv.includes('--form-matrix')) {
  try { await auditFormMatrix() } finally { await browser.close() }
  process.exit(0)
}
if (process.argv.includes('--recovery-matrix')) {
  try { await auditRecoveryMatrix() } finally { await browser.close() }
  process.exit(0)
}

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
  const style = getComputedStyle(document.documentElement)
  const probe = document.createElement('span')
  probe.style.fontFamily = 'var(--ui-font-family-body)'
  document.body.append(probe)
  const tokenFont = getComputedStyle(probe).fontFamily
  probe.remove()
  return {
    h1: [...document.querySelectorAll('h1')].map((e) => e.textContent.trim()),
    h2Count: document.querySelectorAll('h2').length,
    overflow: document.documentElement.scrollWidth - window.innerWidth,
    emptyMain: main ? main.innerText.trim().length === 0 : true,
    navVisible: Boolean(document.querySelector('.unified-sidebar')),
    redirect: location.pathname,
    theme: { bodyFontMatches: getComputedStyle(document.body).fontFamily === tokenFont,
      elementPrimaryMatches: style.getPropertyValue('--el-color-primary').trim() === style.getPropertyValue('--ui-color-action').trim(),
      elementFontMatches: style.getPropertyValue('--el-font-family').trim() === style.getPropertyValue('--ui-font-family-body').trim() },
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
      assert.ok(Object.values(state.theme).every(Boolean), `${role} ${route.name} token主题未生效`)
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
      results.routes.push({ role, route: route.name, path: url, h1: state.h1[0], overflow: state.overflow, theme: state.theme })
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

// 全站状态矩阵复用本脚本与已安装的 Playwright；全部 API 在浏览器中替换，不写数据库。
async function auditStateMatrix() {
  const target = new URL('../验证截图/全站状态矩阵/', import.meta.url)
  await mkdir(target, { recursive: true })
  const checks = [], matrix = [], errors = []
  const members = ['OWNER', 'ACTIVITY_OWNER', 'MEMBER']
  const publicRoles = ['ANON', 'OUTSIDER', ...members]
  const rows = [
    { name: '账号组织目录', path: '/', scope: '.account-page__card', nth: 0, endpoint: '/api/me/organizations', roles: [...members, 'OUTSIDER'], empty: '尚未创建或加入', skeleton: '.el-skeleton' },
    { name: '账号本人报名', path: '/', scope: '.account-page__card', nth: 1, endpoint: '/api/me/registrations', roles: [...members, 'OUTSIDER'], empty: '暂无报名记录', skeleton: '.el-skeleton' },
    { name: '站内提示', path: '/', scope: '.notifications', endpoint: '/api/me/notifications', roles: [...members, 'OUTSIDER'], empty: '暂无提示', skeleton: '.notifications__note' },
    { name: '组织权限壳', path: '/org/1', scope: '.org-main', endpoint: '/api/organizations/1', roles: members, skeleton: '.page-note', noEmpty: true },
    { name: '组织工作台', path: '/org/1', scope: '.workbench', endpoint: '/api/organizations/1/workbench', roles: members, empty: '暂无', skeleton: '.el-skeleton' },
    { name: '活动列表', path: '/org/1/activities', scope: '.list-panel', endpoint: '/api/organizations/1/activities', roles: members, empty: '暂无|尚未创建', skeleton: '.el-skeleton' },
    { name: '我的任务', path: '/org/1/my-tasks', scope: '.list-panel', endpoint: '/api/organizations/1/my-tasks', roles: members, empty: '暂无|没有', skeleton: '.el-skeleton' },
    { name: '成员列表', path: '/org/1/members', scope: '.list-panel', endpoint: '/api/organizations/1/members', roles: members, empty: '暂无|没有', skeleton: '.el-skeleton' },
    { name: '邀请列表', path: '/org/1/members', scope: '[data-invitations-panel]', endpoint: '/api/organizations/1/invitations', roles: ['OWNER'], empty: '暂无有效邀请', skeleton: '[aria-live]' },
    { name: '模型配置', path: '/org/1/settings', scope: '.ai-config', endpoint: '/api/organizations/1/ai/config', roles: members, skeleton: '.el-skeleton', noEmpty: true },
    { name: '内部活动详情', path: '/org/1/activities/3', scope: '.activity-detail', endpoint: '/api/organizations/1/activities/3', roles: members, skeleton: '.el-skeleton', noEmpty: true },
    { name: '任务物资资料', path: '/org/1/activities/3?tab=tasks', scope: '.p3-sections', endpoint: '/api/organizations/1/activities/3/tasks', roles: members, empty: '暂无任务', skeleton: '.el-skeleton' },
    { name: '经费', path: '/org/1/activities/3?tab=expense', scope: '.expense-section', endpoint: '/api/organizations/1/activities/3/expenses', roles: ['OWNER', 'ACTIVITY_OWNER'], empty: '暂无支出', skeleton: '.el-skeleton' },
    { name: '报名', path: '/org/1/activities/3?tab=registration', scope: '.reg-panel', endpoint: '/api/me/registrations', roles: members, empty: '尚未报名', skeleton: '.el-skeleton' },
    { name: '执行记录反馈总结', path: '/org/1/activities/3?tab=lifecycle', scope: '.lifecycle-section', endpoint: '/api/organizations/1/activities/3/execution-records', roles: members, empty: '暂无执行记录', skeleton: '[aria-live]' },
    { name: '确认历史', path: '/org/1/activities/3?tab=lifecycle', scope: '.lifecycle-section .el-collapse-item__content', endpoint: '/api/organizations/1/activities/3/confirmations', roles: members, openText: '查看文稿确认历史', empty: '暂无可读取的确认快照', skeleton: '[aria-live]' },
    { name: 'AI方案输入范围', path: '/org/1/activities/3?tab=proposal', scope: '.ai-draft', endpoint: '/api/organizations/1/activities/3/ai/input', roles: ['OWNER', 'ACTIVITY_OWNER'], status: 'DRAFT', skeleton: '.ai-draft__hint', noEmpty: true },
    { name: 'AI总结输入范围', path: '/org/1/activities/3?tab=lifecycle', scope: '.ai-draft', endpoint: '/api/organizations/1/activities/3/ai/input', roles: ['OWNER', 'ACTIVITY_OWNER'], status: 'REVIEWING', skeleton: '.ai-draft__hint', noEmpty: true },
    { name: '任务资料弹层', path: '/org/1/activities/3?tab=tasks', dialog: '任务资料：已有任务', endpoint: '/api/organizations/1/activities/3/tasks/12/attachments', roles: members, open: '资料', empty: '该任务暂无资料', skeleton: '.el-skeleton' },
    { name: '图片预览', path: '/org/1/activities/3?tab=tasks', dialog: '矩阵图片.png', endpoint: '/api/organizations/1/activities/3/attachments/14/download', roles: members, open: '预览', noEmpty: true, skeleton: '.el-skeleton', image: true },
    { name: '凭证弹层', path: '/org/1/activities/3?tab=expense', dialog: '支出凭证', endpoint: '/api/organizations/1/activities/3/expenses/11/attachments', roles: ['OWNER', 'ACTIVITY_OWNER'], open: '查看凭证', empty: '暂无凭证', skeleton: '.el-skeleton' },
    { name: '公开活动列表', path: '/public/activities', scope: '.public-list', endpoint: '/api/public/activities', roles: publicRoles, empty: '暂无公开活动', skeleton: '.el-skeleton' },
    { name: '公开活动详情', path: '/public/activities/3', scope: '.public-detail', endpoint: '/api/public/activities/3', roles: publicRoles, skeleton: '.el-skeleton', noEmpty: true },
    { name: '公开本人报名', path: '/public/activities/3', scope: '.public-apply', endpoint: '/api/me/registrations', roles: publicRoles.filter(role => role !== 'ANON'), skeleton: '[role=status]', empty: '我要报名' },
    { name: '公开本人反馈', path: '/public/activities/3', scope: '.public-feedback', endpoint: '/api/public/activities/3/feedback', roles: publicRoles.filter(role => role !== 'ANON'), skeleton: '[role=status]', empty: '当前 0 份反馈' },
  ]
  const only = process.env.MATRIX_ONLY
  const check = (row, role, state, name, passed, detail = '') => checks.push({ section: row.name, role, state, name, passed, ...(passed ? {} : { detail }) })
  for (const row of rows.filter(item => !only || item.name === only)) {
    for (const role of row.roles) {
      const context = await browser.newContext({ baseURL: base, reducedMotion: 'reduce', viewport: { width: 1440, height: 1000 } })
      const page = await context.newPage()
      page.setDefaultTimeout(4000)
      page.on('pageerror', error => errors.push({ section: row.name, role, message: error.message }))
      let mode = 'slow', release
      const gate = new Promise(resolve => { release = resolve })
      await context.route('**/api/**', async route => {
        const path = new URL(route.request().url()).pathname
        assert.equal(route.request().method(), 'GET', `状态读取禁止写请求：${path}`)
        if (path === row.endpoint) {
          if (mode === 'slow') await gate
          if ([503, 403, 404].includes(mode)) {
            await route.fulfill({ status: mode, json: { status: mode, code: `MATRIX_${mode}`, detail: mode === 403 ? '无权访问此内容。' : mode === 404 ? '内容不存在或已不可访问。' : '读取暂时失败，请重试。' } })
            return
          }
        }
        if (path === '/api/auth/me' && role === 'ANON') {
          await route.fulfill({ status: 401, json: { status: 401, code: 'UNAUTHORIZED' } })
          return
        }
        const data = stateFixture(path, role, row.name === '成员列表')
        if (row.status && path.endsWith('/activities/3')) data.status = row.status
        if (row.name === '公开本人反馈' && path === row.endpoint) data.canSubmit = true
        if (row.open && path.endsWith('/tasks')) data.items = [{ taskId: '12', activityId: '3', title: '已有任务', assigneeMemberId: '1', assigneeUsername: '矩阵账号', priority: 'MEDIUM', status: 'PENDING', version: 1 }]
        if (row.image && path.endsWith('/attachments')) data.items = [{ attachmentId: '14', activityId: '3', taskId: null, originalName: '矩阵图片.png', contentType: 'image/png', sizeBytes: 68, uploaderUsername: '矩阵账号', purpose: 'ACTIVITY_DOC', createdAt: '2026-10-04' }]
        if (row.name === '凭证弹层' && path.endsWith('/expenses')) { data.items = [{ expenseId: '11', purpose: '已有支出', amount: '12.00', incurredOn: '2026-10-04', category: 'OTHER', status: 'DRAFT', voucherCount: '1', version: 1 }]; data.total = 1 }
        if (row.image && path === row.endpoint) await route.fulfill({ contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF7sAAAAASUVORK5CYII=', 'base64') })
        else await route.fulfill({ json: data })
      })
      const scope = row.dialog ? page.getByRole('dialog', { name: row.dialog, exact: true }) : page.locator(row.scope).nth(row.nth ?? 0)
      const open = async () => {
        if (row.open) await page.getByRole('button', { name: row.open, exact: true }).first().click()
        if (row.openText) await page.getByText(row.openText, { exact: true }).click()
      }
      try {
        await page.goto(row.path, { waitUntil: 'domcontentloaded' })
        await open()
        await scope.waitFor()
        await page.waitForTimeout(350)
        let text = await scope.innerText()
        check(row, role, 'loading', '加载提示', await scope.locator(row.skeleton).count() > 0 || /正在加载|加载.+中/.test(text), text)
        check(row, role, 'loading', '不显示失败或假空态', await scope.getByRole('alert').count() === 0 && (!row.empty || !new RegExp(row.empty).test(text)), text)
        mode = 503
        release()
        await scope.getByRole('alert').first().waitFor()
        await page.waitForTimeout(180)
        text = await scope.innerText()
        check(row, role, '503', '失败结束加载', await scope.locator('.el-skeleton').count() === 0 && !/正在加载|正在读取/.test(text), text)
        check(row, role, '503', '重试入口', await scope.getByRole('button', { name: /重试|重新加载|重新读取/ }).count() > 0, text)
        check(row, role, '503', '不冒充空数据', !row.empty || !new RegExp(row.empty).test(text), text)
        mode = 'empty'
        const retry = scope.getByRole('button', { name: /重试|重新加载|重新读取/ }).first()
        if (await retry.count()) await retry.click()
        else await page.reload({ waitUntil: 'domcontentloaded' })
        await page.waitForLoadState('networkidle')
        text = await scope.innerText()
        check(row, role, 'success', '重试恢复且无错误', await scope.getByRole('alert').count() === 0, text)
        if (row.empty) check(row, role, 'empty', '成功后真实空态', new RegExp(row.empty).test(text), text)
        matrix.push({ section: row.name, role, empty: row.noEmpty ? 'N/A：单个资源或配置，404代表不存在；配置未设置为有效空配置' : '已测', submit: 'N/A：只读区段；写表单独立测试' })
        for (const status of [403, 404]) {
          mode = status
          await page.reload({ waitUntil: 'networkidle' })
          await open()
          await scope.getByRole('alert').first().waitFor()
          text = await scope.innerText()
          check(row, role, status, '权限或缺失状态明确', status === 403 ? /无权|权限|不可访问/.test(text) : /不存在|不可访问|未公开/.test(text), text)
          check(row, role, status, '没有受限数据或骨架', await scope.locator('.el-skeleton, .el-table__body tr, .account-page__orgs, .detail-tabs, .public-facts, img, iframe').count() === 0, text)
          check(row, role, status, '保留返回导航', await page.locator('a[href="/"], a[href="/public/activities"], .account-page').count() > 0, text)
        }
      } catch (error) {
        check(row, role, mode, '场景可执行', false, `${error.message}; ${(await page.locator('body').innerText()).slice(0, 1000)}`)
      } finally {
        release()
        await context.close()
      }
    }
    console.log(`${row.name}：${checks.filter(item => item.section === row.name && item.passed).length}/${checks.filter(item => item.section === row.name).length}`)
  }
  const result = { status: checks.every(item => item.passed) && errors.length === 0 ? 'PASS' : 'FAIL', checks, matrix, errors,
    scope: '正式Vue页面；全部GET响应替身，403/404为浏览器受控故障，未写数据库。加载/空/失败互斥与角色页面均逐条断言；不是自然网络或后端授权证明。' }
  await writeFile(new URL('读取结果.json', target), JSON.stringify(result, null, 2))
  console.log(JSON.stringify({ status: result.status, passed: checks.filter(item => item.passed).length, total: checks.length, failures: checks.filter(item => !item.passed) }, null, 2))
  assert.equal(result.status, 'PASS', '全站读取状态矩阵有未通过项')
}

function stateFixture(path, role, emptyMembers = false) {
  const owner = role === 'OWNER'
  const org = { id: '1', name: '矩阵组织', role: owner ? 'OWNER' : 'MEMBER', memberCount: 2, description: '', createdAt: '2026-10-04', joinedAt: '2026-10-04' }
  const activity = { id: '3', name: '矩阵活动', status: 'APPROVED', version: 1, ownerMemberId: role === 'MEMBER' ? '2' : '1', ownerUsername: role === 'MEMBER' ? '其他成员' : '矩阵账号', description: '', proposal: '原方案', summary: '原总结', budget: '100.00', registrationScope: 'PUBLIC', registrationOpen: true, capacity: 10, location: '活动室', startTime: '2030-10-05T10:00:00+08:00', endTime: '2030-10-05T12:00:00+08:00', organizationName: '矩阵组织' }
  if (path === '/api/auth/me') return { id: '1', username: '矩阵账号' }
  if (path === '/api/auth/csrf') return { token: 'fixture-token', headerName: 'X-CSRF-TOKEN' }
  if (path === '/api/me/organizations') return { items: [] }
  if (path === '/api/organizations/1') return org
  if (path.endsWith('/members')) return { items: emptyMembers ? [] : [{ memberId: '1', accountId: '1', username: '矩阵账号', role: owner ? 'OWNER' : 'MEMBER', joinedAt: '2026-10-04' }], total: emptyMembers ? 0 : 1, page: 1, pageSize: 20 }
  if (path.endsWith('/activities/3')) return activity
  if (path.endsWith('/ai/config')) return { viewerRole: owner ? 'OWNER' : 'MEMBER', available: false, availabilityHint: '尚未配置', configState: 'UNCONFIGURED', protocol: 'OPENAI_CHAT_COMPLETIONS', endpoint: null, model: null, authScheme: 'BEARER', maxOutputTokens: 1024, outputLimitField: 'MAX_TOKENS', timeoutSeconds: 20, hasApiKey: false, enabled: false, version: 0, connectionRevision: 0, testedRevision: null, testedForCurrentRevision: false, testedAt: null, testedBy: null }
  if (path.endsWith('/workbench')) return { role: owner ? 'OWNER' : role === 'ACTIVITY_OWNER' ? 'ACTIVITY_OWNER' : 'MEMBER', cards: [], todos: [], myCards: [] }
  if (path.endsWith('/expenses/summary')) return { budget: '100.00', pendingAmount: '0.00', approvedAmount: '0.00', balance: '100.00', total: 0 }
  if (path.endsWith('/feedback')) return { count: 0, averageRating: null, items: [], mineSubmitted: false, myVersion: null, canSubmit: false, submitBlockedReason: '尚未进入复盘' }
  if (path.endsWith('/confirmations')) return { items: [], total: 0, page: 1, pageSize: 20 }
  return { items: [], total: 0, unread: 0, page: 1, pageSize: 20 }
}

async function auditFormMatrix() {
  const target = new URL('../验证截图/全站状态矩阵/', import.meta.url)
  await mkdir(target, { recursive: true })
  const checks = [], errors = []
  const rows = [
    { name: '创建组织', path: '/', open: '创建组织', dialog: '创建组织', fields: [['组织名称', '失败后保留组织'], ['组织简介', '未保存简介']], submit: '创建' },
    { name: '邀请码加入', path: '/', open: '加入组织', dialog: '使用邀请码加入组织', fields: [['邀请码', 'ABCD-EFGH-IJKL-MNOP']], submit: '加入组织' },
    { name: '创建邀请', path: '/org/1/members', open: '创建邀请', dialog: '创建邀请', fields: [['有效期（天）', '7'], ['最大使用次数', '8']], submit: '生成邀请码' },
    { name: '创建活动', path: '/org/1/activities', open: '创建活动', dialog: '创建活动草稿', fields: [['活动主题', '失败活动草稿'], ['活动简介', '未保存活动简介']], select: '活动负责人', submit: '保存草稿', status: 'DRAFT' },
    { name: '编辑活动', path: '/org/1/activities/3', open: '编辑信息', dialog: '编辑活动信息', fields: [['活动主题', '未保存活动标题'], ['活动简介', '未保存介绍']], submit: '保存', status: 'DRAFT' },
    { name: '驳回活动', path: '/org/1/activities/3?tab=proposal', open: '驳回', dialog: '驳回活动', fields: [['驳回原因', '未保存驳回原因']], submit: '确认驳回', status: 'PENDING_APPROVAL' },
    { name: '新建任务', path: '/org/1/activities/3?tab=tasks', open: '新建任务', dialog: '新建任务', fields: [['任务标题', '未保存任务'], ['说明', '未保存交付说明']], select: '任务负责人', submit: '创建' },
    { name: '编辑任务', path: '/org/1/activities/3?tab=tasks', open: '编辑/改派', dialog: '编辑任务', fields: [['任务标题', '改派前任务草稿']], submit: '保存' },
    { name: '添加物资', path: '/org/1/activities/3?tab=tasks', open: '添加物资', dialog: '添加物资', fields: [['物资名称', '未保存物资'], ['备注', '未保存备注']], submit: '添加' },
    { name: '编辑物资', path: '/org/1/activities/3?tab=tasks', open: '编辑', openScope: '.p3-block:nth-of-type(2)', dialog: '编辑物资', fields: [['物资名称', '改派前物资草稿']], submit: '保存' },
    { name: '登记支出', path: '/org/1/activities/3?tab=expense', open: '登记支出', dialog: '登记支出', fields: [['支出用途', '未保存支出'], ['金额（元）', '12'], ['无票说明', '未保存票据说明']], submit: '保存' },
    { name: '编辑支出', path: '/org/1/activities/3?tab=expense', open: '编辑', dialog: '编辑支出', fields: [['支出用途', '未保存支出编辑']], submit: '保存' },
    { name: '驳回支出', path: '/org/1/activities/3?tab=expense', open: '驳回', dialog: '驳回支出', placeholder: '请说明驳回原因', value: '未保存费用驳回理由', submit: '驳回', expenseStatus: 'PENDING' },
    { name: '超支说明', path: '/org/1/activities/3?tab=expense', open: '确认', dialog: '超出预算，需要说明', placeholder: '请填写超支说明（必填）', value: '未保存超支理由', submit: '确认支出', expenseStatus: 'PENDING', overBudget: true },
    { name: '取消活动理由', path: '/org/1/activities/3?tab=lifecycle', open: '取消活动', dialog: '取消活动', placeholder: '取消原因（必填）', value: '保留取消理由', submit: '确认取消活动' },
    { name: '重开活动理由', path: '/org/1/activities/3?tab=lifecycle', open: '重新打开', dialog: '重新打开活动', placeholder: '重新打开理由（必填）', value: '保留重开理由', submit: '重新打开', status: 'ARCHIVED' },
    { name: '管理取消报名理由', path: '/org/1/activities/3?tab=registration', open: '管理取消', dialog: '管理取消报名', placeholder: '例如：报名者请假', value: '保留报名取消理由', submit: '取消报名' },
    { name: '作废支出理由', path: '/org/1/activities/3?tab=expense', open: '作废', dialog: '作废支出', placeholder: '作废原因', value: '保留作废理由', submit: '作废', expenseStatus: 'APPROVED' },
    { name: '内部报名输入', path: '/org/1/activities/3?tab=registration', open: '我要报名', dialog: '报名', fields: [['展示姓名', '失败后保留姓名'], ['备注', '失败后保留报名备注']], submit: '提交', extra: true },
    { name: '报名驳回输入', path: '/org/1/activities/3?tab=registration', open: '驳回', dialog: '驳回报名', fields: [['驳回原因', '失败后保留驳回理由']], submit: '确认驳回', registrationStatus: 'PENDING', extra: true },
    { name: '公开报名输入', path: '/public/activities/3', open: '我要报名', dialog: '报名', fields: [['展示姓名', '失败后保留外部姓名'], ['备注', '失败后保留外部备注']], submit: '提交', extra: true },
    { name: '保存方案', path: '/org/1/activities/3?tab=proposal', inline: '.activity-detail', inputs: [['.proposal-section textarea', '未保存方案草稿']], submit: '保存方案', status: 'DRAFT' },
    { name: '保存总结', path: '/org/1/activities/3?tab=lifecycle', inline: '.lifecycle-section', inputs: [['textarea[placeholder^="目标、执行概况"]', '未保存总结草稿']], submit: '保存总结', status: 'REVIEWING' },
    { name: '追加执行记录', path: '/org/1/activities/3?tab=lifecycle', inline: '.lifecycle-section', inputs: [['input[placeholder^="进展、问题"]', '未确认执行记录内容']], submit: '记录', status: 'EXECUTING' },
    { name: '内部反馈', path: '/org/1/activities/3?tab=lifecycle', inline: '.lifecycle-section', inputs: [['.lifecycle-feedback-form textarea', '未保存内部反馈']], submit: '提交反馈', status: 'REVIEWING', rating: '.lifecycle-feedback-form .el-rate__item' },
    { name: '公开反馈', path: '/public/activities/3', inline: '.public-feedback', inputs: [['textarea', '未保存公开反馈']], submit: '提交反馈', status: 'REVIEWING', rating: '.el-rate__item' },
    { name: 'AI方案条件', path: '/org/1/activities/3?tab=proposal', inline: '.ai-draft', inputs: [['input[data-ai-field="topic"]', '未保存AI主题'], ['input[data-ai-field="goal"]', '未保存AI目标']], submit: '生成草稿', status: 'DRAFT', failure: '生成结果尚未确认' },
  ]
  const check = (row, name, passed, detail = '') => checks.push({ section: row.name, name, passed, ...(passed ? {} : { detail }) })
  const inlineOnly = process.argv.includes('--inline-only')
  const extraOnly = process.argv.includes('--extra-only')
  const role = process.env.MATRIX_ROLE ?? 'OWNER'
  const managerForms = new Set(['编辑活动', '新建任务', '编辑任务', '添加物资', '编辑物资', '登记支出', '编辑支出', '管理取消报名理由', '报名驳回输入', '保存方案', '保存总结', '追加执行记录', 'AI方案条件'])
  const memberForms = new Set(['内部报名输入', '内部反馈'])
  const publicForms = new Set(['公开报名输入', '公开反馈'])
  const allowed = row => role === 'OWNER' || publicForms.has(row.name) || role === 'ACTIVITY_OWNER' && (managerForms.has(row.name) || memberForms.has(row.name)) || role === 'MEMBER' && memberForms.has(row.name)
  for (const row of rows.filter(item => allowed(item) && (!inlineOnly || item.inline) && (!extraOnly || item.extra) && (!process.env.MATRIX_ONLY || item.name === process.env.MATRIX_ONLY))) {
    const context = await browser.newContext({ baseURL: base, reducedMotion: 'reduce', viewport: { width: 1440, height: 1000 } })
    const page = await context.newPage()
    page.setDefaultTimeout(3500)
    page.on('pageerror', error => errors.push({ section: row.name, message: error.message }))
    let release, started, writes = 0, blocked = true
    const gate = new Promise(resolve => { release = resolve })
    const wrote = new Promise(resolve => { started = resolve })
    await context.route('**/api/**', async route => {
      const path = new URL(route.request().url()).pathname
      if (route.request().method() !== 'GET') {
        if (row.overBudget && !route.request().postDataJSON().overBudgetNote) {
          await route.fulfill({ status: 409, json: { status: 409, code: 'EXPENSE_OVER_BUDGET', detail: '确认后将超出预算。' } })
          return
        }
        writes++
        started()
        if (blocked) await gate
        await route.fulfill({ status: 503, json: { status: 503, code: 'MATRIX_FAILED', detail: '本次提交失败，请核对后重试。' } })
        return
      }
      const data = stateFixture(path, role)
      if (path.endsWith('/activities/3')) Object.assign(data, { status: row.status ?? 'APPROVED' })
      if (row.inline && path.endsWith('/feedback')) Object.assign(data, { canSubmit: true, submitBlockedReason: null })
      if (row.inline && path.endsWith('/ai/input')) Object.assign(data, { available: true, availabilityHint: '模型可用', sourceHash: 'fixture', scope: [], missing: [] })
      if (path.endsWith('/tasks')) data.items = [{ taskId: '12', activityId: '3', title: '已有任务', description: '', assigneeMemberId: '1', assigneeUsername: '矩阵账号', priority: 'MEDIUM', status: 'PENDING', version: 1 }]
      if (path.endsWith('/materials')) data.items = [{ materialId: '13', activityId: '3', name: '已有物资', quantity: 1, unit: '个', note: '', assigneeMemberId: '1', assigneeUsername: '矩阵账号', status: 'PENDING', version: 1 }]
      if (path.endsWith('/expenses')) { data.items = [{ expenseId: '11', activityId: '3', purpose: '已有支出', amount: '12.00', incurredOn: '2026-10-04', category: 'OTHER', noReceiptNote: '无票说明', status: row.expenseStatus ?? 'DRAFT', voucherCount: '0', version: 1 }]; data.total = 1 }
      if (path.endsWith('/activities/3/registrations')) { data.items = [{ registrationId: '15', activityId: '3', accountId: '2', displayName: '报名成员', accountUsername: '其他成员', status: row.registrationStatus ?? 'APPROVED', version: 1 }]; data.total = 1 }
      await route.fulfill({ json: data })
    })
    try {
      await page.goto(row.path, { waitUntil: 'networkidle' })
      const openScope = row.openScope ? page.locator(row.openScope) : page
      if (row.open) await openScope.getByRole('button', { name: row.open, exact: true }).first().click()
      const dialog = row.inline ? page.locator(row.inline) : page.getByRole('dialog', { name: row.dialog, exact: true })
      await dialog.waitFor()
      for (const [label, value] of row.fields ?? []) await dialog.locator('.el-form-item').filter({ hasText: label }).locator('input, textarea').first().fill(value)
      if (row.select) {
        await dialog.locator('.el-form-item').filter({ hasText: row.select }).locator('.el-select').click()
        await page.getByRole('option', { name: '矩阵账号', exact: true }).click()
      }
      if (row.placeholder) await dialog.getByPlaceholder(row.placeholder).fill(row.value)
      for (const [selector, value] of row.inputs ?? []) await dialog.locator(selector).fill(value)
      if (row.rating) await dialog.locator(row.rating).nth(3).click()
      const inputs = () => dialog.locator('input,textarea').evaluateAll(nodes => nodes.map(node => node.value))
      const before = await inputs()
      const submit = row.inline ? dialog.getByRole('button', { name: row.submit, exact: true }) : dialog.locator('.el-dialog__footer button, .el-message-box__btns button').last()
      const submitElement = await submit.elementHandle()
      await submit.click()
      await Promise.race([wrote, new Promise((_, reject) => setTimeout(() => reject(new Error('提交未触发预期写请求')), 2500))])
      check(row, '提交等待中仍显示输入', await dialog.isVisible())
      check(row, '按钮显示等待并禁止重复提交', await submitElement.isDisabled())
      blocked = false
      release()
      await page.waitForLoadState('networkidle')
      await dialog.getByText(row.failure ?? '本次提交失败', { exact: false }).first().waitFor()
      check(row, '失败保留弹层及全部输入', await dialog.isVisible() && JSON.stringify(await inputs()) === JSON.stringify(before))
      check(row, '失败就近显示原因', await dialog.isVisible() && (await dialog.innerText()).includes(row.failure ?? '本次提交失败'))
      check(row, '只发出一次写请求', writes === 1)
      await page.setViewportSize({ width: 390, height: 844 })
      await page.waitForTimeout(80)
      check(row, '390px失败态无横向溢出', await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      if (await dialog.isVisible()) await page.screenshot({ path: fileURLToPath(new URL(`${row.name}-${role}-失败390.png`, target)) })
    } catch (error) {
      check(row, '场景可执行', false, `${error.message}; ${(await page.locator('body').innerText()).slice(0, 1200)}`)
    } finally {
      release()
      await context.close()
    }
    console.log(`${row.name}：${checks.filter(item => item.section === row.name && item.passed).length}/${checks.filter(item => item.section === row.name).length}`)
  }
  const result = { status: checks.every(item => item.passed) && !errors.length ? 'PASS' : 'FAIL', role, checks, errors,
    scope: '正式Vue页面与API替身；每个表单实际输入/提交、延迟503、输入保留与390px可用性，不是自然网络或后端写入证明。' }
  const resultName = extraOnly || process.env.MATRIX_ROLE ? `表单-${role}${extraOnly ? '-补充' : ''}.json` : inlineOnly ? '内嵌表单结果.json' : '表单结果.json'
  await writeFile(new URL(resultName, target), JSON.stringify(result, null, 2))
  console.log(JSON.stringify({ status: result.status, passed: checks.filter(item => item.passed).length, total: checks.length, failures: checks.filter(item => !item.passed) }, null, 2))
  assert.equal(result.status, 'PASS', '表单状态矩阵有未通过项')
}

// 恢复合同仍在同一正式页面测试；全部请求替身，验证原键与原版本，不写库。
async function auditRecoveryMatrix() {
  const target = new URL('../验证截图/全站状态矩阵/', import.meta.url)
  await mkdir(target, { recursive: true })
  const checks = [], errors = []
  async function scenario(name, handler, run) {
    if (process.env.MATRIX_ONLY && process.env.MATRIX_ONLY !== name) return
    const context = await browser.newContext({ baseURL: base, reducedMotion: 'reduce', viewport: { width: 1440, height: 1000 } })
    const page = await context.newPage()
    page.setDefaultTimeout(4500)
    page.on('pageerror', error => errors.push({ section: name, message: error.message }))
    const requests = []
    const check = (label, passed, detail) => checks.push({ section: name, name: label, passed, ...(passed || !detail ? {} : { detail }) })
    await context.route('**/api/**', async route => {
      const request = route.request(), url = new URL(request.url())
      const record = { path: url.pathname, method: request.method(), key: request.headers()['idempotency-key'], queryKey: url.searchParams.get('requestKey'), body: request.postData() }
      requests.push(record)
      if (await handler(route, record)) return
      assert.equal(request.method(), 'GET', `未预设写请求：${record.path}`)
      const data = stateFixture(record.path, 'OWNER')
      if (record.path.endsWith('/tasks')) data.items = [{ taskId: '12', activityId: '3', title: '已有任务', assigneeMemberId: '1', assigneeUsername: '矩阵账号', priority: 'MEDIUM', status: 'PENDING', version: 1 }]
      if (record.path.endsWith('/expenses')) { data.items = [{ expenseId: '11', activityId: '3', purpose: '已有支出', amount: '12.00', incurredOn: '2026-10-04', category: 'OTHER', status: 'DRAFT', voucherCount: '0', version: 1 }]; data.total = 1 }
      await route.fulfill({ json: data })
    })
    try { await run(page, requests, check) }
    catch (error) { checks.push({ section: name, name: '场景可执行', passed: false, detail: `${error.message}; ${(await page.locator('body').innerText()).slice(0, 1400)}` }) }
    finally { await context.close() }
    console.log(`${name}：${checks.filter(item => item.section === name && item.passed).length}/${checks.filter(item => item.section === name).length}`)
  }
  for (const kind of ['活动', '任务', '凭证']) {
    let mode = 404
    await scenario(`${kind}附件原键核对`, async (route, record) => {
      if (record.method === 'POST' && record.path.endsWith('/attachments')) {
        await route.fulfill({ status: 503, json: { status: 503, code: 'UNAVAILABLE', detail: '上传结果未知。' } }); return true
      }
      if (record.path.endsWith('/attachments/by-request-key')) {
        await route.fulfill(mode === 200
          ? { json: { attachmentId: '20', activityId: '3', taskId: kind === '任务' ? '12' : null, expenseId: kind === '凭证' ? '11' : null, originalName: '原文件.txt', contentType: 'text/plain', sizeBytes: 5 } }
          : { status: mode, json: { status: mode, code: mode === 410 ? 'ATTACHMENT_DELETED' : 'NOT_FOUND', detail: mode === 410 ? '附件已删除。' : '尚未找到结果。' } })
        return true
      }
      return false
    }, async (page, requests, check) => {
      await page.goto(kind === '凭证' ? '/org/1/activities/3?tab=expense' : '/org/1/activities/3?tab=tasks', { waitUntil: 'networkidle' })
      let scope = page.locator('.p3-block').last()
      if (kind === '任务') { await page.getByRole('button', { name: '资料', exact: true }).click(); scope = page.getByRole('dialog', { name: '任务资料：已有任务' }) }
      if (kind === '凭证') { await page.getByRole('button', { name: '编辑', exact: true }).click(); scope = page.getByRole('dialog', { name: '编辑支出' }) }
      await scope.locator('input[type=file]').setInputFiles({ name: '原文件.txt', mimeType: 'text/plain', buffer: Buffer.from('draft') })
      const checkButton = scope.getByRole('button', { name: '核对上传结果', exact: true })
      await checkButton.waitFor()
      check('未知结果保留文件并冻结替换', await scope.locator('input[type=file]').isDisabled())
      await checkButton.click(); await page.waitForLoadState('networkidle')
      await checkButton.waitFor()
      check('GET404仍保留核对入口', await checkButton.count() === 1)
      const post = requests.filter(item => item.method === 'POST')
      const reads = requests.filter(item => item.path.endsWith('/attachments/by-request-key'))
      check('首次POST前已有原键', post.length === 1 && Boolean(post[0].key))
      check('核对只GET原键且不重传multipart', reads.length === 1 && reads[0].queryKey === post[0].key && requests.filter(item => item.method === 'POST').length === 1)
      mode = kind === '凭证' ? 410 : 200
      await checkButton.click(); await page.waitForLoadState('networkidle')
      await checkButton.waitFor({ state: 'hidden' })
      await scope.locator('input[type=file]:not(:disabled)').waitFor({ state: 'attached' })
      check(mode === 410 ? '已删除结果解除旧意图且不复活' : '已确认结果解除旧意图', await checkButton.count() === 0 && !await scope.locator('input[type=file]').isDisabled())
      check('所有核对沿用原键且POST仍仅一次', requests.filter(item => item.path.endsWith('/attachments/by-request-key')).every(item => item.method === 'GET' && item.queryKey === post[0].key) && requests.filter(item => item.method === 'POST').length === 1)
    })
  }
  for (const kind of ['活动', '支出']) {
    await scenario(`旧${kind}创建意图需人工核对`, async (route, record) => {
      if (record.method !== 'GET') {
        await route.fulfill({ status: 409, json: { status: 409, code: 'IDEMPOTENCY_RESULT_UNVERIFIABLE', detail: '旧请求无法证明原意图，请先核对列表，不要更换请求键创建。' } }); return true
      }
      return false
    }, async (page, requests, check) => {
      await page.goto(kind === '活动' ? '/org/1/activities' : '/org/1/activities/3?tab=expense', { waitUntil: 'networkidle' })
      await page.getByRole('button', { name: kind === '活动' ? '创建活动' : '登记支出', exact: true }).click()
      const dialog = page.getByRole('dialog', { name: kind === '活动' ? '创建活动草稿' : '登记支出' })
      await dialog.getByLabel(kind === '活动' ? '活动主题' : '支出用途', { exact: true }).fill('需要人工核对的旧意图')
      if (kind === '活动') { await dialog.locator('.el-form-item').filter({ hasText: '活动负责人' }).locator('.el-select').click(); await page.getByRole('option', { name: '矩阵账号', exact: true }).click() }
      else { await dialog.getByLabel('金额（元）', { exact: true }).fill('12'); await dialog.getByLabel('无票说明', { exact: true }).fill('旧说明') }
      const submit = dialog.locator('.el-dialog__footer button').last()
      await submit.click(); await dialog.getByRole('alert').waitFor(); await page.waitForLoadState('networkidle')
      check('显示人工核列表且不要换键创建', /核对.+列表|核对列表/.test(await dialog.innerText()) && /不要更换|勿换键/.test(await dialog.innerText()))
      check('旧意图冻结且停止无限POST核对', await submit.isDisabled() && requests.filter(item => item.method !== 'GET').length === 1)
      check('保持当前输入供人工核对', await dialog.getByLabel(kind === '活动' ? '活动主题' : '支出用途', { exact: true }).inputValue() === '需要人工核对的旧意图')
    })
  }
  for (const kind of ['活动', '任务', '凭证']) {
    let mode = 503, downloads = 0, gate, started
    const attachment = { attachmentId: '14', activityId: '3', taskId: kind === '任务' ? '12' : null, expenseId: kind === '凭证' ? '11' : null, originalName: '受控资料.txt', contentType: 'text/plain', sizeBytes: 5, uploaderUsername: '矩阵账号' }
    await scenario(`${kind}下载失败状态`, async (route, record) => {
      if (record.path.endsWith('/attachments/14/download')) {
        started?.()
        if (gate) await gate
        await route.fulfill(mode === 200 ? { contentType: 'text/plain', body: '已校验原件' } : { status: mode, json: { status: mode, code: `MATRIX_${mode}`, detail: mode === 403 ? '无权访问此内容。' : mode === 404 ? '内容不存在或已不可访问。' : '下载暂时失败，请重试。' } }); return true
      }
      if (record.path.endsWith('/attachments')) { await route.fulfill({ json: { items: [attachment] } }); return true }
      if (kind === '凭证' && record.path.endsWith('/expenses')) { await route.fulfill({ json: { items: [{ expenseId: '11', activityId: '3', purpose: '已有支出', amount: '12.00', incurredOn: '2026-10-04', category: 'OTHER', status: 'DRAFT', voucherCount: '1', version: 1 }], total: 1 } }); return true }
      return false
    }, async (page, requests, check) => {
      page.on('download', () => { downloads++ })
      await page.goto(kind === '凭证' ? '/org/1/activities/3?tab=expense' : '/org/1/activities/3?tab=tasks', { waitUntil: 'networkidle' })
      let scope = page.locator('.p3-sections')
      if (kind === '任务') { await page.getByRole('button', { name: '资料', exact: true }).click(); scope = page.getByRole('dialog', { name: '任务资料：已有任务' }) }
      if (kind === '凭证') { await page.getByRole('button', { name: '查看凭证', exact: true }).click(); scope = page.getByRole('dialog', { name: '支出凭证' }) }
      const button = kind === '凭证' ? scope.getByRole('button', { name: '受控资料.txt' }) : scope.getByRole('button', { name: '下载', exact: true }).first()
      for (const status of [503, 403, 404]) {
        mode = status
        let release
        const waiting = new Promise(resolve => { started = resolve })
        if (status === 503) gate = new Promise(resolve => { release = resolve })
        const response = page.waitForResponse(item => item.url().endsWith('/attachments/14/download'))
        await button.click()
        if (status === 503) {
          await Promise.race([waiting, new Promise((_, reject) => setTimeout(() => reject(new Error('下载未发起受控GET')), 4000))])
          check('下载加载中禁止重复点击', await button.isDisabled())
          release(); gate = null
        }
        await response
        await scope.getByRole('alert').first().waitFor()
        check(`${status}失败就近显示且不存成错误文件`, downloads === 0)
        check(`${status}仍保留原页面与资料`, new URL(page.url()).pathname.startsWith('/org/1/activities/3') && (await scope.innerText()).includes('受控资料.txt'))
        if (status === 403) await page.screenshot({ path: fileURLToPath(new URL(`${kind}下载403.png`, target)) })
      }
      mode = 200
      const download = page.waitForEvent('download')
      await button.click()
      check('成功只下载已校验的原件', (await download).suggestedFilename() === '受控资料.txt' && downloads === 1)
      check('全部为读取请求且没有写入', requests.every(item => item.method === 'GET'))
    })
  }
  let aiMode = 'UNKNOWN'
  await scenario('AI连接测试原键核对', async (route, record) => {
    if (record.path.endsWith('/ai/config')) { await route.fulfill({ json: configuredAiFixture() }); return true }
    if (record.path.endsWith('/ai/config/test') || record.path.endsWith('/ai/config/test/by-request-key')) {
      if (aiMode === 404) await route.fulfill({ status: 404, json: { status: 404, code: 'NOT_FOUND', detail: '尚未找到测试结果。' } })
      else await route.fulfill({ json: { status: aiMode, errorCode: aiMode === 'UNKNOWN' ? 'AI_TIMEOUT' : null, message: aiMode === 'FAILED' ? '明确测试失败' : '测试状态已记录', testedRevision: 7, requestId: 'matrix-test', testedAt: '2026-10-04' } })
      return true
    }
    return false
  }, async (page, requests, check) => {
    await page.goto('/org/1/settings', { waitUntil: 'networkidle' })
    const test = page.locator('[data-ai-action="test"]'), verify = page.locator('[data-ai-action="check-test"]')
    const model = page.locator('[data-ai-field="model"] input, input[data-ai-field="model"]')
    await test.click(); await verify.waitFor(); await page.waitForLoadState('networkidle')
    const original = requests.find(item => item.path.endsWith('/ai/config/test'))
    check('首个POST已带原键和已载版本', Boolean(original.key) && JSON.parse(original.body).version === 7)
    check('UNKNOWN冻结配置且提供核对', await model.isDisabled() && await verify.isEnabled())
    aiMode = 404; await verify.click(); await page.waitForLoadState('networkidle')
    check('核对404仍保留原意图和冻结', await verify.count() === 1 && await model.isDisabled())
    aiMode = 'RUNNING'; await verify.click(); await page.waitForLoadState('networkidle')
    check('RUNNING只保留核对', await verify.count() === 1 && await model.isDisabled())
    aiMode = 'FAILED'; await verify.click(); await page.waitForLoadState('networkidle')
    await verify.waitFor({ state: 'hidden' })
    await page.locator('[data-ai-field="model"] input:not(:disabled), input[data-ai-field="model"]:not(:disabled)').waitFor()
    check('明确FAILED恢复编辑且保留失败原因', !await model.isDisabled() && (await page.locator('.ai-config').innerText()).includes('明确测试失败'))
    await test.click()
    const confirm = page.getByRole('dialog', { name: '重新发起连接测试' })
    await confirm.waitFor(); await confirm.getByRole('button', { name: '保留上次结果' }).click()
    check('取消新意图不再次POST', requests.filter(item => item.path.endsWith('/ai/config/test')).length === 1)
    await test.click(); await confirm.getByRole('button', { name: '重新发起测试', exact: true }).click(); await page.waitForLoadState('networkidle')
    const posts = requests.filter(item => item.path.endsWith('/ai/config/test'))
    check('确认新意图才生成新键', posts.length === 2 && posts[1].key !== original.key && JSON.parse(posts[1].body).version === 7)
    check('UNKNOWN期间全部核对为GET原键', requests.filter(item => item.path.endsWith('/test/by-request-key')).every(item => item.method === 'GET' && item.queryKey === original.key))
  })
  for (const kind of ['内部', '公开']) {
    let currentVersion = 1, currentComment = '原反馈', postCount = 0
    await scenario(`${kind}反馈草稿版本`, async (route, record) => {
      if (record.path.endsWith('/activities/3')) { const detail = stateFixture(record.path, 'OWNER'); detail.status = 'REVIEWING'; await route.fulfill({ json: detail }); return true }
      if (record.path.endsWith('/feedback')) {
        if (record.method === 'POST') {
          postCount++
          if (postCount <= 2) await route.fulfill({ status: 409, json: { status: 409, code: 'EDIT_CONFLICT', detail: '反馈版本已变化，请核对。' } })
          else { currentVersion++; currentComment = JSON.parse(record.body).comment; await route.fulfill({ json: { feedbackId: '22', version: currentVersion, rating: 4, comment: currentComment } }) }
        } else await route.fulfill({ json: { count: 1, averageRating: '4.0', items: [], mineSubmitted: true, myRating: 4, myComment: currentComment, myVersion: currentVersion, canSubmit: true, submitBlockedReason: null } })
        return true
      }
      return false
    }, async (page, requests, check) => {
      await page.goto(kind === '内部' ? '/org/1/activities/3?tab=lifecycle' : '/public/activities/3', { waitUntil: 'networkidle' })
      const scope = page.locator(kind === '内部' ? '.lifecycle-feedback-form' : '[data-public-feedback]')
      const comment = scope.locator('textarea')
      await comment.fill('保留本地反馈草稿')
      currentVersion = 4; currentComment = '别人已保存版本4'
      if (kind === '内部') {
        await page.locator('.lifecycle-section').evaluate(element => {
          for (let component = element.__vueParentComponent; component; component = component.parent) {
            if (typeof component.exposed?.reload === 'function') { void component.exposed.reload(); return }
          }
          throw new Error('未找到已有 reload 暴露')
        })
        await page.waitForLoadState('networkidle')
        check('后台刷新保留dirty输入', await comment.inputValue() === '保留本地反馈草稿')
      }
      const submit = scope.getByRole('button', { name: '更新反馈', exact: true })
      await submit.click(); await scope.getByRole('button', { name: '核对最新反馈' }).waitFor()
      let posts = requests.filter(item => item.method === 'POST' && item.path.endsWith('/feedback'))
      check('冲突提交使用草稿加载时版本', JSON.parse(posts[0].body).version === 1)
      check('409保留评分和建议', await comment.inputValue() === '保留本地反馈草稿' && await scope.locator('.el-rate__item .is-active').count() > 0)
      await scope.getByRole('button', { name: '核对最新反馈' }).click()
      const confirm = page.getByRole('dialog', { name: '核对最新反馈' })
      await confirm.waitFor(); check('恢复先展示当前反馈供核对', (await confirm.innerText()).includes('别人已保存版本4'))
      await confirm.getByRole('button', { name: '继续核对' }).click(); await submit.click(); await page.waitForLoadState('networkidle')
      posts = requests.filter(item => item.method === 'POST' && item.path.endsWith('/feedback'))
      check('取消核对不会静默推进版本', JSON.parse(posts[1].body).version === 1)
      await scope.getByRole('button', { name: '核对最新反馈' }).click(); await confirm.getByRole('button', { name: '保留输入并使用此版本' }).click()
      check('确认基线仍保留当前输入', await comment.inputValue() === '保留本地反馈草稿')
      await submit.click(); await page.waitForLoadState('networkidle')
      posts = requests.filter(item => item.method === 'POST' && item.path.endsWith('/feedback'))
      check('确认后用版本4保存原草稿', JSON.parse(posts[2].body).version === 4 && JSON.parse(posts[2].body).comment === '保留本地反馈草稿')
      await comment.fill('第二次编辑'); await submit.click(); await page.waitForLoadState('networkidle')
      posts = requests.filter(item => item.method === 'POST' && item.path.endsWith('/feedback'))
      check('保存成功后推进至返回版本5', JSON.parse(posts[3].body).version === 5)
    })
  }
  for (const kind of ['内部再报名', '公开再报名', '本人取消', '批准报名', '驳回报名', '管理取消']) {
    const name = `报名版本：${kind}`
    let version = 3
    const registration = () => ({ registrationId: '15', activityId: '3', accountId: '1', displayName: '原报名姓名', note: '原备注', status: kind.includes('再报名') ? 'CANCELLED_SELF' : kind === '批准报名' || kind === '驳回报名' ? 'PENDING' : 'APPROVED', version })
    await scenario(name, async (route, record) => {
      if (record.path === '/api/me/registrations' || record.path.endsWith('/activities/3/registrations') && record.method === 'GET') { await route.fulfill({ json: { items: [registration()], total: 1 } }); return true }
      if (record.method === 'POST' && record.path.includes('/registrations')) { await route.fulfill({ status: 409, json: { status: 409, code: 'EDIT_CONFLICT', detail: '报名已变化，请重新核对。' } }); return true }
      return false
    }, async (page, requests, check) => {
      await page.goto(kind.startsWith('公开') ? '/public/activities/3' : '/org/1/activities/3?tab=registration', { waitUntil: 'networkidle' })
      const posted = page.waitForResponse(response => response.request().method() === 'POST' && response.url().includes('/registrations'))
      let input, dialog
      if (kind.includes('再报名')) {
        await page.getByRole('button', { name: '更新报名信息', exact: true }).click()
        dialog = page.getByRole('dialog', { name: '更新报名信息', exact: true })
        input = dialog.getByPlaceholder('报名姓名（1-64 字符）'); await input.fill('保留再次报名草稿'); version = 7
        await dialog.getByRole('button', { name: '提交', exact: true }).click()
      } else if (kind === '本人取消') {
        await page.locator('.reg-panel').getByRole('button', { name: '取消报名', exact: true }).first().click()
        dialog = page.getByRole('dialog', { name: '取消报名', exact: true }); await dialog.waitFor(); version = 7
        await dialog.getByRole('button', { name: '取消报名', exact: true }).click()
      } else if (kind === '批准报名') await page.locator('.reg-panel').getByRole('button', { name: '通过', exact: true }).click()
      else if (kind === '驳回报名') {
        await page.locator('.reg-panel').getByRole('button', { name: '驳回', exact: true }).click()
        dialog = page.getByRole('dialog', { name: '驳回报名' }); input = dialog.locator('textarea'); await input.fill('保留驳回理由'); version = 7
        await dialog.getByRole('button', { name: '确认驳回', exact: true }).click()
      } else {
        await page.getByRole('button', { name: '管理取消', exact: true }).click()
        dialog = page.getByRole('dialog', { name: '管理取消报名' }); input = dialog.getByPlaceholder('例如：报名者请假'); await input.fill('保留管理取消理由'); version = 7
        await dialog.getByRole('button', { name: '取消报名', exact: true }).click()
      }
      await posted
      await page.waitForLoadState('networkidle')
      const posts = requests.filter(item => item.method === 'POST')
      check('写请求携带加载或原确认时版本3', posts.length === 1 && JSON.parse(posts[0].body).version === 3)
      check('冲突显示明确失败', (await page.locator('body').innerText()).includes('报名已变化'))
      if (input) check('冲突保留弹层及当前输入', await dialog.isVisible() && (await input.inputValue()).startsWith('保留'))
    })
  }
  const result = { status: checks.every(item => item.passed) && errors.length === 0 ? 'PASS' : 'FAIL', checks, errors,
    scope: '正式 Vue 页面与 API 替身；原键 GET 核对、版本冲突恢复、当前输入保留。未写库、未真实上传、未调用模型。' }
  await writeFile(new URL('恢复结果.json', target), JSON.stringify(result, null, 2))
  console.log(JSON.stringify({ status: result.status, passed: checks.filter(item => item.passed).length, total: checks.length, failures: checks.filter(item => !item.passed) }, null, 2))
  assert.equal(result.status, 'PASS', '恢复合同矩阵有未通过项')
}

function configuredAiFixture() {
  return { viewerRole: 'OWNER', available: true, availabilityHint: '模型可用', configState: 'ENABLED', protocol: 'OPENAI_CHAT_COMPLETIONS', endpoint: 'https://example.com', model: 'matrix-model', authScheme: 'BEARER', maxOutputTokens: 1024, outputLimitField: 'MAX_TOKENS', timeoutSeconds: 20, hasApiKey: true, enabled: true, version: 7, connectionRevision: 7, testedRevision: 7, testedForCurrentRevision: true, testedAt: '2026-10-04', testedBy: '矩阵账号' }
}
