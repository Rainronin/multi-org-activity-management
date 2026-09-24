# 生成脚本索引

本目录存放图示生成、文档检查与开发验证脚本，只在本机核对与取数，不属于业务代码，也不进入前端构建。**脚本通过不代表业务验收**：它们只证明所列场景，凭据与限制写在各自输出和[审核报告](../项目文档/开发进度审核报告.md)里。

## 只用 Python 标准库

```powershell
python .\生成脚本\generate_diagrams.py   # 更新 图示成果/ 的 HTML 与 SVG；PNG 不自动更新
python .\生成脚本\check_docs.py           # 校验文档链接、模块与验收覆盖、阶段引用与过时入口
```

## 浏览器脚本（Playwright，需先启动对应服务）

依赖 `UI演示/node_modules` 里的 Playwright，用本机 Chrome 运行；失败会以断言抛出而非静默通过。

### 当前真实多角色联调

`audit_live_role_flow.mjs` 使用四个隔离 Chrome 会话，对正式前端执行创建、方案提交／审批、任务分派、手机报名审核、任务完成与复盘；同时检查详情页角色入口。账号与组织关系由真实 API 准备，业务操作走页面，无接口替身。**会写库**，只允许 `activity_test`。

运行前停止占用 8080 的开发服务，显式设置测试后端的 `SPRING_DATASOURCE_URL` 指向 `activity_test`，上传目录指向测试目录；确认数据库实际连接后启动正式前端 5174，再执行：

```powershell
$env:AUDIT_TEST_DATABASE='activity_test'
node .\生成脚本\audit_live_role_flow.mjs
```

每次默认注册四个测试账号；五分钟内反复跑会触发真实注册限流，不绕过限流。调试同一次运行时可用 `FLOW_RUN_STAMP` 指定此前生成的时间戳，复用对应测试账号；正常新场景不设置。输出 `验证截图/浏览器联调/结果.json` 及手机报名、负责人复盘截图，失败时输出当前页面文字。运行后关闭测试前后端，避免将测试库当作开发环境。

### 当前 UI 还原（S1—S6 与审核整改）

| 脚本 | 用途 |
| --- | --- |
| `audit_ui_activity_entry.mjs` | 第二轮创建流程：角色入口、候选人重试、校验、未保存提醒、时区、原键原内容核对、五档视口与短视口；全部 API 替身，不写开发库 |
| `audit_ui_task_flow.mjs` | 第二轮任务流程：说明与期限、直达任务页签、任务表单校验与失败／成功反馈、取消保护、五档视口；全部 API 替身 |
| `audit_ui_priority.mjs` | 2026-09-22 UI 优先级：五档视口、手机菜单、页签导航和加载；仅需正式前端，接口替身不作为业务证据 |
| `audit_ui_standard.mjs` | 标准页只读回归：工作台、详情页签与手机 |
| `audit_ui_workbench.mjs` | 工作台结构与数值，含骨架同构、窄屏首屏、匿名登录证据 |
| `audit_ui_s4.mjs` | 活动列表／我的任务／成员管理三页：四态互斥、筛选口径、权限、五档宽度 |
| `audit_ui_s5.mjs` | 登录／注册／恢复／账号／组织设置／公开参与：认证构图、控件尺寸、设置校验与重试、详情加载状态、骨架限速可见 |
| `audit_ui_s6.mjs` | 全前端回归：匿名／负责人／普通成员三套会话 × 全部可达路由 + 守卫场景 |
| `audit_ui_visual_review.mjs` | 复核方脚本：认证页与 Demo 同视口对照、触控尺寸、设置失焦／失败替身 |
| `audit_p9_build.mjs` | 生产构建产物与嵌套路由刷新，含 `/api` 代理健康 |
| `seed_demo.mjs` | 演示数据准备（注册两个演示账号并造出可浏览活动），**会写库** |

### 后端独立审核的定点证据（2026-09-15）

| 脚本 | 用途 |
| --- | --- |
| `audit_governance_probe.mjs` | 限流绕过（SEC-01）、分页边界、归档后 PATCH（ACT-01）、畸形路径变量（SEC-08） |
| `audit_governance_probe2.mjs` | 费用票据越权下载（AUTH-03）：同一凭证下负责人与普通成员的列表／下载对照 |
| `audit_governance_probe3.mjs` | 归档后物资与任务写入（AUTH-02）、反馈并发唯一键（CONC-01） |
| `audit_governance_probe4.mjs` | AI 生成并发同键（AI-01）：自带 9999 端口桩模型，需后端 `app.ai.allow-private-hosts=true` 与 `enabled=true` |

三者都**会写库**，必须先把 8080 指向非开发库（本轮用 `activity_p9_build` 清空重建），并先跑 `seed_demo.mjs` 造账号；账号经 `PROBE_OWNER`／`PROBE_MEMBER`／`PROBE_ORG` 传入。结论与证据见[后端独立审核记录](../项目文档/历史记录/后端独立审核记录.md)。

## 账号依赖（复跑前必读）

`audit_ui_s4／s5／s6／workbench／standard` 的默认账号是**写死的旧演示账号**（如 `demo_owner_yrnn`），而 `seed_demo.mjs` 每次生成带时间戳后缀的**新**账号名。因此在新建或清空后的库上直接复跑必然 401 登录失败。复跑顺序：

```powershell
node .\生成脚本\seed_demo.mjs            # 记下输出的 owner／member 账号名
$env:UI_AUDIT_USER="<owner>"; $env:UI_AUDIT_MEMBER="<member>"; $env:UI_AUDIT_PASS="Demo12345!"
node .\生成脚本\audit_ui_s4.mjs            # s5／s6／workbench／standard 同理
```

`audit_p9_build.mjs` 不依赖演示账号（只验证生产构建、嵌套路由刷新与 `/api` 代理），可直接复跑。

### 阶段历史核对（P1—P9）

按阶段留档，用于追溯当时的业务结论；重跑前必须确认数据库目标，多数会新增测试数据。

`audit_p13.mjs`、`audit_p234.mjs`、`audit_p234_recheck.mjs`、`audit_p4_ui.mjs`、`audit_p5_expense.mjs`、
`audit_p6_lifecycle.mjs`、`audit_p7_workbench.mjs`、`audit_p8_ai.mjs`、`audit_p9_flow.mjs`、
`audit_f01_outsider.mjs`、`audit_f0507_flow.mjs`、`audit_s10_ui.mjs`、`audit_s11_public.mjs`、
`audit_mobile_acceptance.mjs`、`audit_ds_progress.mjs`

## 数据库目标

- 正式前端 5174 与后端 8080 的 `local` profile 连的是 **`activity_dev`**（开发库）。
- 自动化测试脚本要求独立测试库 **`activity_test`**；`seed_demo.mjs` 等写库脚本会先检查 `AUDIT_TEST_DATABASE=activity_test` 才继续。
- 核对界面时看到的是开发库数据；不要用测试库的结论解释界面现象，也不要让写库脚本落到开发库。

## 可重跑与产物

截图统一输出到 `验证截图/`（不入库），脚本会同时写出 `*结果.json` 与对照 HTML。异机接手或换数据后需重跑才能得到当前证据；不要把旧截图当作本轮结果。

根目录不放临时产物。临时诊断脚本用完即删，不留在本目录。
