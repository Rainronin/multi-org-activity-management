// P0.3运行日志：真实HTTP出口、请求标识、脱敏及gzip轮转；不创建业务数据、不调用模型。
// 测试后端需仅连接activity_test，日志路径由AUDIT_RUNTIME_LOG_FILE指定。
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFile, readdir, writeFile } from 'node:fs/promises'
import { basename, dirname, join } from 'node:path'
import { gunzipSync } from 'node:zlib'
import { setTimeout as delay } from 'node:timers/promises'

assert.equal(process.env.AUDIT_TEST_DATABASE, 'activity_test', '只允许独立测试库')
const logFile = process.env.AUDIT_RUNTIME_LOG_FILE
assert.ok(logFile, '必须指定本次测试服务的日志文件')
const base = 'http://127.0.0.1:8080'
const secret = `DO_NOT_LOG_${randomUUID()}`
const checks = []

async function readLogs() {
  const names = (await readdir(dirname(logFile))).filter(name => name === basename(logFile)
    || (name.startsWith(`${basename(logFile)}.`) && name.endsWith('.gz')))
  const texts = await Promise.all(names.map(async name => {
    const bytes = await readFile(join(dirname(logFile), name))
    return (name.endsWith('.gz') ? gunzipSync(bytes) : bytes).toString('utf8')
  }))
  return { text: texts.join('\n'), archives: names.filter(name => name.endsWith('.gz')).length }
}

const startup = await readLogs()
assert.match(startup.text, /Database: jdbc:mysql:\/\/[^\s]+\/activity_test[?\s]/, '启动日志必须确认真实activity_test目标')

async function request(path, options, status, code, route) {
  const response = await fetch(`${base}${path}`, options)
  assert.equal(response.status, status)
  const requestId = response.headers.get('x-request-id')
  assert.match(requestId ?? '', /^[\da-f-]{36}$/)
  assert.notEqual(requestId, secret)
  if (status >= 400) {
    const problem = await response.json()
    assert.equal(problem.code, code)
    assert.equal(problem.requestId, requestId)
  } else {
    await response.text()
  }
  checks.push({ requestId, status, code, route, method: options?.method ?? 'GET' })
  return response
}

await request(`/api/health?password=${secret}`, { headers: { Cookie: `private=${secret}`, 'X-Request-Id': secret } }, 200, 'NONE', '/api/health')
await request(`/api/organizations/${secret}`, {}, 401, 'AUTH_REQUIRED', 'UNMAPPED')
await request('/api/auth/recover', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ recoveryCode: secret }) }, 403, 'CSRF_INVALID', '/api/auth/recover')
await request(`/api/public/activities/${secret}`, {}, 400, 'VALIDATION_FAILED', '/api/public/activities/{activityId}')
await request(`/api/public/${secret}`, {}, 404, 'RESOURCE_NOT_FOUND', '/**')

const csrfResponse = await fetch(`${base}/api/auth/csrf`)
assert.equal(csrfResponse.status, 200)
const csrf = await csrfResponse.json()
const cookie = csrfResponse.headers.getSetCookie().map(value => value.split(';')[0]).join('; ')
for (let attempt = 0; attempt < 11; attempt++) {
  await request('/api/auth/login', { method: 'POST', headers: {
    Cookie: cookie, [csrf.headerName]: csrf.token, 'Content-Type': 'application/x-www-form-urlencoded',
  }, body: new URLSearchParams({ username: secret, password: secret }) },
  attempt < 10 ? 401 : 429, attempt < 10 ? 'AUTH_INVALID_CREDENTIALS' : 'RATE_LIMITED', '/api/auth/login')
}
// 本次服务可临时降低max-file-size触发轮转，正式配置仍为10MB。
for (let index = 0; index < 24; index++) await request('/api/health', {}, 200, 'NONE', '/api/health')
await delay(1000) // 等待Logback异步压缩完成后读取静止文件。
const logs = await readLogs()
assert.ok(logs.archives > 0, '应产生真实可解压的gzip轮转文件')
assert.ok(!logs.text.includes(secret), '日志不得含路径、参数、Cookie、正文或凭据样本')
const lines = logs.text.split(/\r?\n/)
for (const check of checks) {
  const matches = lines.filter(line => line.includes(`[${check.requestId}]`) && line.includes('durationMs='))
  assert.equal(matches.length, 1, '每次请求应有且只有一条完成日志')
  const line = matches[0]
  assert.ok(line.includes(check.status >= 400 ? ' WARN ' : ' INFO '))
  assert.ok(line.includes(`method=${check.method} route=${check.route} `))
  assert.match(line, /durationMs=\d+/)
  assert.ok(line.includes(`status=${check.status} code=${check.code}`))
}
const result = { checkedAt: new Date().toISOString(), database: 'activity_test', checks: checks.length,
  gzipArchives: logs.archives, sensitiveSampleExcluded: true,
  limits: '轮转阈值由测试服务临时降低；500及过滤器链异常见ErrorContractTest；未等待14天或填满200MB。' }
await writeFile(new URL('../验证截图/持续集中-运行日志结果.json', import.meta.url), JSON.stringify(result, null, 2))
console.log(JSON.stringify(result))
