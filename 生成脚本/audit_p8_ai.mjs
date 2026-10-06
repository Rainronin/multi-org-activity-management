// P8 真机核对：组织模型配置（保存／测试／启用／修订）、两种兼容协议的真实 HTTP 调用、
// 生成与防重键重放、人工编辑与确认、模型故障与超时后的手工路径，以及页面显示与窄屏布局。
// 默认使用本机桩；显式指定 P8_REAL_CONFIG 才执行所选协议及两类文稿的真实厂商页面验收。
// 仅可针对显式连接 activity_test 的后端运行：会新增专用测试数据。
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { createInterface } from 'node:readline'
import { fileURLToPath } from 'node:url'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') {
  throw new Error('先核实后端测试库，再设置 AUDIT_TEST_DATABASE=activity_test')
}

// 真实验收只读取调用者本次明确提供的标准输入或文件，不查旧密钥或既有组织配置。
const rehearsal = process.env.P8_REAL_REHEARSAL === '1'
let realConfig = null
if (process.env.P8_REAL_CONFIG) {
  let raw
  try {
    let configText
    if (process.env.P8_REAL_CONFIG === '-') {
      const terminal = process.stdin.isTTY
      if (terminal) process.stdin.setRawMode(true)
      const input = createInterface({ input: process.stdin, terminal: false })
      try {
        configText = await new Promise((resolve, reject) => {
          input.once('line', resolve)
          input.once('close', () => reject(new Error('未收到标准输入配置')))
        })
      } finally {
        input.close()
        if (terminal) process.stdin.setRawMode(false)
      }
    } else configText = await readFile(process.env.P8_REAL_CONFIG, 'utf8')
    raw = JSON.parse(configText)
  }
  catch { throw new Error('无法读取本次新的 P8_REAL_CONFIG JSON 配置') }
  let endpoint
  try { endpoint = new URL(raw.endpoint) }
  catch { throw new Error('请提供有效的完整模型端点') }
  assert.ok(!endpoint.username && !endpoint.password && !endpoint.search && !endpoint.hash,
    '端点不得携带凭据、查询参数或片段')
  assert.ok(rehearsal ? endpoint.hostname === '127.0.0.1' && endpoint.protocol === 'http:'
    : endpoint.protocol === 'https:' && !['127.0.0.1', 'localhost', '[::1]'].includes(endpoint.hostname),
  '真实验收需要 HTTPS 厂商端点；预演只允许本机 HTTP 桩')
  assert.ok(typeof raw.model === 'string' && raw.model.trim().length > 0 && raw.model.length <= 128, '请填写模型 ID')
  assert.ok(['X_API_KEY', 'BEARER'].includes(raw.authScheme), '请指定 X_API_KEY 或 BEARER 认证方式')
  const protocol = raw.protocol ?? 'ANTHROPIC_MESSAGES'
  assert.ok(['ANTHROPIC_MESSAGES', 'OPENAI_CHAT_COMPLETIONS'].includes(protocol), '未知的兼容协议')
  assert.ok(protocol !== 'OPENAI_CHAT_COMPLETIONS' || raw.authScheme === 'BEARER', 'OpenAI 协议使用 BEARER 认证')
  assert.ok(typeof raw.apiKey === 'string' && raw.apiKey.trim().length > 0 && raw.apiKey.length <= 400,
    '请在本次配置中填写新密钥')
  const maxOutputTokens = raw.maxOutputTokens ?? 2048
  const timeoutSeconds = raw.timeoutSeconds ?? 60
  assert.ok(Number.isInteger(maxOutputTokens) && maxOutputTokens >= 64 && maxOutputTokens <= 4096, '输出上限需为 64—4096 的整数')
  assert.ok(Number.isInteger(timeoutSeconds) && timeoutSeconds >= 5 && timeoutSeconds <= 60, '单次时限需为 5—60 秒的整数')
  realConfig = { protocol, endpoint: endpoint.href, model: raw.model.trim(),
    authScheme: raw.authScheme, apiKey: raw.apiKey.trim(), maxOutputTokens, timeoutSeconds, outputLimitField: 'MAX_TOKENS' }
}
if (process.argv.includes('--check-config')) {
  assert.ok(realConfig, '请显式指定本次新的 P8_REAL_CONFIG 配置')
  console.log(JSON.stringify({ check: 'P8 配置前置校验', protocol: realConfig.protocol,
    endpointHost: new URL(realConfig.endpoint).host, model: realConfig.model, rehearsal, businessWrites: 0 }))
  process.exit(0)
}
assert.ok(!rehearsal || realConfig, '预演也需显式指定 P8_REAL_CONFIG 配置')

