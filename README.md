# 基于 Vue 的多组织活动管理系统设计与实现

当前修复版本：`v1.0.1`（2026-10-08），初版为`v1.0.0`。功能清单 **163项完成／0项未关闭**，包含多组织协作、活动立项与审批、任务与物资、报名、经费、执行复盘、归档及 AI 文稿辅助。正式 UI 以用户确认的现有页面与[UI规范](项目文档/前端UI设计与规范.md)为基线。

本机验收已完成；本项目尚未部署到生产环境。测试范围与限制统一见[审核报告](项目文档/开发进度审核报告.md)，后续接手先读[接手摘要](项目文档/进度接手摘要.md)。

私有源码仓库：[Rainronin/multi-org-activity-management](https://github.com/Rainronin/multi-org-activity-management)。仓库包含源码、锁文件、数据库迁移、可复用验证脚本、图示和正式文档；本机配置、密钥、数据备份、上传文件、验收截图与依赖缓存均不上传。

## 目录与文档入口

| 目录／入口 | 内容 |
| --- | --- |
| [项目协作规则](AGENTS.md) | 接手顺序、权限与修改边界 |
| `基于Vue3的多组织活动管理系统_zcode/backend/` | Spring Boot／MyBatis／MySQL 后端及测试 |
| `基于Vue3的多组织活动管理系统_zcode/frontend/` | Vue 3／TypeScript／Element Plus 正式前端 |
| [项目计划书](项目文档/项目计划书.md)／[功能清单](项目文档/功能开发清单.md) | 业务权限、范围与唯一完成状态 |
| [开发规范](项目文档/开发规范.md)／[开发流程](项目文档/开发流程与顺序.md) | 工程合同与后续修改流程 |
| [前端架构](项目文档/前端架构与技术栈设计.md)／[后端架构](项目文档/后端架构与技术栈设计.md) | 当前实现职责与技术边界 |
| [数据设计](项目文档/数据设计.md)／[接口说明](项目文档/接口说明.md) | V27结构、字段、端点、权限与恢复合同 |
| [部署与恢复](项目文档/部署与恢复说明.md) | 本机配置、迁移、附件、备份与 AI 接入 |
| [脚本索引](生成脚本/README.md) | 文档、图示、浏览器与恢复验证脚本 |
| [图示成果](图示成果/项目ER图.html)／[系统架构图](图示成果/系统架构图.html) | 功能、概念 ER 与架构图，不能代替物理表结构 |
| [学习指南](学习指南/index.html) | 用户提供的学习材料 |
| [UI演示](UI演示/README.md) | 原始风格参考，使用虚构数据，不是正式业务工程 |
| `项目文档/历史记录/` | [开发日志](项目文档/历史记录/开发日志.md)与旧送审记录，只在追溯时查阅 |
| `历史参考/`、`backups/`、`验证截图/` | 本机历史材料、备份与验证证据，由Git忽略；克隆仓库后不包含这些目录 |

## 首次运行

环境：JDK 21、MySQL 8.4、Node.js 24（本机验收版本见[部署说明](项目文档/部署与恢复说明.md#一环境要求)）。依赖版本由 `pom.xml`、Maven Wrapper 与 `package-lock.json` 管理；数据库迁移共27份，Flyway自动执行。下列命令使用PowerShell 7；从克隆仓库首次运行时重新安装依赖。

1. 准备相互独立的 `activity_dev` 与 `activity_test` 数据库及对应账号，权限模板见[部署说明](项目文档/部署与恢复说明.md)。不要将测试库配置为开发库。
2. 以下 `backend/`、`frontend/` 均位于业务工程目录。将 `backend/src/main/resources/application-local.example.yml` 复制到 `backend/config/application-local.yml`，填写开发库凭据；将 `backend/src/test/resources/application-local.example.yml` 复制到 `backend/src/test/resources/application-local.yml`，填写独立测试账号凭据。已有实际配置不得覆盖；示例均为占位值，实际文件已被忽略，不能提交。
3. 启动MySQL并核对实际端口。开发默认3306；测试可通过 `activity.test-db-port` 使用13306。已有库升级前先停止业务写入并备份数据库及附件，不能手改已应用迁移。

在业务工程的 `backend/` 中：

```powershell
.\mvnw.cmd spring-boot:run -D"spring-boot.run.profiles=local"
```

在业务工程的 `frontend/` 中：

```powershell
npm ci
npm run dev
```

访问 <http://127.0.0.1:5174/>，后端为 `127.0.0.1:8080`；前端 `/api` 代理到后端，端口占用时不会自动漂移。健康检查为 `GET /api/health`。启动后自行注册账号、创建组织或使用已有开发数据；仓库不附带真实账号、恢复凭据或开发库数据。

AI需配置独立主加密密钥、可信厂商域名及组织自己的新API Key，步骤见[部署说明第五节](项目文档/部署与恢复说明.md#五ai-模型接入可选功能)。未配置或厂商失败时可继续人工编写方案与总结；不复用历史验收密钥。

已有本机数据库配置也必须补齐 `app.ai.master-key`，它与界面填写的厂商API Key是两种密钥。主密钥只生成一次并保存在已忽略的 `backend/config/application-local.yml`，不能只在临时测试进程设置，也不能每次启动重生成。修改后重启后端，再在组织设置保存厂商参数与密钥；保存成功即生效，连接测试为可选诊断。

## 检查命令

后端（在业务工程的 `backend/` 执行；先启动并确认独立测试MySQL，测试基类固定使用 `activity_test`，该参数只选择端口）：

```powershell
.\mvnw.cmd -D"activity.test-db-port=13306" verify
```

前端（在业务工程的 `frontend/` 执行，已完成 `npm ci`）：

```powershell
npm run test -- --run
npm run lint
npm run check:ui
npm run build
```

工作区根目录（仅Python标准库）：

```powershell
python .\生成脚本\check_docs.py
python -m unittest discover -s .\生成脚本 -p 'test_*.py'
python .\生成脚本\generate_diagrams.py
```

图示生成会更新HTML和SVG，PNG不自动重绘。浏览器专项需要另装 `UI演示/` 的依赖和本机Chrome，运行范围与测试库保护见[脚本索引](生成脚本/README.md)。一般源码或文档修改只执行与风险相符的检查。

## 当前交付范围

2026-10-06发布前重新执行：后端 **213项测试及构建**、前端 **60项测试、lint、UI门禁和含类型构建**，UI演示构建均通过；两协议真实DeepSeek方案／总结页面验收在同日此前已完成，本次不重复厂商调用。具体证据、受控模拟及未覆盖范围见[审核报告](项目文档/开发进度审核报告.md)。

2026-10-08修复本机AI主密钥漏配及错误指引：后端 **214项verify及构建**通过；真实HTTP本机桩验证保存、测试、启用、方案生成和重启后解密。前端未修改，本轮未重跑前端或真实厂商验收。

同日按用户实测反馈调整AI操作：保存即启用，测试连接可选且保存不调用厂商；主页无草稿时打开既有创建表单，无复盘活动时前往列表。最新后端 **215项测试**通过，运行中JAR占用导致首次打包失败，停止后以skipTests完成verify打包；前端 **60项测试、lint、UI门禁、含类型构建**及两组API替身浏览器回归通过。本次未调用真实厂商；旧停用配置重新保存即可生效。用户确认暂时正常后，按要求停止本项目前端5174、后端8080和本机MySQL3306，作为`v1.0.1`记录；下次使用按上方命令重新启动。
