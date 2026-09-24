// 标准页只读回归：真实账号与接口；状态反例仅在独立浏览器中拦截，不写业务库。
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { mkdir, writeFile } from 'node:fs/promises'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
const base = process.env.UI_BASE || 'http://127.0.0.1:5174'
const out = new URL('../验证截图/UI标准页/', import.meta.url)
await mkdir(out, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const results = { checks: [], screenshots: [], limitations: ['状态反例为浏览器响应替身，不代表业务验收', '未调用厂商模型、未执行后端测试或业务写入'] }
try {
  const ctx = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } })
  const csrf = await (await ctx.request.get('/api/auth/csrf')).json()
  assert.equal((await ctx.request.post('/api/auth/login', { form: { username: process.env.UI_AUDIT_USER || 'demo_owner_yrnn', password: process.env.UI_AUDIT_PASS || 'Demo12345!' }, headers: { [csrf.headerName]: csrf.token } })).status(), 204)
  const org = (await (await ctx.request.get('/api/me/organizations')).json()).items[0]
  const activities = (await (await ctx.request.get(`/api/organizations/${org.id}/activities?pageSize=50`)).json()).items
  const detail = activities.find(a => a.status === 'APPROVED')
  const draft = activities.find(a => a.status === 'DRAFT')
  assert.ok(org && detail && draft, '需要既有组织、筹备活动及草稿样例')
  const home = `/org/${org.id}`, detailUrl = `${home}/activities/${detail.id}`
  const page = await ctx.newPage()
  const errors = []
  page.on('pageerror', e => errors.push(e.message))
  async function shot(name, fullPage = false) { await page.screenshot({ path: fileURLToPath(new URL(name+'.png', out)), fullPage }); results.screenshots.push(name+'.png') }
  async function visible(selector) { await page.locator(selector).first().waitFor({state:'visible'}) }
  await page.goto(home, {waitUntil:'networkidle'})
  await visible('[data-focus-activity]')
  const dimensions = await page.evaluate(() => {
    const size = s => parseFloat(getComputedStyle(document.querySelector(s)).fontSize)
    const box = s => document.querySelector(s).getBoundingClientRect().toJSON()
    return { title: size('.spotlight h2'), ai: size('.assistant-card h2'), ring: box('.budget-chart__ring'), label: box('.budget-chart__label'), kpi: box('.kpi-card') }
  })
  assert.ok(dimensions.title > dimensions.ai, '活动主标题必须比 AI 标题突出')
  assert.ok(await page.locator('.budget-chart__label').evaluate(e => e.tagName === 'SPAN'))
  assert.ok(dimensions.kpi.height < 180, '桌面 KPI 应为紧凑信息带')
  await shot('工作台-1440')
  await shot('工作台-完整', true)
  const desktopFocus = await page.locator('.spotlight').boundingBox()
  await page.locator('.spotlight').hover()
  await page.waitForTimeout(300)
  assert.ok((await page.locator('.spotlight').boundingBox()).y < desktopFocus.y, '悬停应浮起而非下沉')
  await page.getByRole('button', { name: '折叠侧栏', exact: true }).click()
  await page.waitForTimeout(300)
  await shot('工作台-折叠')
  const widths=[]
  for(const width of [320,390,768,1024,1440]) {
    await page.setViewportSize({width,height:1000})
    await page.waitForTimeout(300)
    const data=await page.evaluate(()=>({ overflow:document.documentElement.scrollWidth-innerWidth, labels:[...document.querySelectorAll('.u-item .u-label')].map(e=>({display:getComputedStyle(e).display,width:e.getBoundingClientRect().width})), token:getComputedStyle(document.querySelector('.workspace')).getPropertyValue('--ui-nav-brand-display').trim() }))
    assert.equal(data.overflow,0, `工作台 ${width} 溢出`)
    assert.ok(data.token, '跨视口 token 必须有值')
    if(width<=390) { assert.ok(data.labels.every(x=>x.display!=='none' && x.width>0)); assert.ok(await page.locator('.el-select__placeholder').first().isVisible()) }
    widths.push({width,...data})
    if(width===390) await shot('工作台-390',true)
  }
  assert.equal(await page.locator('.workspace').getAttribute('data-nav-collapsed'),'true','手机适配不能丢失桌面折叠选择')
  await page.getByRole('button',{name:'展开侧栏',exact:true}).click()
  await page.setViewportSize({width:1440,height:1000})
  const requested=[]
  page.on('request',r=>{if(r.url().includes('/api/'))requested.push(r.url())})
  await page.goto(detailUrl+'?tab=overview',{waitUntil:'networkidle'})
  await visible('.detail-tabs')
  assert.ok(!requested.some(u=>/\/(tasks|materials|expenses|registrations)(?:\?|$)/.test(u)), '未打开业务页签不能提前取数')
  await shot('活动详情-概览')
  await page.getByRole('tab',{name:'方案',exact:true}).click()
  await page.waitForURL('**tab=proposal')
  await page.reload({waitUntil:'networkidle'})
  assert.equal(await page.getByRole('tab',{name:'方案',exact:true}).getAttribute('aria-selected'),'true')
  await page.getByRole('tab',{name:'概览',exact:true}).click()
  await page.waitForURL('**tab=overview')
  await page.goBack({waitUntil:'networkidle'})
  assert.equal(await page.getByRole('tab',{name:'方案',exact:true}).getAttribute('aria-selected'),'true')
  await page.goForward({waitUntil:'networkidle'})
  await page.getByRole('tab',{name:'任务与物资',exact:true}).click()
  await page.waitForTimeout(500)
  await shot('活动详情-任务物资')
  for(const width of [320,390,768,1024,1440]) {
    await page.setViewportSize({width,height:1000})
    await page.waitForTimeout(350)
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth-innerWidth),0,`详情 ${width} 溢出`)
    if(width===390) await shot('活动详情-390',true)
  }
  await page.goto(detailUrl+'?tab=invalid',{waitUntil:'networkidle'})
  await page.waitForURL('**tab=overview')
  await page.goto(`${home}/activities/${draft.id}?tab=proposal`,{waitUntil:'networkidle'})
  await page.getByRole('button', { name: '编辑信息', exact: true }).click()
  const editor = page.getByRole('dialog', { name: '编辑活动信息' })
  const nameField = editor.getByLabel('活动主题')
  await nameField.fill('')
  await editor.getByLabel('活动简介').click()
  await editor.getByText('请输入活动主题', { exact: true }).waitFor({ state: 'visible' })
  await editor.getByLabel('预算（元）').fill('1.234')
  await nameField.click()
  await editor.getByText('请输入非负金额，最多两位小数', { exact: true }).waitFor({ state: 'visible' })
  await page.screenshot({ path: fileURLToPath(new URL('活动详情-失焦校验.png', out)) })
  await editor.getByRole('button', { name: /关闭|Close/ }).click()
  await page.locator('.proposal-section textarea').first().fill('未保存草稿核对，不提交')
  await page.locator('.u-item').first().click()
  await visible('.el-message-box')
  await page.getByRole('button',{name:'继续编辑',exact:true}).click()
  assert.ok(page.url().includes(draft.id))
  assert.equal(await page.locator('.proposal-section textarea').first().inputValue(),'未保存草稿核对，不提交')
  // 关闭该页面释放未保存状态，不执行业务保存。
  await page.close()
  const states=await ctx.newPage()
  await states.emulateMedia({reducedMotion:'reduce'})
  await states.goto(home,{waitUntil:'networkidle'})
  await states.locator('[data-focus-activity]').waitFor({state:'visible'})
  assert.equal(await states.locator('.spotlight').evaluate(e=>getComputedStyle(e).transitionDuration),'0s')
  let release;const gate=new Promise(resolve=>{release=resolve})
  await states.route('**/workbench?*',async route=>{await gate;await route.continue()})
  await states.goto(home,{waitUntil:'domcontentloaded'})
  await states.locator('.workbench__skeleton').waitFor({state:'visible'})
  assert.equal(await states.locator('[data-focus-activity]').count(),0)
  await states.screenshot({path:fileURLToPath(new URL('工作台-加载.png', out))})
  release();await states.waitForLoadState('networkidle');await states.unrouteAll()
  await states.route('**/workbench?*',route=>route.fulfill({status:503,contentType:'application/problem+json',body:JSON.stringify({status:503,code:'UNAVAILABLE',detail:'状态核对'})}))
  await states.goto(home,{waitUntil:'networkidle'});await states.locator('.workbench__error').waitFor({state:'visible'})
  assert.equal(await states.locator('[data-focus-activity]').count(),0)
  await states.screenshot({path:fileURLToPath(new URL('工作台-失败.png', out))})
  await states.unrouteAll()
  // 空态与成员视图是响应替身，仅证明前端不会伪造经费或 AI 管理内容。
  await states.route('**/workbench?*',route=>route.fulfill({json:{role:'MEMBER',cards:[],todos:[],myCards:[{key:'my-registrations',label:'我的有效报名',value:'2',hint:'本人报名',detailPath:'/',detailLabel:'我的报名'}]}}))
  await states.goto(home,{waitUntil:'networkidle'})
  assert.equal(await states.locator('[data-ai-card]').count(),0)
  assert.equal(await states.locator('[data-chart-panel]').count(),0)
  assert.equal(await states.locator('.kpi-card').count(),1)
  assert.ok(!(await states.locator('[data-kpi-band]').innerText()).includes('支出'))
  await states.screenshot({path:fileURLToPath(new URL('工作台-成员状态替身.png', out))})
  assert.deepEqual(errors,[])
  const demo=await browser.newPage({viewport:{width:1440,height:1000}})
  await demo.goto('http://127.0.0.1:5173',{waitUntil:'networkidle'});await demo.waitForTimeout(1200)
  await demo.screenshot({path:fileURLToPath(new URL('Demo-1440.png', out))})
  results.checks.push('真实工作台与详情读取、五档溢出、手机标签与组织名、折叠往返、上浮方向、减少动效、页签刷新与历史、非法页签、懒加载、未保存离开拦截、编辑表单失焦校验、加载失败互斥、成员指标不冒充经费')
  results.dimensions=dimensions;results.widths=widths;results.orgId=org.id;results.detailId=detail.id
  await writeFile(new URL('结果.json',out),JSON.stringify(results,null,2))
  const pairs=[['Demo','Demo-1440.png'],['正式工作台','工作台-1440.png'],['活动详情','活动详情-概览.png'],['任务与物资','活动详情-任务物资.png'],['手机工作台','工作台-390.png'],['手机详情','活动详情-390.png']]
  await writeFile(new URL('标准页对照.html',out),`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>标准页还原对照</title><style>body{font:16px system-ui;margin:24px;background:#f3f3ef;color:#1a1814}main{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:24px}figure{margin:0}img{width:100%;vertical-align:top}figcaption{padding:12px;font-weight:bold}p{line-height:1.7}@media(max-width:700px){main{grid-template-columns:1fr}}</style><h1>标准页还原 · 待你确认</h1><p>Demo 与正式页面均为 1440×1000。正式页使用既有真实数据；保留独立详情路由与业务页签，不伪造趋势。手机截图为 390 宽完整页面。</p><main>${pairs.map(([label,file])=>`<figure><figcaption>${label}</figcaption><img src="${file}" alt="${label}"></figure>`).join('')}</main></html>`)
  console.log(JSON.stringify(results,null,2))
} finally { await browser.close() }