const PORT = Number(process.env.P8_STUB_PORT || 18099)
const API_KEY = 'sk-p8-audit-secret-key'
const stub = {
  mode: 'ok',
  delayMs: 0,
  received: [],
}
const stubServer = createServer((req, res) => {
  let raw = ''
  req.on('data', (chunk) => {
    raw += chunk
  })
  req.on('end', async () => {
    let body = null
    try {
      body = raw ? JSON.parse(raw) : null
    } catch {
      body = { unparsed: raw.slice(0, 200) }
    }
    stub.received.push({ path: req.url, headers: req.headers, body })
    if (stub.delayMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, stub.delayMs))
    }
    const anthropic = req.url.includes('/messages')
    res.setHeader('Content-Type', 'application/json')
    if (stub.mode === 'unauthorized') {
      res.statusCode = 401
      res.end(JSON.stringify({ error: { message: 'invalid api key' } }))
      return
    }
    const text = raw.includes('《活动总结》')
      ? `${anthropic ? 'P8-STUB-ANTHROPIC' : 'P8-STUB-OPENAI'} 总结草稿正文\n一、活动目标：待补充\n二、执行概况：按记录整理`
      : `${anthropic ? 'P8-STUB-ANTHROPIC' : 'P8-STUB-OPENAI'} 方案草稿正文\n一、方案概述：待补充\n二、活动流程：待补充`
    res.statusCode = 200
    res.end(anthropic
      ? JSON.stringify({ content: [{ type: 'text', text }], stop_reason: 'end_turn' })
      : JSON.stringify({ choices: [{ message: { role: 'assistant', content: text }, finish_reason: 'stop' }] }))
  })
})
if (!realConfig || rehearsal) await new Promise((resolve) => stubServer.listen(PORT, '127.0.0.1', resolve))

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const shots = []
try {
  const stamp = process.env.FLOW_RUN_STAMP || Date.now()
  console.log(JSON.stringify({ runStamp: stamp }))
  const ownerName = `p8owner${stamp}`
  const memberName = `p8member${stamp}`
  const outsiderName = `p8out${stamp}`
  const password = `Pass${stamp}word`
  const base = 'http://127.0.0.1:5174'
  const openaiEndpoint = `http://127.0.0.1:${PORT}/v1/chat/completions`
  const anthropicEndpoint = `http://127.0.0.1:${PORT}/v1/messages`

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
    if (!res.ok()) throw new Error(`${method} ${path} -> ${res.status()} ${realConfig ? (await res.json().catch(() => ({}))).code ?? 'UNKNOWN' : await res.text()}`)
    return res.json()
  }
  async function status(ctx, method, path, data, extraHeaders) {
    const res = await call(ctx, method, path, data, extraHeaders)
    const text = await res.text()
    let body = null
    try {
      body = text ? JSON.parse(text) : null
    } catch {
      body = { raw: text.slice(0, 200) }
    }
    return { status: res.status(), body }
  }
  /** Element Plus 的 ElInput 可能把透传属性放在内层 input 上，两种形态都兼容。 */
  function fieldInput(page, field) {
    return page.locator(`input[data-ai-field="${field}"], [data-ai-field="${field}"] input`).first()
  }
  async function fillField(page, field, value) {
    await fieldInput(page, field).fill(value)
  }

  async function login(ctx, name) {
    if (process.env.REUSE_TEST_ACCOUNTS !== '1') {
      // 注册按来源 IP 限流（10 次/5 分钟）：连续核对时等待窗口恢复，最多约 7 分钟
      for (let attempt = 1; ; attempt += 1) {
        const res = await call(ctx, 'POST', '/api/auth/register', { username: name, password })
        if (res.ok()) break
        if (res.status() !== 429 || attempt >= 10) throw new Error(`注册失败 ${res.status()} ${await res.text()}`)
        await new Promise((resolve) => setTimeout(resolve, 45000))
      }
    }
    const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
    assert.equal((await ctx.request.post('/api/auth/login', {
      form: { username: name, password }, headers: { [csrf.headerName]: csrf.token },
    })).status(), 204, '准备流程：登录失败')
  }

  const owner = await browser.newContext({ baseURL: base })
  await login(owner, ownerName)
  const member = await browser.newContext({ baseURL: base })
  await login(member, memberName)
  const outsider = await browser.newContext({ baseURL: base })
  await login(outsider, outsiderName)

  const org = await json(owner, 'POST', '/api/organizations', { name: `P8核对-${stamp}` })
  const configPath = `/api/organizations/${org.id}/ai/config`
  const invitation = await json(owner, 'POST', `/api/organizations/${org.id}/invitations`, {})
  await json(member, 'POST', '/api/invitations/accept', { code: invitation.code })
  const members = (await json(owner, 'GET', `/api/organizations/${org.id}/members?pageSize=100`)).items
  const ownerMemberId = members.find((x) => x.username === ownerName).memberId

  // 准备：一个复盘中活动（总结）与一个草稿活动（方案）
  const reviewing = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
    name: 'P8 总结核对活动', description: '帮助新成员熟悉组织，按计划完成场地布置',
    location: '礼堂', ownerMemberId, startTime: '2027-12-05T14:00:00+08:00',
    endTime: '2027-12-05T16:00:00+08:00', registrationScope: 'INTERNAL', capacity: 20, budget: '100.00',
  }, { 'Idempotency-Key': `p8-sum-${stamp}` })
  const reviewingPath = `/api/organizations/${org.id}/activities/${reviewing.id}`
  await json(owner, 'PUT', `${reviewingPath}/proposal`, { content: '已确认方案：分组活动与分享', version: reviewing.version })
  await json(owner, 'POST', `${reviewingPath}/submit`)
  await json(owner, 'POST', `${reviewingPath}/approve`,
    { note: '核对确认', version: (await json(owner, 'GET', reviewingPath)).version })
  await json(owner, 'POST', `${reviewingPath}/start-execution`,
    { version: (await json(owner, 'GET', reviewingPath)).version })
  await json(owner, 'POST', `${reviewingPath}/execution-records`, { category: 'PROGRESS', content: '按计划完成场地布置' })
  await json(owner, 'POST', `${reviewingPath}/finish-execution`,
    { version: (await json(owner, 'GET', reviewingPath)).version })
  const reviewingDetail = await json(owner, 'GET', reviewingPath)

  const draftActivity = await json(owner, 'POST', `/api/organizations/${org.id}/activities`, {
    name: 'P8 方案核对活动', description: '迎新活动，需要给出流程与分工建议',
    location: '待定', ownerMemberId, startTime: '2027-12-08T18:00:00+08:00',
    endTime: '2027-12-08T20:00:00+08:00', registrationScope: 'INTERNAL', capacity: 60, budget: '3000.00',
  }, { 'Idempotency-Key': `p8-plan-${stamp}` })
  const draftPath = `/api/organizations/${org.id}/activities/${draftActivity.id}`

  if (realConfig) {
    // 仅三次模型调用。已有故障、角色和响应丢失矩阵留在原桩模式，真实厂商失败不换键重发。
    const evidence = { check: rehearsal ? 'P8 真实厂商路径桩预演' : 'P8 真实厂商页面验收', realVendor: !rehearsal, status: 'RUNNING',
      startedAt: new Date().toISOString(), protocol: realConfig.protocol, model: realConfig.model,
      endpointHost: new URL(realConfig.endpoint).host, orgId: org.id, generations: [], screenshots: [] }
    const evidenceUrl = new URL(`../验证截图/P8-${rehearsal ? '真实路径桩预演' : `真实厂商-${realConfig.protocol}`}-结果.json`, import.meta.url)
    const ownerPage = await owner.newPage()
    await ownerPage.setViewportSize({ width: 1440, height: 1000 })
    ownerPage.setDefaultTimeout(15000)
    const pageErrors = []
    ownerPage.on('pageerror', () => pageErrors.push('页面运行异常'))
    await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
    let failure
    try {
      const saved = await json(owner, 'PUT', configPath, realConfig)
      assert.ok(saved.hasApiKey && !JSON.stringify(saved).includes(realConfig.apiKey), '配置响应不得回显密钥')
      const testKey = `p8-real-test-${stamp}`
      evidence.connectionTestRequestKey = testKey
      await writeFile(evidenceUrl, JSON.stringify(evidence, null, 2))
      const tested = await json(owner, 'POST', `${configPath}/test`, { version: saved.version }, { 'Idempotency-Key': testKey })
      evidence.connectionTest = { status: tested.status, requestId: tested.requestId, errorCode: tested.errorCode }
      assert.equal(tested.status, 'SUCCEEDED', '连接未成功，停止真实调用；可按原键只读核对')
      await json(owner, 'POST', `${configPath}/enable`, { version: saved.version })
      for (const sample of [
        { type: 'ACTIVITY_PLAN', path: draftPath, id: draftActivity.id, tab: 'proposal', field: 'proposal', save: '保存方案',
          edit: '人工补充：现场安排签到与合影。' },
        { type: 'ACTIVITY_SUMMARY', path: reviewingPath, id: reviewing.id, tab: 'lifecycle', field: 'summary', save: '保存总结',
          edit: '人工补充：下次提前一天布置场地。' },
      ]) {
        await ownerPage.goto(`/org/${org.id}/activities/${sample.id}?tab=${sample.tab}`, { waitUntil: 'networkidle' })
        const panel = ownerPage.locator(`[data-ai-type="${sample.type}"]`)
        await panel.waitFor()
        if (sample.type === 'ACTIVITY_PLAN') {
          await fillField(ownerPage, 'topic', '迎新晚会')
          await fillField(ownerPage, 'goal', '帮助新成员熟悉组织')
        } else {
          await panel.locator('[data-ai-action="toggle-scope"]').click()
          await panel.locator('[data-ai-scope]').waitFor()
        }
        const generationResponse = ownerPage.waitForResponse(r => r.url().endsWith(`${sample.path}/ai/generations`)
          && r.request().method() === 'POST', { timeout: 90000 })
        const generationRequest = ownerPage.waitForRequest(r => r.url().endsWith(`${sample.path}/ai/generations`) && r.method() === 'POST')
        await panel.locator('[data-ai-action="generate"]').click()
        const sent = await generationRequest
        const row = { type: sample.type, activityId: sample.id, requestKey: sent.headers()['idempotency-key'], status: 'SENT' }
        evidence.generations.push(row)
        await writeFile(evidenceUrl, JSON.stringify(evidence, null, 2))
        const generatedHttp = await generationResponse
        const generation = await generatedHttp.json()
        Object.assign(row, {
          requestId: generatedHttp.headers()['x-request-id'], httpStatus: generatedHttp.status(), status: generation.status,
          generationId: generation.generationId, errorCode: generation.code })
        assert.equal(generatedHttp.status(), 201, '生成未成功，停止调用；保留原键供只读核对')
        assert.equal(generation.status, 'SUCCEEDED')
        assert.ok(typeof generation.output === 'string' && generation.output.trim(), '厂商需返回非空文本')
        assert.ok(!generation.output.includes(realConfig.apiKey), '输出包含密钥，停止记录正文及截图')
        assert.ok(rehearsal || !generation.output.includes('P8-STUB-'), '真实厂商不能用桩正文充当证据')
        await panel.locator('[data-ai-output]').waitFor()
        assert.ok(await panel.locator('[data-ai-output]').inputValue() === generation.output, '页面需展示本次模型正文')
        row.outputSha256 = createHash('sha256').update(generation.output).digest('hex')
        row.outputLength = generation.output.length
        const adoptResponse = ownerPage.waitForResponse(r => r.url().endsWith(`${sample.path}/ai/generations/${generation.generationId}/adopt`))
        await panel.locator('[data-ai-action="adopt"]').click()
        assert.equal((await adoptResponse).status(), 200, '页面采用应成功')
        await panel.getByRole('button', { name: '已采用', exact: true }).waitFor()
        await panel.locator('[data-ai-action="apply"]').click()
        const editedText = `${generation.output}\n${sample.edit}`
        const editor = sample.type === 'ACTIVITY_PLAN' ? ownerPage.locator('textarea').first()
          : ownerPage.getByPlaceholder(/目标、执行概况/)
        await editor.fill(editedText)
        const saveResponse = ownerPage.waitForResponse(r => r.url().endsWith(`${sample.path}/${sample.field}`) && r.request().method() === 'PUT')
        await ownerPage.getByRole('button', { name: sample.save, exact: true }).click()
        assert.equal((await saveResponse).status(), 200, '页面人工编辑应保存成功')
        const detail = await json(owner, 'GET', sample.path)
        assert.ok(detail[sample.field] === editedText, '持久化需完整保留真实正文及人工修改')
        row.humanEditSaved = true
        if (sample.type === 'ACTIVITY_SUMMARY') {
          const confirmResponse = ownerPage.waitForResponse(r => r.url().endsWith(`${sample.path}/summary/confirm`) && r.request().method() === 'POST')
          await ownerPage.getByRole('button', { name: '确认总结', exact: true }).click()
          assert.equal((await confirmResponse).status(), 200, '总结需经正式页面确认')
          const confirmed = await json(owner, 'GET', sample.path)
          assert.ok(confirmed.summaryConfirmedAt && confirmed.summary === editedText, '确认需保留人工编辑内容')
          row.summaryConfirmedAt = confirmed.summaryConfirmedAt
        }
        const history = (await json(owner, 'GET', `${sample.path}/ai/generations?type=${sample.type}`)).items
        assert.equal(history.length, 1, '本模式每类只生成一次，不自动重发模型请求')
        assert.ok(history[0].adoptedAt, '采用事实需持久化')
        row.adoptedAt = history[0].adoptedAt
        await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
        const screenshot = fileURLToPath(new URL(`../验证截图/P8-${rehearsal ? '桩预演' : `真实厂商-${realConfig.protocol}`}-${sample.type}.png`, import.meta.url))
        await ownerPage.screenshot({ path: screenshot, fullPage: true })
        evidence.screenshots.push(screenshot)
      }
      assert.equal(pageErrors.length, 0, '页面不得出现运行异常')
      evidence.status = 'PASS'
    } catch (error) {
      failure = error
      evidence.status = 'FAIL'
      evidence.error = String(error.message).replaceAll(realConfig.apiKey, '***')
    } finally {
      // 只清除此脚本新组织的密钥；失败也执行，不触碰任何旧组织配置。
      try {
        const current = await json(owner, 'GET', configPath)
        if (current.hasApiKey) await json(owner, 'POST', `${configPath}/clear-key`, { version: current.version })
        const cleared = await json(owner, 'GET', configPath)
        assert.ok(!cleared.hasApiKey && !cleared.enabled, '本次测试密钥需清除且配置停用')
        evidence.testKeyCleared = true
      } catch (error) {
        evidence.testKeyCleared = false
        evidence.status = 'FAIL'
        evidence.cleanupError = String(error.message).replaceAll(realConfig.apiKey, '***')
        failure ??= error
      }
      evidence.browserErrorCount = pageErrors.length
      evidence.finishedAt = new Date().toISOString()
      await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })
      await writeFile(evidenceUrl, JSON.stringify(evidence, null, 2))
      console.log(JSON.stringify(evidence, null, 1))
    }
    if (failure) throw new Error(String(failure.message).replaceAll(realConfig.apiKey, '***'))
  } else {
  // 1. 配置：首次保存、未测试不能启用、密钥不回显
  const initial = await json(owner, 'GET', configPath)
  assert.equal(initial.configState, 'NOT_CONFIGURED', '初始应为尚未配置')
  const saved = await json(owner, 'PUT', configPath, {
    protocol: 'OPENAI_CHAT_COMPLETIONS', endpoint: openaiEndpoint, model: 'p8-stub-model',
    apiKey: API_KEY, maxOutputTokens: 128, outputLimitField: 'MAX_TOKENS', timeoutSeconds: 10,
  })
  assert.equal(saved.hasApiKey, true, '保存后应显示已配置密钥')
  assert.equal(saved.enabled, false, '保存本身不启用')
  const savedRaw = JSON.stringify(saved)
  assert.ok(!savedRaw.includes(API_KEY), '响应不得回显密钥')
  const enableBeforeTest = await status(owner, 'POST', `${configPath}/enable`, { version: saved.version })
  assert.equal(enableBeforeTest.status, 409, '未测试不能启用')
  assert.equal(enableBeforeTest.body.code, 'AI_TEST_REQUIRED', '未测试启用应给出 AI_TEST_REQUIRED')
  const outsiderConfig = await status(outsider, 'GET', configPath)
  assert.equal(outsiderConfig.status, 404, '非成员不得读取组织配置')
  const memberRead = await json(member, 'GET', configPath)
  assert.equal(memberRead.viewerRole, 'MEMBER', '普通成员只读可用状态')
  assert.equal(memberRead.endpoint, null, '普通成员不应看到端点细节')
  const memberWrite = await status(member, 'PUT', configPath, {
    protocol: 'OPENAI_CHAT_COMPLETIONS', endpoint: openaiEndpoint, model: 'p8-stub-model', version: saved.version,
  })
  assert.equal(memberWrite.status, 403, '普通成员不得修改配置')

  // 2. 连接测试：只发固定短文本，且不计入业务资料
  stub.received.length = 0
  const tested = await json(owner, 'POST', `${configPath}/test`, { version: saved.version },
    { 'Idempotency-Key': `p8-config-test-${stamp}` })
  assert.equal(tested.status, 'SUCCEEDED', '连接测试应成功')
  assert.equal(stub.received.length, 1, '连接测试应只调用一次模型')
  const testBody = JSON.stringify(stub.received[0].body)
  assert.ok(testBody.includes('连接正常'), '测试应发送固定短文本')
  assert.ok(!testBody.includes('P8 总结核对活动') && !testBody.includes('礼堂'), '测试不得发送组织资料')
  const enabledConfig = await json(owner, 'POST', `${configPath}/enable`, { version: saved.version })
  assert.equal(enabledConfig.enabled, true, '测试通过后应能启用')
  assert.equal(enabledConfig.connectionRevision, tested.testedRevision, '测试版本应与当前修订一致')

  // 3. 总结生成：输入范围预览、真实 HTTP 调用形态、防重键重放、人工采用
  const preview = await json(owner, 'GET', `${reviewingPath}/ai/input?type=ACTIVITY_SUMMARY`)
  assert.equal(preview.available, true, '启用后应显示可用')
  assert.ok(preview.scope.length >= 4, '应展示输入范围')
  assert.ok(preview.missing.includes('反馈'), '无反馈应列入缺失项')
  stub.received.length = 0
  const summaryKey = `p8-summary-${stamp}`
  const summaryGeneration = await json(owner, 'POST', `${reviewingPath}/ai/generations`,
    { type: 'ACTIVITY_SUMMARY' }, { 'Idempotency-Key': summaryKey })
  assert.equal(summaryGeneration.status, 'SUCCEEDED', '总结生成应成功')
  assert.ok(summaryGeneration.output.includes('P8-STUB-OPENAI'), '应返回模型正文')
  assert.equal(stub.received.length, 1, '一次生成只调用一次模型')
  const openaiRequest = stub.received[0]
  assert.ok(openaiRequest.path.includes('/v1/chat/completions'), '应请求配置的完整端点')
  assert.equal(openaiRequest.headers.authorization, `Bearer ${API_KEY}`, 'OpenAI 协议使用 Bearer')
  assert.equal(openaiRequest.body.stream, false, '不使用流式输出')
  assert.equal(openaiRequest.body.max_tokens, 128, '输出上限使用配置值')
  assert.equal(openaiRequest.body.messages[0].role, 'system', 'system 与 messages 分离')
  assert.ok(JSON.stringify(openaiRequest.body).includes('【资料开始】'), '业务资料应放在定界块中')
  assert.ok(!JSON.stringify(openaiRequest.body).includes(API_KEY), '请求体不得包含密钥')

  const replay = await json(owner, 'POST', `${reviewingPath}/ai/generations`,
    { type: 'ACTIVITY_SUMMARY' }, { 'Idempotency-Key': summaryKey })
  assert.equal(replay.generationId, summaryGeneration.generationId, '同防重键重放应返回既有记录')
  assert.equal(stub.received.length, 1, '重放不得重复调用模型')
  const adopted = await json(owner, 'POST',
    `${reviewingPath}/ai/generations/${summaryGeneration.generationId}/adopt`)
  assert.ok(adopted.adoptedAt, '采用应记录时间')

  // 4. 方案生成：切换到 Anthropic 协议，改地址必须重输密钥
  const noKeyChange = await status(owner, 'PUT', configPath, {
    protocol: 'ANTHROPIC_MESSAGES', endpoint: anthropicEndpoint, model: 'p8-stub-model',
    authScheme: 'X_API_KEY', maxOutputTokens: 128, outputLimitField: 'MAX_TOKENS', timeoutSeconds: 10,
    version: enabledConfig.version,
  })
  assert.equal(noKeyChange.status, 400, '改协议或地址必须重输密钥')
  const switched = await json(owner, 'PUT', configPath, {
    protocol: 'ANTHROPIC_MESSAGES', endpoint: anthropicEndpoint, model: 'p8-stub-model',
    authScheme: 'X_API_KEY', apiKey: API_KEY, maxOutputTokens: 128, outputLimitField: 'MAX_TOKENS',
    timeoutSeconds: 10, version: enabledConfig.version,
  })
  assert.equal(switched.connectionRevision, enabledConfig.connectionRevision + 1, '配置修订应递增')
  assert.equal(switched.enabled, false, '配置变化后需要重新测试')
  assert.equal(switched.testedRevision, null, '旧测试状态应清除')
  const blockedGenerate = await status(owner, 'POST', `${draftPath}/ai/generations`,
    { type: 'ACTIVITY_PLAN', conditions: { topic: '迎新晚会', goal: '帮助新成员熟悉组织' } },
    { 'Idempotency-Key': `p8-blocked-${stamp}` })
  assert.equal(blockedGenerate.status, 409, '未重新测试不得生成')
  assert.equal(blockedGenerate.body.code, 'AI_TEST_REQUIRED', '应提示需要重新测试')
  const retested = await json(owner, 'POST', `${configPath}/test`, { version: switched.version },
    { 'Idempotency-Key': `p8-config-retest-${stamp}` })
  assert.equal(retested.testedRevision, switched.connectionRevision, '测试应绑定当前修订')
  const reEnabled = await json(owner, 'POST', `${configPath}/enable`, { version: switched.version })
  assert.equal(reEnabled.enabled, true, '重新测试后应能启用')

  stub.received.length = 0
  const planGeneration = await json(owner, 'POST', `${draftPath}/ai/generations`, {
    type: 'ACTIVITY_PLAN',
    conditions: {
      topic: '迎新晚会', goal: '帮助新成员熟悉组织', audience: '新成员与老成员',
      expectedParticipants: 60, budget: '不超过 3000 元', timePlace: '九月第一个周五晚上，礼堂',
      constraints: '不使用明火',
    },
  }, { 'Idempotency-Key': `p8-plan-gen-${stamp}` })
  assert.equal(planGeneration.status, 'SUCCEEDED', '方案生成应成功')
  assert.ok(planGeneration.output.includes('P8-STUB-ANTHROPIC'), '应返回 Anthropic 协议的正文')
  const anthropicRequest = stub.received[0]
  assert.ok(anthropicRequest.path.includes('/v1/messages'), '应请求 Anthropic 兼容端点')
  assert.equal(anthropicRequest.headers['x-api-key'], API_KEY, 'Anthropic 协议默认 x-api-key')
  assert.equal(anthropicRequest.headers['anthropic-version'], '2023-06-01', 'anthropic-version 固定')
  assert.equal(anthropicRequest.body.max_tokens, 128, 'max_tokens 必填')
  assert.ok(typeof anthropicRequest.body.system === 'string', 'system 与 messages 分离')
  assert.equal(anthropicRequest.body.messages[0].role, 'user', '业务条件放在 user 消息')
  assert.ok(JSON.stringify(anthropicRequest.body).includes('【条件开始】'), '条件放在定界块中')
  const planDetail = await json(owner, 'GET', draftPath)
  assert.equal(planDetail.proposal, null, '生成不得自动写入活动方案')
  const draftTasks = await json(owner, 'GET', `${draftPath}/tasks`)
  assert.equal(draftTasks.items.length, 0, '生成不得自动创建任务')

  // 5. 模型故障与超时：明确失败、保留人工路径、记录错误码
  stub.mode = 'unauthorized'
  const unauthorized = await status(owner, 'POST', `${reviewingPath}/ai/generations`,
    { type: 'ACTIVITY_SUMMARY' }, { 'Idempotency-Key': `p8-401-${stamp}` })
  assert.equal(unauthorized.status, 502, '上游 401 应映射为 AI 上游认证失败')
  assert.equal(unauthorized.body.code, 'AI_UPSTREAM_AUTH', '错误码应区分认证失败')
  const stillLoggedIn = (await call(owner, 'GET', '/api/me/organizations')).status()
  assert.equal(stillLoggedIn, 200, '上游 401 不得导致本站登录失效')
  stub.mode = 'ok'
  const summaryAfterFailure = await json(owner, 'GET', reviewingPath)
  assert.equal(summaryAfterFailure.summary, null, '失败不得写入总结')

  const timeoutConfig = await json(owner, 'PUT', configPath, {
    protocol: 'ANTHROPIC_MESSAGES', endpoint: anthropicEndpoint, model: 'p8-stub-model',
    authScheme: 'X_API_KEY', maxOutputTokens: 128, outputLimitField: 'MAX_TOKENS',
    timeoutSeconds: 5, version: reEnabled.version,
  })
  const timeoutTested = await json(owner, 'POST', `${configPath}/test`, { version: timeoutConfig.version },
    { 'Idempotency-Key': `p8-config-timeout-test-${stamp}` })
  assert.equal(timeoutTested.status, 'SUCCEEDED', '缩短时限后仍应测试通过')
  const timeoutEnabled = await json(owner, 'POST', `${configPath}/enable`, { version: timeoutConfig.version })
  stub.delayMs = 8000
  const startedAt = Date.now()
  const timedOut = await status(owner, 'POST', `${reviewingPath}/ai/generations`,
    { type: 'ACTIVITY_SUMMARY' }, { 'Idempotency-Key': `p8-timeout-${stamp}` })
  const waitedMs = Date.now() - startedAt
  stub.delayMs = 0
  assert.equal(timedOut.status, 504, '超时应返回 504')
  assert.equal(timedOut.body.code, 'AI_TIMEOUT', '超时应给出 AI_TIMEOUT')
  assert.ok(waitedMs < 15000, `超时应按配置时限放弃，实际等待 ${waitedMs}ms`)

  // 6. 停用后保留人工路径
  const disabled = await json(owner, 'POST', `${configPath}/disable`, { version: timeoutEnabled.version })
  assert.equal(disabled.enabled, false, '应能停用')
  const blockedByDisabled = await status(owner, 'POST', `${reviewingPath}/ai/generations`,
    { type: 'ACTIVITY_SUMMARY' }, { 'Idempotency-Key': `p8-disabled-${stamp}` })
  assert.equal(blockedByDisabled.status, 409, '停用后不得生成')
  assert.equal(blockedByDisabled.body.code, 'AI_NOT_ENABLED', '应提示未启用')
  const manualSave = await json(owner, 'PUT', `${reviewingPath}/summary`,
    { summary: '人工编写的总结：活动按计划完成。', version: (await json(owner, 'GET', reviewingPath)).version })
  assert.ok(manualSave.summary.includes('人工编写'), '停用后仍可人工编写总结')
  const reEnabledForUi = await json(owner, 'POST', `${configPath}/enable`, { version: disabled.version })
  assert.equal(reEnabledForUi.enabled, true, '重新启用用于页面核对')

  await mkdir(new URL('../验证截图/', import.meta.url), { recursive: true })

  // 7. 浏览器：配置页、方案生成采用、总结生成与人工修改确认
  const ownerPage = await owner.newPage({ viewport: { width: 1440, height: 1000 } })
  const pageErrors = []
  ownerPage.on('pageerror', (e) => pageErrors.push(e.message))
  const generationRoute = '**/ai/generations'
  async function hideOneGenerationResponse(route) {
    const response = await route.fetch()
    assert.equal(response.status(), 201, '模型应先成功生成，页面才模拟响应丢失')
    await route.fulfill({ status: 503, contentType: 'application/problem+json',
      body: JSON.stringify({ status: 503, code: 'TEST_UNAVAILABLE', detail: '模拟生成响应丢失' }) })
  }
  async function keepPendingGenerationOnLeave(page) {
    const before = page.url()
    await page.getByRole('link', { name: '组织概览' }).click()
    await page.getByRole('dialog', { name: '未保存的修改' }).waitFor()
    await page.getByRole('button', { name: '继续编辑' }).click()
    assert.equal(page.url(), before, '取消离开应保留待核对生成请求')
  }
  await ownerPage.goto(`/org/${org.id}/settings`, { waitUntil: 'networkidle' })
  await ownerPage.waitForSelector('text=AI 模型', { timeout: 15000 })
  await ownerPage.waitForSelector('[data-ai-action="test"]', { timeout: 15000 })
  const settingsBody = await ownerPage.locator('body').innerText()
  const keyInputValue = await fieldInput(ownerPage, 'apiKey').inputValue()
  const shot1 = fileURLToPath(new URL('../验证截图/P8-组织设置-模型配置.png', import.meta.url))
  await ownerPage.screenshot({ path: shot1, fullPage: true })
  shots.push(shot1)

  // 方案生成：填条件 → 生成 → 采用到编辑区 → 人工补充 → 保存方案
  await ownerPage.goto(`/org/${org.id}/activities/${draftActivity.id}?tab=proposal`, { waitUntil: 'networkidle' })
  await ownerPage.locator('[data-ai-type="ACTIVITY_PLAN"]').waitFor({ timeout: 15000 })
  await fillField(ownerPage, 'topic', '迎新晚会（页面核对）')
  await fillField(ownerPage, 'goal', '帮助新成员熟悉组织')
  const planCallsBefore = stub.received.length
  await ownerPage.route(generationRoute, hideOneGenerationResponse)
  await ownerPage.locator('[data-ai-action="generate"]').click()
  await ownerPage.getByRole('button', { name: '核对生成结果' }).waitFor({ timeout: 20000 })
  assert.equal(stub.received.length, planCallsBefore + 1, '响应丢失前应只调用一次模型')
  assert.equal(await fieldInput(ownerPage, 'topic').isDisabled(), true, '结果未知期间应冻结生成条件')
  await keepPendingGenerationOnLeave(ownerPage)
  await ownerPage.unroute(generationRoute, hideOneGenerationResponse)
  await ownerPage.getByRole('button', { name: '核对生成结果' }).click()
  await ownerPage.waitForSelector('[data-ai-output]', { timeout: 20000 })
  assert.equal(stub.received.length, planCallsBefore + 1, '查询已有结果不得再次调用模型')
  // 已采用的旧记录延后返回时，不能覆盖期间新生成记录的编号和采用状态。
  const priorPlan = (await json(owner, 'GET', `${draftPath}/ai/generations?type=ACTIVITY_PLAN`)).items[0]
  const priorAdoptPath = `${draftPath}/ai/generations/${priorPlan.generationId}/adopt`
  let releasePriorAdoption, signalPriorAdoption
  const priorAdoptionGate = new Promise(resolve => { releasePriorAdoption = resolve })
  const priorAdoptionStarted = new Promise(resolve => { signalPriorAdoption = resolve })
  const delayPriorAdoption = async route => {
    const response = await route.fetch()
    assert.equal(response.status(), 200)
    signalPriorAdoption()
    await priorAdoptionGate
    await route.fulfill({ response })
  }
  await ownerPage.route(`**${priorAdoptPath}`, delayPriorAdoption)
  await ownerPage.locator('[data-ai-action="adopt"]').click()
  await priorAdoptionStarted
  const nextPlanResponse = ownerPage.waitForResponse(response => response.url().endsWith(`${draftPath}/ai/generations`)
    && response.request().method() === 'POST')
  await ownerPage.locator('[data-ai-action="generate"]').click()
  const nextResponse = await nextPlanResponse
  assert.equal(nextResponse.status(), 201, '竞态核对的新生成应被受理；测试进程需足够调用额度')
  const nextPlan = await nextResponse.json()
  assert.equal(nextPlan.status, 'SUCCEEDED')
  assert.notEqual(nextPlan.generationId, priorPlan.generationId)
  await ownerPage.waitForFunction(() => !document.querySelector('[data-ai-action="generate"]').classList.contains('is-loading'))
  const firstSuccessApplied = (await ownerPage.locator('[data-ai-action="generate"]').innerText()).trim() === '生成草稿'
  if (!firstSuccessApplied) await ownerPage.getByRole('button', { name: '核对生成结果', exact: true }).click()
  await ownerPage.getByText('草稿仅在本面板中显示', { exact: false }).waitFor()
  await ownerPage.locator('[data-ai-output]').fill('新一轮方案的人工编辑内容')
  const releasedAdoption = ownerPage.waitForResponse(response => response.url().endsWith(priorAdoptPath))
  releasePriorAdoption()
  await releasedAdoption
  await ownerPage.waitForLoadState('networkidle')
  const keptNewGeneration = (await ownerPage.locator('[data-ai-action="adopt"]').innerText()).trim() === '记录为已采用'
  const keptEditedDraft = await ownerPage.locator('[data-ai-output]').inputValue() === '新一轮方案的人工编辑内容'
  const afterAdoption = (await json(owner, 'GET', `${draftPath}/ai/generations?type=ACTIVITY_PLAN`)).items
  assert.ok(afterAdoption.find(item => item.generationId === priorPlan.generationId).adoptedAt)
  assert.equal(afterAdoption.find(item => item.generationId === nextPlan.generationId).adoptedAt, null)
  console.log(JSON.stringify({ firstSuccessApplied, keptNewGeneration, keptEditedDraft, oldAdoptionSaved: true, newPlanUnadopted: true }))
  assert.ok(firstSuccessApplied && keptNewGeneration && keptEditedDraft, '首次成功应直接展示；旧采用响应不得覆盖新记录或人工编辑内容')
  await ownerPage.unroute(`**${priorAdoptPath}`, delayPriorAdoption)
  const freshAdoption = ownerPage.waitForResponse(response => response.url().endsWith(`${draftPath}/ai/generations/${nextPlan.generationId}/adopt`))
  await ownerPage.locator('[data-ai-action="adopt"]').click()
  assert.equal((await freshAdoption).status(), 200, '新一轮记录应可正常采用')
  await ownerPage.getByRole('button', { name: '已采用', exact: true }).waitFor()
  assert.equal(await ownerPage.locator('[data-ai-output]').inputValue(), '新一轮方案的人工编辑内容')
  const adoptionRace = { firstSuccessApplied, keptNewGeneration, keptEditedDraft, oldAdoptionSaved: true, newAdoptionSaved: true }
  const planDraftText = await ownerPage.locator('[data-ai-output]').inputValue()
  await ownerPage.locator('[data-ai-action="apply"]').click()
  await ownerPage.locator('textarea').first().fill(`${planDraftText}\n人工补充：现场安排签到与合影。`)
  await ownerPage.getByRole('button', { name: '保存方案' }).click()
  await ownerPage.waitForTimeout(1200)
  const savedProposal = (await json(owner, 'GET', draftPath)).proposal ?? ''
  assert.ok(savedProposal.includes('人工补充'), '页面方案保存应保留人工补充')
  const shot2 = fileURLToPath(new URL('../验证截图/P8-方案生成与采用.png', import.meta.url))
  await ownerPage.screenshot({ path: shot2, fullPage: true })
  shots.push(shot2)

  // 总结生成：预览输入范围 → 生成 → 采用 → 人工修改 → 保存 → 确认
  await ownerPage.goto(`/org/${org.id}/activities/${reviewing.id}?tab=lifecycle`, { waitUntil: 'networkidle' })
  await ownerPage.waitForSelector('[data-ai-type="ACTIVITY_SUMMARY"]', { timeout: 15000 })
  await ownerPage.locator('[data-ai-action="toggle-scope"]').click()
  await ownerPage.waitForSelector('[data-ai-scope]', { timeout: 10000 })
  const scopeText = await ownerPage.locator('[data-ai-scope]').innerText()
  const summaryCallsBefore = stub.received.length
  await ownerPage.route(generationRoute, hideOneGenerationResponse)
  await ownerPage.locator('[data-ai-action="generate"]').click()
  await ownerPage.getByRole('button', { name: '核对生成结果' }).waitFor({ timeout: 20000 })
  await keepPendingGenerationOnLeave(ownerPage)
  await ownerPage.unroute(generationRoute, hideOneGenerationResponse)
  await ownerPage.getByRole('button', { name: '核对生成结果' }).click()
  await ownerPage.waitForSelector('[data-ai-output]', { timeout: 20000 })
  assert.equal(stub.received.length, summaryCallsBefore + 1, '总结结果核对不得重复调用模型')
  const normalSummaryResponse = ownerPage.waitForResponse(response => response.url().endsWith(`${reviewingPath}/ai/generations`)
    && response.request().method() === 'POST')
  await ownerPage.locator('[data-ai-action="generate"]').click()
  const normalSummaryHttp = await normalSummaryResponse
  assert.equal(normalSummaryHttp.status(), 201)
  const normalSummary = await normalSummaryHttp.json()
  assert.equal(normalSummary.status, 'SUCCEEDED')
  await ownerPage.waitForFunction(() => !document.querySelector('[data-ai-action="generate"]').classList.contains('is-loading'))
  const normalSummaryFirstSuccess = (await ownerPage.locator('[data-ai-action="generate"]').innerText()).trim() === '生成草稿'
  assert.ok(normalSummaryFirstSuccess, '总结首次成功应直接展示，不要求额外核对')
  assert.equal(await ownerPage.locator('[data-ai-output]').inputValue(), normalSummary.output)
  const summaryDraftText = await ownerPage.locator('[data-ai-output]').inputValue()
  assert.ok(summaryDraftText.includes('总结草稿正文'), '页面总结生成应返回总结草稿')
  await ownerPage.locator('[data-ai-action="apply"]').click()
  await ownerPage.locator('[data-ai-action="adopt"]').click()
  await ownerPage.waitForTimeout(600)
  await ownerPage.getByPlaceholder(/目标、执行概况/).fill(`${summaryDraftText}\n人工补充：下次提前一天布置场地。`)
  await ownerPage.getByRole('button', { name: '保存总结' }).click()
  await ownerPage.waitForTimeout(1200)
  await ownerPage.getByRole('button', { name: '确认总结' }).click()
  await ownerPage.waitForTimeout(1200)
  const confirmed = await json(owner, 'GET', reviewingPath)
  assert.ok(confirmed.summaryConfirmedAt, '确认总结后应记录确认时间')
  assert.ok(confirmed.summary.includes('人工补充'), '确认的总结应保留人工补充')
  const shot3 = fileURLToPath(new URL('../验证截图/P8-总结生成与确认.png', import.meta.url))
  await ownerPage.screenshot({ path: shot3, fullPage: true })
  shots.push(shot3)
  const generations = await json(owner, 'GET', `${reviewingPath}/ai/generations?type=ACTIVITY_SUMMARY`)
  const adoptedRow = generations.items.find((item) => item.generationId === summaryGeneration.generationId)
  const sourceChanged = generations.items.some((item) => item.sourceChanged)

  // 8. 成员视图：组织设置只显示可用状态，活动页不出现生成入口
  const memberPage = await member.newPage({ viewport: { width: 1440, height: 1000 } })
  const memberErrors = []
  memberPage.on('pageerror', (e) => memberErrors.push(e.message))
  await memberPage.goto(`/org/${org.id}/settings`, { waitUntil: 'networkidle' })
  await memberPage.waitForSelector('[data-ai-role="member"]', { timeout: 15000 })
  const memberSettingsBody = await memberPage.locator('body').innerText()
  const memberFormVisible = await memberPage.locator('[data-ai-field="endpoint"]').count()
  await memberPage.goto(`/org/${org.id}/activities/${reviewing.id}?tab=lifecycle`, { waitUntil: 'networkidle' })
  await memberPage.getByRole('tab', { name: '执行与复盘' }).waitFor()
  const memberPanels = await memberPage.locator('[data-ai-type="ACTIVITY_SUMMARY"]').count()
  assert.equal(memberPanels, 0, '普通成员不能看到总结生成入口')

  // 9. 窄屏：设置页与活动页在各宽度下不横向溢出
  const widths = []
  for (const width of [1440, 1024, 768, 390, 320]) {
    await ownerPage.setViewportSize({ width, height: 900 })
    await ownerPage.goto(`/org/${org.id}/settings`, { waitUntil: 'networkidle' })
    await ownerPage.waitForSelector('text=AI 模型', { timeout: 15000 })
    const settingsWidth = await ownerPage.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }))
    await ownerPage.goto(`/org/${org.id}/activities/${reviewing.id}?tab=lifecycle`, { waitUntil: 'networkidle' })
    await ownerPage.waitForSelector('[data-ai-type="ACTIVITY_SUMMARY"]', { timeout: 15000 })
    const activityWidth = await ownerPage.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }))
    widths.push({
      width,
      settingsOverflowPx: settingsWidth.scrollWidth - settingsWidth.clientWidth,
      activityOverflowPx: activityWidth.scrollWidth - activityWidth.clientWidth,
    })
  }
  await ownerPage.setViewportSize({ width: 390, height: 900 })
  await ownerPage.waitForTimeout(200)
  const shot4 = fileURLToPath(new URL('../验证截图/P8-窄屏.png', import.meta.url))
  await ownerPage.screenshot({ path: shot4, fullPage: true })
  shots.push(shot4)

  console.log(JSON.stringify({
    check: 'P8 AI 文本辅助真机核对（模型为本机桩服务，真实模型调用形态由协议断言核对）',
    config: {
      initialState: initial.configState,
      enabled: reEnabledForUi.enabled,
      connectionRevision: reEnabledForUi.connectionRevision,
      testedRevision: reEnabledForUi.testedRevision,
      keyEchoedInResponse: savedRaw.includes(API_KEY),
      memberRole: memberRead.viewerRole,
      memberEndpointHidden: memberRead.endpoint === null,
      memberWriteStatus: memberWrite.status,
      outsiderStatus: outsiderConfig.status,
      enableBeforeTestStatus: enableBeforeTest.status,
    },
    connectionTest: {
      status: tested.status,
      testedRevision: tested.testedRevision,
      sentFixedText: testBody.includes('连接正常'),
      sentOrgData: testBody.includes('礼堂'),
    },
    protocols: {
      openai: {
        path: openaiRequest.path,
        authorization: openaiRequest.headers.authorization ? 'Bearer ***' : null,
        stream: openaiRequest.body.stream,
        maxTokens: openaiRequest.body.max_tokens,
        hasDataBlock: JSON.stringify(openaiRequest.body).includes('【资料开始】'),
      },
      anthropic: {
        path: anthropicRequest.path,
        xApiKey: anthropicRequest.headers['x-api-key'] ? '***' : null,
        version: anthropicRequest.headers['anthropic-version'],
        maxTokens: anthropicRequest.body.max_tokens,
        systemSeparated: typeof anthropicRequest.body.system === 'string',
        conditionBlock: JSON.stringify(anthropicRequest.body).includes('【条件开始】'),
      },
    },
    generation: {
      adoptionRace,
      normalSummaryFirstSuccess,
      summaryStatus: summaryGeneration.status,
      summaryOutputMarker: summaryGeneration.output.split('\n')[0],
      replayedSameId: replay.generationId === summaryGeneration.generationId,
      modelCallsAfterReplay: 1,
      adoptedAt: adopted.adoptedAt,
      planStatus: planGeneration.status,
      planOutputMarker: planGeneration.output.split('\n')[0],
      proposalAutoWritten: planDetail.proposal !== null,
      taskAutoCreated: draftTasks.items.length,
      sourceChangedFlagSeen: sourceChanged,
      adoptedRowVisibleInUi: Boolean(adoptedRow?.adoptedAt),
    },
    failures: {
      upstreamAuthStatus: unauthorized.status,
      upstreamAuthCode: unauthorized.body.code,
      sessionStillValidAfterUpstream401: stillLoggedIn === 200,
      timeoutStatus: timedOut.status,
      timeoutCode: timedOut.body.code,
      timeoutWaitedMs: waitedMs,
      disabledCode: blockedByDisabled.body.code,
      manualSummarySaved: manualSave.summary.includes('人工编写'),
    },
    ui: {
      settingsShowsModelSection: settingsBody.includes('AI 模型'),
      settingsShowsState: settingsBody.includes('已启用') || settingsBody.includes('可用'),
      keyInputEmpty: keyInputValue === '',
      planDraftStart: planDraftText.split('\n')[0],
      proposalSavedWithHumanEdit: savedProposal.includes('人工补充'),
      summaryDraftStart: summaryDraftText.split('\n')[0],
      scopePreview: scopeText.split('\n').slice(0, 3),
      summarySavedWithHumanEdit: (confirmed.summary ?? '').includes('人工补充'),
      summaryConfirmedAt: confirmed.summaryConfirmedAt,
      memberSettingsRoleNote: memberSettingsBody.includes('组织成员'),
      memberFormControls: memberFormVisible,
      memberPanels: memberPanels,
    },
    narrowWidths: widths,
    browserErrors: pageErrors,
    memberViewErrors: memberErrors,
    screenshots: shots.map((p) => p.split('验证截图').pop()),
  }, null, 1))
  }
} finally {
  await browser.close()
  if (stubServer.listening) {
    stubServer.closeAllConnections?.()
    await new Promise((resolve) => stubServer.close(resolve))
  }
}
