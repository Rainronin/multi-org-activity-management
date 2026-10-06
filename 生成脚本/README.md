# 生成脚本索引

本目录保留可复用检查及历史取证脚本。正式业务源码在业务工程；脚本通过仅证明其断言与输入范围，实际结论见[审核报告](../项目文档/开发进度审核报告.md)。源码、演示素材及用户图示不靠一次性脚本复制成另一套实现。

## 通用前提

- Python检查只用标准库。Node浏览器脚本需要在 `UI演示/` 运行 `npm ci`，使用该目录的Playwright与本机Chrome。
- 写库脚本必须先核对后端实际连接独立activity_test，再设置 `AUDIT_TEST_DATABASE=activity_test`。环境变量是前置保护，不能替代实际服务目标核对；不能对开发库运行验证或种子脚本。
- 正式前端5174、测试后端8080；业务后端测试配置、临时AI桩、端口及主密钥均仅限测试进程。不要修改正式参数来迁就测试，也不读取旧一次性密钥。
- 本机截图、JSON和日志输出到已忽略的 `验证截图/`；旧过程证据整理在 `backups/验收证据-20261006.zip`，保持原相对路径。证据不随Git克隆分发。

## 文档与图示

```powershell
python .\生成脚本\check_docs.py
python .\生成脚本\generate_diagrams.py
```

前者核对本地链接、模块／场景／阶段和过时入口；后者消费当前计划书生成图示HTML／SVG，PNG不自动更新，图形内容变化需另导出。用户系统架构图与学习指南独立保留，不由此脚本覆盖。

## 当前专项入口

| 脚本 | 用途及模式 |
| --- | --- |
| `audit_live_role_flow.mjs` | 四角色立项、任务、报名及复盘；活动201后受控替换503核原键 |
| `audit_org_permissions.mjs` | 撤权、移除／退出、职责处理、历史署名及归档资料权限 |
| `audit_org_switch.mjs` | 组织切换、旧响应及草稿；`--stale-error`、`--interaction-basics`、`--loading-states`，可用`--case`限定 |
| `audit_f01_outsider.mjs` | 真实外部报名／反馈与改密恢复；`--read-states`使用只读API替身；`--idle-expiry`需测试会话1m并实等65秒 |
| `audit_public_ai_forms.mjs` | 公开参与及AI表单状态专项，替身不作数据库或厂商证明 |
| `audit_s10_ui.mjs` | 任务／物资新建原键核对、子记录权限与资料；创建丢响应为先页面503后写抓取原请求 |
| `audit_p234.mjs`、`audit_p234_recheck.mjs` | 活动、任务与报名主链及旧结果核对 |
| `audit_p4_ui.mjs`、`audit_s11_public.mjs` | 报名与公开参与页面专项 |
| `audit_p5_expense.mjs` | 经费、凭证、作废及更正；`--readonly-recheck`与`--concurrent-recheck` |
| `audit_p6_lifecycle.mjs` | 执行、反馈、总结、归档／重开及独立确认历史；`--early-cancel`覆盖初期取消 |
| `audit_p7_workbench.mjs` | 工作台与提示、撤权未读、手机布局 |
| `audit_p8_ai.mjs` | 两协议AI桩、结果未知、竞态、人工保存；显式配置可做真实厂商，见下节 |
| `audit_p9_flow.mjs` | 同活动贯通链，准备含API，AI为本机桩 |
| `audit_p9_build.mjs` | 构建产物深链／守卫／健康代理，只读，不依赖旧演示账号 |
| `audit_runtime_logs.mjs` | 真实HTTP错误／requestId／脱敏与gzip轮转；需本次日志路径 |
| `audit_recovery.py` | 创建独立临时MySQL与activity_test／activity_restore，备份及附件恢复，不连接开发库 |

脚本默认模式和可选模式在源码开头及 `process.argv` 定义；按实际前置条件运行，不机械顺序执行全部脚本。用户资料、现有开发库及真实密钥不作为测试夹具。

## 正式UI与状态矩阵

| 脚本 | 用途 |
| --- | --- |
| `audit_ui_s4.mjs` | 列表／本人任务／成员等正式页面只读风格核对 |
| `audit_ui_s5.mjs` | 编辑弹层与账号；`--account-security`／`--account-security-live` |
| `audit_ui_s6.mjs` | 三身份路由／守卫；`--state-matrix`、`--form-matrix`、`--recovery-matrix`；`--inline-only`、`--extra-only`等用于限定专项 |
| `audit_ui_task_flow.mjs` | 任务承担者主流程及草稿／旧请求保护 |

