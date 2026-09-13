// P9.3 生产构建与嵌套路由刷新核对：用最小静态服务器按前端部署约定提供 frontend/dist
// （未知路径回退 index.html，/api 反向代理到 8080），验证构建产物可独立运行、嵌套路由可直接刷新、
// 深链接在未登录时按路由守卫进入登录页。仅用于本机核对，不替代正式反向代理配置。
import assert from 'node:assert/strict'
import { createServer, request as httpRequest } from 'node:http'
import { readFile, stat } from 'node:fs/promises'
import { extname, join, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'

const distDir = fileURLToPath(new URL('../基于Vue3的多组织活动管理系统_zcode/frontend/dist/', import.meta.url))
const apiTarget = { host: '127.0.0.1', port: 8080 }
const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.json': 'application/json; charset=utf-8',
  '.woff2': 'font/woff2',
}

function proxy(req, res) {
  const upstream = httpRequest({
    host: apiTarget.host, port: apiTarget.port, path: req.url, method: req.method, headers: req.headers,
  }, (upstreamRes) => {
    res.writeHead(upstreamRes.statusCode, upstreamRes.headers)
    upstreamRes.pipe(res)
  })
  upstream.on('error', () => {
    res.writeHead(502, { 'Content-Type': 'application/json' })
    res.end('{"code":"UPSTREAM_UNAVAILABLE"}')
  })
  req.pipe(upstream)
}

const server = createServer(async (req, res) => {
  if (req.url.startsWith('/api')) {
    proxy(req, res)
    return
  }
  const pathname = decodeURIComponent(req.url.split('?')[0])
  const candidate = join(distDir, normalize(pathname).replace(/^(\.\.[/\\])+/, ''))
  let filePath = candidate
  try {
    const info = await stat(candidate)
    if (info.isDirectory()) {
      filePath = join(candidate, 'index.html')
    }
  } catch {
    // 未知路径按 SPA 约定回退到 index.html（嵌套路由刷新依赖该回退）
    filePath = join(distDir, 'index.html')
  }
  try {
    const body = await readFile(filePath)
    res.writeHead(200, { 'Content-Type': CONTENT_TYPES[extname(filePath)] ?? 'application/octet-stream' })
    res.end(body)
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
    res.end('not found')
  }
})
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
const base = `http://127.0.0.1:${server.address().port}`

const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const indexHtml = await (await fetch(`${base}/`)).text()
  assert.ok(indexHtml.includes('<div id="app">'), 'index.html 应包含应用挂载点')
  const assetMatch = indexHtml.match(/src="(\/assets\/[^"]+\.js)"/)
  assert.ok(assetMatch, 'index.html 应引用构建后的哈希资源')
  const assetResponse = await fetch(`${base}${assetMatch[1]}`)
  assert.equal(assetResponse.status, 200, '构建资源应可访问')
  assert.ok((assetResponse.headers.get('content-type') ?? '').includes('javascript'),
    '构建资源应返回 JavaScript 内容类型')

  // 嵌套路由直接刷新：静态服务器回退到 index.html，前端路由接管
  const nestedPath = '/org/1/activities/1'
  const nestedResponse = await fetch(`${base}${nestedPath}`)
  assert.equal(nestedResponse.status, 200, '嵌套路由刷新应返回 200')
  const nestedHtml = await nestedResponse.text()
  assert.ok(nestedHtml.includes('<div id="app">'), '嵌套路由应返回应用入口')
  const deepPath = '/public/activities/1'
  const deepHtml = await (await fetch(`${base}${deepPath}`)).text()

  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  await page.goto(`${base}${nestedPath}`, { waitUntil: 'networkidle' })
  const guardUrl = page.url()
  const body = await page.locator('body').innerText()
  await page.goto(`${base}/public/activities`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)
  const publicBody = await page.locator('body').innerText()
  const apiProxied = await (await fetch(`${base}/api/health`)).json()

  console.log(JSON.stringify({
    check: 'P9.3 生产构建与嵌套路由刷新核对（最小静态服务器 + /api 反向代理）',
    build: {
      indexHasMountPoint: indexHtml.includes('<div id="app">'),
      hashedAsset: assetMatch[1],
      assetStatus: assetResponse.status,
      assetContentType: assetResponse.headers.get('content-type'),
    },
    nestedRoute: {
      path: nestedPath,
      status: nestedResponse.status,
      servedAppShell: nestedHtml.includes('<div id="app">'),
      guardRedirectedToLogin: guardUrl.includes('/login'),
      loginFormRendered: /登录|进入工作区/.test(body),
    },
    deepLink: {
      path: deepPath,
      servedAppShell: deepHtml.includes('<div id="app">'),
      publicListRendered: publicBody.includes('公开活动'),
    },
    apiProxy: { status: apiProxied.status, database: apiProxied.database },
    browserErrors: errors,
  }, null, 1))
} finally {
  await browser.close()
  server.closeAllConnections?.()
  await new Promise((resolve) => server.close(resolve))
}
