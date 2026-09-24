// 第二轮任务流程：接口替身验证 UI，不写真实业务数据。
import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
const output = new URL('../验证截图/UI第二轮-任务流程/', import.meta.url)
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const context = await browser.newContext({ reducedMotion: 'reduce' })
  const org = { id: '1', name: '任务测试组织', role: 'OWNER', createdAt: '2026-09-23', joinedAt: '2026-09-23', memberCount: 2 }
  const task = { taskId: '2', activityId: 1, title: '选定团建场地', description: '联系场地，确认雨天备选方案。', assigneeMemberId: '1', assigneeUsername: '测试负责人', priority: 'HIGH', dueAt: '2026-10-10T09:00:00+08:00', status: 'PENDING', overdue: false, version: 1 }
  const tasks = [task]
  let rejectTask = true
  let taskWrites = 0
  const errors = []
  await context.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    let data
    if (route.request().method() === 'POST' && path.endsWith('/tasks')) {
      taskWrites++
      if (!rejectTask) {
        const created = { ...task, ...route.request().postDataJSON(), taskId: '3' }
        tasks.push(created)
        return route.fulfill({ status: 201, json: created })
      }
      return route.fulfill({ status: 400, json: { status: 400, code: 'VALIDATION_FAILED', detail: '负责人已退出组织，请重新选择。' } })
    }
    assert.equal(route.request().method(), 'GET', `意外写入 ${path}`)
    if (path === '/api/auth/me') data = { id: '1', username: '测试负责人' }
    else if (path === '/api/auth/csrf') data = { token: 'test-only', headerName: 'X-CSRF-TOKEN' }
    else if (path === '/api/me/organizations') data = { items: [org] }
    else if (path === '/api/organizations/1') data = org
    else if (path.endsWith('/my-tasks') || path.endsWith('/tasks')) data = { items: tasks }
    else if (path.endsWith('/members')) data = { items: [{ memberId: '1', username: '测试负责人', role: 'OWNER' }] }
    else if (path.endsWith('/activities/1')) data = { id: '1', name: '秋季团建', status: 'APPROVED', ownerMemberId: '1', ownerUsername: '测试负责人', registrationScope: 'INTERNAL', budget: '1000.00', version: 1, createdAt: '2026-09-23' }
    else if (path.endsWith('/attachments') || path.endsWith('/materials')) data = { items: [] }
    else if (path.includes('/ai/')) data = { available: false }
    else throw new Error(`未定义请求 ${path}`)
    await route.fulfill({ json: data })
  })
  const page = await context.newPage()
  page.on('pageerror', e => errors.push(e.message))
  await page.goto('http://127.0.0.1:5174/org/1/my-tasks', { waitUntil: 'networkidle' })
  await page.getByText(task.description, { exact: true }).waitFor({ timeout: 3000 })
  await page.screenshot({ path: fileURLToPath(new URL('我的任务.png', output)), fullPage: true })
  await page.getByRole('button', { name: '查看活动', exact: true }).click()
  await page.waitForURL('**/activities/1?tab=tasks')
  await page.getByRole('columnheader', { name: '截止时间', exact: true }).waitFor()
  await page.getByText(task.description, { exact: true }).waitFor()
  await page.screenshot({ path: fileURLToPath(new URL('活动任务.png', output)), fullPage: true })
  await page.getByRole('button', { name: '新建任务', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新建任务', exact: true })
  await dialog.getByRole('button', { name: '创建', exact: true }).click()
  await dialog.getByText('请输入任务标题', { exact: true }).waitFor()
  assert.equal(taskWrites, 0)
  await dialog.getByLabel('任务标题', { exact: true }).fill('采购物料')
  await dialog.getByRole('combobox').first().click()
  await page.getByRole('option', { name: '测试负责人', exact: true }).click()
  await dialog.getByRole('button', { name: '创建', exact: true }).click()
  await dialog.getByText('负责人已退出组织，请重新选择。', { exact: true }).waitFor()
  assert.equal(taskWrites, 1)
  for (const width of [1440, 1024, 768, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    const box = await dialog.boundingBox()
    assert.ok(box.x >= 0 && box.x + box.width <= width, `${width}px 任务弹窗溢出`)
    await page.screenshot({ path: fileURLToPath(new URL(`任务弹窗-${width}.png`, output)), fullPage: true })
  }
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('button', { name: '继续填写', exact: true }).click()
  assert.equal(await dialog.getByLabel('任务标题', { exact: true }).inputValue(), '采购物料')
  rejectTask = false
  await dialog.getByRole('button', { name: '创建', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  await page.getByText('采购物料', { exact: true }).waitFor()
  assert.equal(taskWrites, 2)
  assert.deepEqual(errors, [])
  console.log('PASS: 任务说明、截止时间、直达页签、任务表单校验、弹窗失败反馈、取消保护、五档视口；全部 API 替身')
} finally { await browser.close() }
