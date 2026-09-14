# UI 还原实施证据（第一轮：U01—U04 与 U06 前半）

用途：把本轮 UI 还原的**改动、可复现命令、真机核对输出与截图位置**集中成一份可交给独立审核的材料。审核结论仍以[开发进度审核报告](开发进度审核报告.md)的 U01—U06 为准；未完成项在第四节明确列出，不代表已通过视觉验收。

审核基准：`b767f60`；本轮提交：`51e65af`（第 1 步 工作台／导航）、`0690f49`（第 2／3 步 活动详情／登录品牌／检查规则／对照页）。工作区干净，后端未改动。

## 一、环境与数据（可自行复现）

| 项 | 值 |
| --- | --- |
| 正式前端 | `http://127.0.0.1:5174`（Vite dev；生产构建已验证见部署说明） |
| 已确认 Demo | `http://127.0.0.1:5173`（`UI演示`，`npm run dev`） |
| 后端 | `http://127.0.0.1:8080`，显式连接测试库 `activity_test` |
| 演示账号 | `demo_owner_yrnn` / `Demo12345!`（组织「演示组织 yrnn」，id 4922） |
| 视口 | 1440×1000（桌面）、390×844（手机） |

## 二、逐项对照（U01—U06）

| 编号 | 审核要求 | 本轮结果 | 证据 |
| --- | --- | --- | --- |
| U01 信息架构 | 深色重点活动卡＋暖橙 AI 卡 → 精简 KPI → 图表 → 明细 | **已还原** | `WorkbenchSection.vue` 重写；真机结构断言：重点卡 1、AI 卡 1、KPI 4、图表面板 1、阶段轨 6；`OrgOverviewPage.vue` 组织资料降为工作台之后的紧凑信息带（截图 `UI核对-工作台-1440.png`） |
| U02 卡片与层级 | 单项卡片边界、等宽数字、标题／说明层级、口径下移 | **已还原** | KPI 卡（图标＋标签＋数据字体数值＋口径说明）、明细面板独立卡片、经费环图与进度条；KPI 数值与工作台接口逐项一致（1／0／1／328.50）；无趋势接口不画曲线 |
| U03 活动详情 | 统一详情布局，不再纵向堆叠 | **已还原** | `ActivityDetailPage.vue`：状态首屏（当前状态＋下一步说明＋负责人／开始／地点／预算）＋六个业务页签（概览／方案／任务与物资／报名／经费／执行与复盘，按状态权限显隐）；真机：页签 6 个、切换有效、**页高 2708px → 856px**（1440×1000 同一活动），截图 `UI核对-活动详情-1440.png` |
| U04 侧栏与品牌 | 分组／层级、紧凑圆角项、品牌标识 | **已还原** | `OrganizationLayout.vue`：组织工作区分组标题＋圆角药丸子项（`aria-expanded`／`aria-controls` 完整）；`AuthLayout.vue` 品牌标识与侧栏、Demo 同为倾斜线条 SVG；真机侧栏折叠 `data-nav-collapsed=true`（截图 `UI核对-侧栏折叠.png`、`UI核对-登录-1440.png`） |
| U05 质感与加载节奏 | 玻璃质感、卡片悬停、错峰进入、骨架层级 | **部分完成** | 工作台与活动详情已是同构骨架屏；卡片悬停上浮＋token 过渡，`prefers-reduced-motion` 下过渡时长 0s（真机测得 `0s`）。**成员／组织设置／公开参与页仍是文字加载提示与朴素列表**，未统一 |
| U06 检查与验收 | 修检查规则脆弱点并留反例；视觉验收需同视口并排审查 | **前半完成** | `check:ui` 组件 props 正则原含异常控制字符（`\\u0008` 取代了 `\\s`，只命中多行标签），已修复并新增 **14 条规则自检**（含 SVG 豁免与表格列宽例外）；修复后立即命中 `OrgMembersPage` 5 处列宽字面量并按例外口径留档。同视口 Demo／正式对照页已生成（见第三节）。**视觉基线结论待审核方给出** |

