// 正式前端 + 真实测试后端：四会话走创建、审批、任务、手机报名与复盘。
// 仅账号和成员关系由 API 准备；不使用接口替身，不连接开发库。
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('必须确认后端连接 activity_test')
const base = 'http://127.0.0.1:5174'
const out = new URL('../验证截图/浏览器联调/', import.meta.url)
await mkdir(out, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const errors = [], steps = [], pages = []
let current
try {
  const stamp = process.env.FLOW_RUN_STAMP || Date.now(), password = `Test${stamp}Pass`
  async function call(ctx, method, path, data) {
    const csrf = method === 'GET' ? null : await (await ctx.request.get('/api/auth/csrf')).json()
    const res = await ctx.request.fetch(path, { method, data, headers: csrf ? { [csrf.headerName]: csrf.token } : {} })
    assert.ok(res.ok(), `${method} ${path}: ${res.status()} ${await res.text()}`)
    return res.status() === 204 ? null : res.json()
  }
  async function actor(role, mobile = false) {
    const ctx = await browser.newContext({ baseURL: base, viewport: mobile ? { width: 390, height: 844 } : { width: 1440, height: 1000 } })
    const username = `flow_${role}_${stamp}`
    if (!process.env.FLOW_RUN_STAMP) await call(ctx, 'POST', '/api/auth/register', { username, password })
    const page = await ctx.newPage(); pages.push(page); current = page
    page.on('pageerror', e => errors.push(e.message))
    page.on('response', r => { if (r.status() >= 500) errors.push(`${r.status()} ${new URL(r.url()).pathname}`) })
    await page.goto('/login')
    await page.getByPlaceholder('注册时设置的用户名').fill(username)
    await page.getByPlaceholder('请输入密码').fill(password)
    await page.getByRole('button', { name: '进入工作区', exact: true }).click()
    await page.waitForURL(url => !url.pathname.includes('login'))
    return { ctx, page, username }
  }
  const owner = await actor('owner'), organizer = await actor('org'), executor = await actor('exec'), participant = await actor('join', true)
  const org = await call(owner.ctx, 'POST', '/api/organizations', { name: `浏览器团建-${stamp}` })
  const op = `/api/organizations/${org.id}`
  for (const a of [organizer, executor, participant]) {
    const invitation = await call(owner.ctx, 'POST', `${op}/invitations`, {})
    await call(a.ctx, 'POST', '/api/invitations/accept', { code: invitation.code })
  }
  // 账号及成员关系通过真实 API 准备，以下业务写入均由页面操作。
  const p = owner.page; current = p
  await p.goto(`/org/${org.id}/activities`)
  await p.getByRole('button', { name: '创建活动', exact: true }).click()
  const dialog = p.getByRole('dialog')
  await dialog.getByLabel('活动主题', { exact: true }).fill(`浏览器团建-${stamp}`)
  await dialog.getByRole('combobox').first().click()
  await p.getByRole('option', { name: organizer.username, exact: true }).click()
  await dialog.getByLabel('活动简介', { exact: true }).fill('组织成员团建交流，场地和物料由专人负责。')
  await dialog.getByLabel('活动开始时间', { exact: true }).fill('2027-10-05T14:00')
  await dialog.getByLabel('活动结束时间', { exact: true }).fill('2027-10-05T16:00')
  await dialog.getByLabel('活动地点', { exact: true }).fill('团建场地')
  await dialog.getByLabel('预算（元）', { exact: true }).fill('1000')
  const created = p.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith(`${op}/activities`))
  await dialog.getByRole('button', { name: '保存草稿', exact: true }).click()
  const cr = await created; assert.equal(cr.status(), 201); const activity = await cr.json()
  const path = `/org/${org.id}/activities/${activity.id}`, ap = `${op}/activities/${activity.id}`
  steps.push('浏览器创建活动与指定负责人')
  const q = organizer.page; current = q
  await q.goto(path + '?tab=proposal')
  await q.locator('.proposal-section textarea').waitFor()
  assert.equal(await q.getByRole('button', { name: '删除草稿', exact: true }).count(), 0, '活动负责人不得显示组织负责人专属删除入口')
  await q.locator('.proposal-section textarea').fill('场地确认、物料采购与团队活动安排。')
  const saved = q.waitForResponse(r => r.request().method() === 'PUT' && r.url().endsWith('/proposal'))
  await q.getByRole('button', { name: '保存方案', exact: true }).click()
  assert.equal((await saved).status(), 200)
  await q.getByRole('button', { name: '提交确认', exact: true }).click()
  await q.getByRole('button', { name: '提交', exact: true }).click()
  await q.getByText('等待组织负责人确认或驳回', { exact: true }).waitFor()
  assert.equal(await q.getByRole('button', { name: '通过', exact: true }).count(), 0, '活动负责人不得显示审批入口')
  assert.equal(await q.getByRole('button', { name: '驳回', exact: true }).count(), 0)
  current = p; await p.goto(path)
  await p.getByRole('button', { name: '通过', exact: true }).click()
  await p.getByText('筹备中：分配任务、准备物资、审核报名', { exact: true }).waitFor()
  steps.push('活动负责人提交、组织负责人审批')
  current = q; await q.goto(path + '?tab=tasks')
  await q.getByRole('button', { name: '新建任务', exact: true }).waitFor()
  assert.equal(await q.getByRole('button', { name: '撤回确认', exact: true }).count(), 0)
  await q.getByRole('button', { name: '新建任务', exact: true }).click()
  const td = q.getByRole('dialog', { name: '新建任务', exact: true })
  await td.getByLabel('任务标题', { exact: true }).fill('采购团建物料')
  await td.getByRole('combobox').first().click()
  await q.getByRole('option', { name: executor.username, exact: true }).click()
  await td.getByRole('button', { name: '创建', exact: true }).click()
  await q.getByText('采购团建物料', { exact: true }).waitFor()
  steps.push('页面分派任务')
  await q.goto(path + '?tab=registration')
  await q.locator('.reg-switch').click()
  const m = participant.page; current = m
  await m.goto(path + '?tab=registration')
  await m.getByRole('button', { name: '我要报名', exact: true }).click()
  const rd = m.getByRole('dialog', { name: '报名', exact: true })
  await rd.getByLabel('展示姓名', { exact: true }).fill('普通参与者')
  await rd.getByRole('button', { name: '提交', exact: true }).click()
  await rd.waitFor({ state: 'hidden' })
  await m.getByText('待审核', { exact: true }).waitFor()
  await m.screenshot({ path: fileURLToPath(new URL('手机报名.png', out)), fullPage: true, animations: 'disabled' })
  current = q; await q.reload()
  await q.getByRole('button', { name: '通过', exact: true }).click()
  await q.getByRole('tabpanel', { name: '报名', exact: true }).getByText('已通过', { exact: true }).waitFor()
  await m.reload()
  await m.getByText('已通过', { exact: true }).waitFor()
  assert.equal(await m.getByRole('button', { name: '撤回确认', exact: true }).count(), 0)
  await m.screenshot({ path: fileURLToPath(new URL('手机报名.png', out)), fullPage: true, animations: 'disabled' })
  steps.push('手机报名、负责人审核、手机刷新查看通过结果')
  current = q; await q.goto(path + '?tab=lifecycle')
  await q.getByRole('button', { name: '启动执行', exact: true }).click()
  await q.getByRole('dialog').getByRole('button', { name: '启动执行', exact: true }).click()
  await q.getByText('执行中：记录进展与问题，结束后进入复盘', { exact: true }).waitFor()
  const e = executor.page; current = e
  await e.goto(`/org/${org.id}/my-tasks`)
  await e.getByRole('button', { name: '开始处理', exact: true }).click()
  await e.getByRole('button', { name: '标记完成', exact: true }).click()
  await e.getByText('暂无分配给你的未完成任务', { exact: false }).waitFor()
  steps.push('执行者从我的任务开始处理并完成')
  await m.goto(`/org/${org.id}/my-tasks`)
  await m.getByText('暂无分配给你的未完成任务', { exact: false }).waitFor()
  const tasks = await call(owner.ctx, 'GET', `${ap}/tasks`)
  assert.equal(tasks.items.length, 1); assert.equal(tasks.items[0].status, 'COMPLETED')
  assert.equal(await m.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
  current = q; await q.reload()
  await q.getByRole('button', { name: '结束执行', exact: true }).click()
  await q.getByRole('button', { name: '进入复盘', exact: true }).click()
  await q.getByText('复盘中：收集反馈、编写并确认总结', { exact: true }).waitFor()
  assert.equal((await call(owner.ctx, 'GET', ap)).status, 'REVIEWING')
  await q.getByRole('dialog').waitFor({ state: 'hidden' })
  await q.screenshot({ path: fileURLToPath(new URL('负责人复盘.png', out)), fullPage: true, animations: 'disabled' })
  steps.push('启动执行、完成任务后进入复盘，真实接口重读状态')
  assert.deepEqual(errors, [])
  await writeFile(new URL('结果.json', out), JSON.stringify({ database: 'activity_test', activityId: activity.id, orgId: org.id, steps, errors }, null, 2))
  console.log(JSON.stringify({ steps, activityId: activity.id, errors }))
} catch (e) {
  if (current) await writeFile(new URL('失败页面.txt', out), await current.locator('body').innerText())
  console.error(e.message); process.exitCode = 1
} finally { await browser.close() }
