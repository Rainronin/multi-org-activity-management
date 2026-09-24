// 活动创建入口定点回归：全部 API（含保存）使用替身，不写开发库、不证明服务端授权。
import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const output = new URL('../验证截图/UI第二轮-活动入口/', import.meta.url)
await mkdir(output, { recursive: true })
try {
  const context = await browser.newContext({ reducedMotion: 'reduce' })
  let role = 'MEMBER'
  let failCandidates = true
  let candidateRequests = 0
  const submissions = []
  let saveMode = 'reject'
  const unexpected = []
  const errors = []
  const org = () => ({ id: '1', name: '活动入口测试组织', role, memberCount: 2, createdAt: '2026-09-23', joinedAt: '2026-09-23', description: '' })
  const activity = { id: '1', name: '团建活动', status: 'APPROVED', ownerUsername: '小李', budget: '1000.00', registrationScope: 'INTERNAL' }
  await context.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() === 'POST' && path.endsWith('/activities')) {
      submissions.push({ body: route.request().postDataJSON(), key: route.request().headers()['idempotency-key'] })
      if (saveMode === 'reject') return route.fulfill({ status: 400, json: { status: 400, code: 'VALIDATION_FAILED', detail: '预算待修正', fieldErrors: [{ field: 'budget', message: '预算待修正' }] } })
      if (saveMode === 'unknown') return route.abort('failed')
      return route.fulfill({ status: 201, json: { ...activity, id: '99', name: '测试团建', status: 'DRAFT' } })
    }
    assert.equal(route.request().method(), 'GET', '未定义的写请求不得穿透到后端')
    let data
    if (path === '/api/auth/csrf') data = { token: 'test-only', headerName: 'X-CSRF-TOKEN' }
    else if (path === '/api/auth/me') data = { id: '1', username: '测试成员' }
    else if (path === '/api/me/organizations') data = { items: [org()] }
    else if (path === '/api/organizations/1') data = org()
    else if (path.endsWith('/activities')) data = { items: [activity], total: 1 }
    else if (path.endsWith('/members')) {
      candidateRequests++
      if (failCandidates) return route.fulfill({ status: 503, json: { detail: '测试成员接口暂不可用' } })
      data = { items: [{ memberId: '1', username: '小李', role: 'MEMBER' }], total: 1 }
    } else {
      unexpected.push(path)
      return route.fulfill({ status: 500, json: { detail: '未定义的测试接口' } })
    }
    return route.fulfill({ json: data })
  })
  const page = await context.newPage()
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${process.env.UI_BASE || 'http://127.0.0.1:5174'}/org/1/activities`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '团建活动', exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '创建活动', exact: true }).count(), 0, '普通成员有可见活动时也不得展示创建入口')
  role = 'OWNER'
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '创建活动', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByText('负责人列表加载失败，请重试。', { exact: true }).waitFor()
  assert.equal(await dialog.getByRole('button', { name: '保存草稿' }).isDisabled(), true)
  failCandidates = false
  await dialog.getByRole('button', { name: '重新加载负责人' }).click()
  await page.waitForLoadState('networkidle')
  assert.equal(await dialog.getByText('负责人列表加载失败，请重试。', { exact: true }).count(), 0)
  await dialog.getByRole('combobox').first().click()
  await page.getByRole('option', { name: '小李', exact: true }).click()
  assert.equal(candidateRequests, 2, '失败后应允许就地重试')
  for (const width of [1440, 1024, 768, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${width}px 页面溢出`)
    const saveBox = await dialog.getByRole('button', { name: '保存草稿', exact: true }).boundingBox()
    assert.ok(saveBox && saveBox.y >= 0 && saveBox.y + saveBox.height <= 900, `${width}px 保存按钮须留在视口内`)
    await page.screenshot({ path: fileURLToPath(new URL(`创建入口-${width}.png`, output)), fullPage: true })
  }
  await page.setViewportSize({ width: 390, height: 520 })
  const compactSave = await dialog.getByRole('button', { name: '保存草稿', exact: true }).boundingBox()
  assert.ok(compactSave.y + compactSave.height <= 520, '短视口仍可操作保存按钮')
  await page.setViewportSize({ width: 1440, height: 900 })
  await dialog.getByRole('button', { name: '保存草稿', exact: true }).click()
  await dialog.getByText('请输入活动主题', { exact: true }).waitFor({ timeout: 3000 })
  assert.equal(submissions.length, 0, '无效表单不应发送创建请求')
  await dialog.getByLabel('活动主题', { exact: true }).fill('测试团建')
  await dialog.getByLabel('预算（元）', { exact: true }).fill('-1')
  await dialog.getByRole('button', { name: '保存草稿', exact: true }).click()
  assert.equal(submissions.length, 0)
  await dialog.getByLabel('预算（元）', { exact: true }).fill('100.00')
  await dialog.getByLabel('活动开始时间', { exact: true }).fill('2026-10-16T09:00')
  await dialog.getByLabel('活动结束时间', { exact: true }).fill('2026-10-16T08:00')
  await dialog.getByRole('button', { name: '保存草稿', exact: true }).click()
  await dialog.getByText('结束时间必须晚于开始时间', { exact: true }).waitFor()
  assert.equal(submissions.length, 0)
  await dialog.getByLabel('活动结束时间', { exact: true }).fill('2026-10-16T17:00')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('button', { name: '继续填写', exact: true }).click()
  assert.equal(await dialog.getByLabel('活动主题', { exact: true }).inputValue(), '测试团建')
  await dialog.getByRole('button', { name: '保存草稿', exact: true }).click()
  await dialog.getByText('预算待修正', { exact: true }).first().waitFor()
  assert.equal(submissions[0].body.startTime, '2026-10-16T09:00:00+08:00')
  saveMode = 'unknown'
  await dialog.getByLabel('预算（元）', { exact: true }).fill('200.00')
  await dialog.getByRole('button', { name: '保存草稿', exact: true }).click()
  await dialog.getByRole('button', { name: '核对保存结果', exact: true }).waitFor()
  assert.notEqual(submissions[0].key, submissions[1].key, '明确拒绝后修改内容应使用新意图')
  assert.equal(await dialog.getByLabel('活动主题', { exact: true }).isDisabled(), true)
  saveMode = 'success'
  await dialog.getByRole('button', { name: '核对保存结果', exact: true }).click()
  await page.getByText('草稿已保存，可继续完善活动方案。', { exact: true }).waitFor()
  assert.deepEqual(submissions[2], submissions[1], '结果未知时必须原键、原内容重试')
  assert.equal(await page.getByRole('button', { name: '查看活动草稿', exact: true }).count(), 1)
  assert.deepEqual(unexpected, [])
  assert.deepEqual(errors, [])
  console.log('PASS: 成员入口、负责人失败重试、五档视口、表单校验、取消保护、时间偏移、保存拒绝与未知重试；API 替身，无业务写入')
} finally {
  await browser.close()
}