## 三、证据文件位置（截图目录不入库，按需附给审核）

| 材料 | 路径 |
| --- | --- |
| 并排对照页（工作台／登录＋其他截图索引） | `验证截图/UI独立审核/对照-正式与演示.html` |
| 正式工作台 1440／390 | `验证截图/UI核对-工作台-1440.png`、`UI核对-工作台-390.png` |
| 正式活动详情（页签化） | `验证截图/UI核对-活动详情-1440.png` |
| 正式侧栏折叠（图标岛） | `验证截图/UI核对-侧栏折叠.png` |
| 正式登录 | `验证截图/UI核对-登录-1440.png` |
| Demo 参考截图（本机同视口实拍） | `验证截图/UI独立审核/演示-工作台-1440.png`、`演示-登录-1440.png` |
| 真机核对输出（机器可读） | `验证截图/UI独立审核/ui核对输出.json` |

## 四、真机核对输出（原文）

``json
{
 "check": "工作台视觉还原核对（Demo 阅读顺序：重点卡＋AI 卡 → KPI → 图表 → 明细／待办）",
 "org": {
  "id": "4922",
  "name": "演示组织 yrnn"
 },
 "structure": {
  "spotlight": 1,
  "assistant": 1,
  "kpis": 4,
  "chart": 1,
  "details": 1,
  "todos": 1,
  "stageRail": 6
 },
 "spotlightTitle": "演示活动·公开报名",
 "kpiValues": {
  "activities-in-progress": "1",
  "tasks-open": "0",
  "registrations-effective": "1",
  "expense-approved": "328.50"
 },
 "checks": {
  "kpiMatchesApi": {
   "activities": true,
   "tasks": true,
   "registrations": true,
   "expense": true
  }
 },
 "detail": {
  "status": "APPROVED",
  "tabLabels": [
   "概览",
   "方案",
   "任务与物资",
   "报名",
   "经费",
   "执行与复盘"
  ],
  "switchedTab": "方案",
  "statusHintShown": true,
  "bodyHeightPx": 856,
  "overflowPx": 0,
  "pageErrors": []
 },
 "shell": {
  "navCollapsedAttr": "true",
  "reducedMotionTransitionDuration": "0s"
 },
 "demoComparison": {
  "demoReachable": true,
  "demoErrors": [],
  "html": "验证截图/UI独立审核/对照-正式与演示.html"
 },
 "overflowPx": 0,
 "mobileOverflowPx": 0,
 "pageErrors": [],
 "screenshots": [
  "验证截图/UI核对-工作台-1440.png",
  "验证截图/UI核对-工作台-390.png"
 ]
}
``

## 五、复现命令

``powershell
# 启动：后端（测试库）、正式前端、Demo 参考
cd .\基于Vue3的多组织活动管理系统_zcode\backend; .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
cd .\基于Vue3的多组织活动管理系统_zcode\frontend; npm run dev
cd .\UI演示; npm run dev

# 真机核对（结构、KPI 一致性、详情页签与高度、侧栏折叠、减少动效、窄屏溢出、页面错误）
node .\生成脚本\audit_ui_workbench.mjs
# 静态门禁与构建
cd .\基于Vue3的多组织活动管理系统_zcode\frontend; npm run typecheck; npm run lint; npm run check:ui; npx vitest run; npm run build
``

## 六、未完成与待审核方判定

1. **U05 剩余页面**：成员管理、组织设置（AI 模型）、公开参与页的骨架屏、卡片质感与悬停未按工作台口径统一（当前为文字"正在加载……"与朴素列表）。
2. **视觉基线结论**：按审核报告 U06，需同视口、同角色、可比数据状态的并排审查后给出；本轮只提供材料与实测数据，不自行判定"视觉合格"。
3. **撤回的交付验收**：功能开发清单中该项保持未勾选状态，本轮未回填。
4. **真实厂商模型调用证据**：与本轮 UI 无关，仍待用户提供密钥后单独补齐。