UI脚本旧默认账号不一定存在，须显式提供当前测试账号。只读脚本不应靠开发库造数据。`seed_demo.mjs`会创建演示数据，只有明确需要并确认独立测试服务后才执行；它不是用户正常启动的一步。

```powershell
$env:UI_AUDIT_USER='<本次测试负责人账号>'
$env:UI_AUDIT_MEMBER='<本次测试成员账号>'
$env:UI_AUDIT_PASS='<本次测试密码>'
node .\生成脚本\audit_ui_s6.mjs --state-matrix
```

读取993、表单300、恢复90的实际适用身份／N/A与限制见审核及本机覆盖清单；不能把弹层子集再加总、把替身当自然网络、把路由组合当不同页面数量。

## P8真实厂商模式

仅消费用户本次明确提供的新配置，不扫描旧文件、旧环境密钥或已有组织凭据。`P8_REAL_CONFIG='-'`读取一行标准输入JSON，TTY关闭回显；也可指定已忽略的安全文件。不要将密钥放shell参数、示例、Git或报告。

必填：`endpoint`（完整HTTPS POST地址）、`model`、`authScheme`（X_API_KEY或BEARER）、`apiKey`（新密钥）。可选：`protocol`默认ANTHROPIC_MESSAGES，也可OPENAI_CHAT_COMPLETIONS（仅BEARER）；`maxOutputTokens`默认2048、`timeoutSeconds`默认60。加 `--check-config`只做前置校验、不写业务库，每次运行各消费一次输入。

```powershell
$env:AUDIT_TEST_DATABASE='activity_test'
$env:P8_REAL_CONFIG='-'
node .\生成脚本\audit_p8_ai.mjs
```

真实测试后端须配置新的随机主加密密钥，只允许本次可信厂商域名并保持私网豁免关闭。单次流程仅发连接测试、页面方案及页面总结各一次，采用／人工修改保存后确认总结；原键在等待前落证据，失败停止、不自动重发／换键。finally通过clear-key清除本次新测试组织的密钥并核对停用。结果按协议分存 `P8-真实厂商-<协议>-结果.json`，仅存状态、标识、长度／摘要与确认时间，截图按协议分文件。

2026-10-06 DeepSeek两协议真实流程通过，首次Anthropic截断FAIL单独保留；同2048上限修复兼容后成功，本轮总8次实际请求。厂商限流／费用及更多品牌未验，不重复调用已作废的一次性密钥。

预演可加 `P8_REAL_REHEARSAL=1`，只允许本机HTTP桩及公开假密钥，结果明确realVendor=false；不能用预演关闭真实厂商项。原完整桩流程可能超过正式5分钟10次，测试后端可临时放宽到30／50完成专项，正式默认不变，必须记录实际测试参数。

## 日志与恢复

日志检查指定本次测试服务的 `AUDIT_RUNTIME_LOG_FILE`，脚本先核启动日志包含activity_test。gzip轮转测试可仅进程阈值1KB，正式10MB／14天／归档200MB保持；不得将短期轮转当长期容量验收。

恢复脚本需先构建后端，再用实际可执行文件路径：

```powershell
python .\生成脚本\audit_recovery.py --mysql-bin '<MySQL的bin目录>' --java '<java.exe绝对路径>'
```

其演练使用独立临时实例，输出 SQL／附件／JSON／日志到本机验证目录，结束关闭本次服务。旧恢复证据对应当时结构，不能自动算V27整库恢复完成；该项仍为部署限制，不是清单里的未开发功能。

## 历史取证入口

`audit_ds_progress.mjs`、`audit_p13.mjs`、`audit_f0507_flow.mjs`、`audit_mobile_acceptance.mjs`、`audit_governance_probe*.mjs`保留早期进度／权限／并发取证；probe3已消费当前版本合同，其他旧脚本须先对照当前接口，不能默认有效。

`audit_ui_activity_entry.mjs`、`audit_ui_priority.mjs`、`audit_ui_standard.mjs`、`audit_ui_visual_review.mjs`、`audit_ui_workbench.mjs`、`ui_visual_capture.mjs`保留旧视觉审核过程。正式UI已经确认，不按旧脚本／旧截图重新开发。历史结论见开发日志及历史审核材料，不把这些脚本的旧运行数当本次结果。
