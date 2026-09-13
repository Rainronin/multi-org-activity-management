// 22cff8b 独立进度复核：只对明确连接 activity_test 的审核服务运行；新增专用账号与数据。
import assert from 'node:assert/strict'
import { chromium } from '../UI演示/node_modules/playwright/index.mjs'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
if (process.env.AUDIT_TEST_DATABASE !== 'activity_test') throw new Error('先确认实际后端测试库')
const browser = await chromium.launch({channel:'chrome',headless:true})
try {
 const base='http://127.0.0.1:5174', stamp=Date.now(), password=`Audit${stamp}pass`
 const owner=await browser.newContext({baseURL:base,viewport:{width:1440,height:1000}})
 const outsider=await browser.newContext({baseURL:base,viewport:{width:1440,height:1000}})
 async function call(ctx,method,path,data,key,multipart){
  const headers={};if(key)headers['Idempotency-Key']=key
  if(method!=='GET'){const c=await(await ctx.request.get('/api/auth/csrf')).json();headers[c.headerName]=c.token}
  return ctx.request.fetch(path,{method,headers,...(data===undefined?{}:{data}),...(multipart?{multipart}:{})})
 }
 async function json(ctx,method,path,data,key){const r=await call(ctx,method,path,data,key);assert.ok(r.ok(),`准备失败 ${method} ${path} ${r.status()}`);return r.json()}
 for(const [i,ctx]of [owner,outsider].entries()){
  const username=`dsreview${stamp}u${i}`;await json(ctx,'POST','/api/auth/register',{username,password})
  const c=await(await ctx.request.get('/api/auth/csrf')).json();assert.equal((await ctx.request.post('/api/auth/login',{form:{username,password},headers:{[c.headerName]:c.token}})).status(),204)
 }
 const org=await json(owner,'POST','/api/organizations',{name:`DS审核${stamp}`})
 const mid=(await json(owner,'GET',`/api/organizations/${org.id}/members`)).items[0].memberId
 const path=`/api/organizations/${org.id}/activities`
 const body={name:'外部参与真实审核',description:'公开介绍',location:'会议室',ownerMemberId:mid,registrationScope:'PUBLIC',capacity:8,startTime:new Date(Date.now()+86400000).toISOString(),endTime:new Date(Date.now()+90000000).toISOString(),registrationStart:new Date(Date.now()-3600000).toISOString(),registrationEnd:new Date(Date.now()+80000000).toISOString()}
 const a=await json(owner,'POST',path,body,`ds-${stamp}`), ap=`${path}/${a.id}`
 await json(owner,'PUT',`${ap}/proposal`,{content:'方案第一版',version:(await json(owner,'GET',ap)).version})
 const fresh=await json(owner,'GET',ap)
 await json(owner,'PATCH',ap,{location:'已更新地点',version:fresh.version})
 const missingVersion=await call(owner,'PATCH',ap,{location:'没有版本仍覆盖'})
 const oldProposal=await call(owner,'PUT',`${ap}/proposal`,{content:'旧页面覆盖方案',version:0})
 const replay=await call(owner,'POST',path,body,`ds-${stamp}`)
 console.log(JSON.stringify({check:'S08剩余',missingVersion:missingVersion.status(),staleProposal:oldProposal.status(),creationReplayAfterEdit:replay.status()}))
 const initialTask=await call(owner,'POST',`${ap}/tasks`,{title:'初始草稿任务',assigneeMemberId:mid})
 console.log(JSON.stringify({check:'初始草稿创建任务',status:initialTask.status()}))
 await json(owner,'POST',`${ap}/submit`);await json(owner,'POST',`${ap}/approve`,{version:(await json(owner,'GET',ap)).version});await json(owner,'POST',`${ap}/registration-open`,{open:true})
 const page=await outsider.newPage(), errors=[];page.on('pageerror',e=>errors.push(e.message))
 await page.goto(`/public/activities/${a.id}`,{waitUntil:'networkidle'})
 // F01：登录后的外部参与者在本页报名并留在公开页（原缺陷是被送进内部工作区）
 await page.getByRole('button',{name:'我要报名'}).click()
 await page.getByPlaceholder('报名姓名').fill('外部参与者')
 await page.locator('.el-dialog').getByRole('button',{name:'提交'}).click()
 await page.waitForSelector('text=待审核',{timeout:15000})
 console.log(JSON.stringify({check:'无组织外部账号报名入口',url:new URL(page.url()).pathname,stayedOnPublicPage:new URL(page.url()).pathname.startsWith('/public/'),applicationFormCount:await page.getByPlaceholder('报名姓名').count(),bodyShowsNoOrg:(await page.locator('body').innerText()).includes('组织')}))
 await page.reload({waitUntil:'networkidle'})
 await page.waitForSelector('text=我的报名',{timeout:15000})
 const mineNow=await json(outsider,'GET','/api/me/registrations')
 console.log(JSON.stringify({check:'本人报名列表',hasActivityName:(await page.locator('body').innerText()).includes(body.name),activityNameField:mineNow.items[0]&&mineNow.items[0].activityName,hasSchedule:Boolean(mineNow.items[0]&&mineNow.items[0].startTime),cancelButtons:await page.getByRole('button',{name:'取消报名',exact:true}).count()}))
 await mkdir(new URL('../验证截图/',import.meta.url),{recursive:true})
 await page.screenshot({path:fileURLToPath(new URL('../验证截图/DS审核-外部账号.png',import.meta.url)),fullPage:true})
 const ownerPage=await owner.newPage();await ownerPage.goto(`/org/${org.id}/activities/${a.id}`,{waitUntil:'networkidle'})
 const viewports=[]
 for(const width of [1440,1024,768,390,320]){
  await ownerPage.setViewportSize({width,height:800});await ownerPage.waitForTimeout(120)
  viewports.push(await ownerPage.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,overflow:document.documentElement.scrollWidth>innerWidth})))
 }
 console.log(JSON.stringify({check:'P3P4页面宽度',viewports}))
 await ownerPage.screenshot({path:fileURLToPath(new URL('../验证截图/DS审核-320宽.png',import.meta.url)),fullPage:true})
 const upload=await call(owner,'POST',`${ap}/attachments`,undefined,`ds-att-${stamp}`,{file:{name:'审核.txt',mimeType:'text/plain',buffer:Buffer.from('这是一份正常文本。')}})
 const att=await upload.json();assert.equal(upload.status(),201)
 const download=await call(owner,'GET',`${ap}/attachments/${att.attachmentId}/download`)
 await json(owner,'POST',`${ap}/withdraw-approval`,{reason:'测试调整期',version:(await json(owner,'GET',ap)).version})
 const del=await call(owner,'DELETE',`${ap}/attachments/${att.attachmentId}`)
 await json(owner,'POST',`${ap}/submit`)
 const pendingTask=await call(owner,'POST',`${ap}/tasks`,{title:'重新提交后不应可写',assigneeMemberId:mid})
 console.log(JSON.stringify({check:'调整期重新提交后的冻结',createTask:pendingTask.status()}))
 const mine=await json(outsider,'GET','/api/me/registrations')
 console.log(JSON.stringify({check:'调整期与文件',deleteWhileAdjusting:del.status(),downloadDisposition:download.headers()['content-disposition'],myRegistrationFields:Object.keys(mine.items[0]),pageErrors:errors}))
 console.log(JSON.stringify({check:'隔离',database:'activity_test',accounts:2,organizations:1,uploads:'验证截图/audit-ds-files'}))
}finally{await browser.close()}
