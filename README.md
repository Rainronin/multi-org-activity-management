# 基于 Vue 的多组织活动管理系统设计与实现

> 2026-09-23：当前正式 UI 已达到用户要求标准、暂时确认。后续以正式前端和[UI 规范](项目文档/前端UI设计与规范.md)为基线；当前停点见[接手摘要](项目文档/进度接手摘要.md)。

当前基准提交 `2866900`，保留其后全部未提交改动。UI 确认不等于全业务验收；未关闭问题见[审核报告](项目文档/开发进度审核报告.md)，完成状态见[功能开发清单](项目文档/功能开发清单.md)。

私有源码仓库：[Rainronin/multi-org-activity-management](https://github.com/Rainronin/multi-org-activity-management)。本机配置、上传文件、验证截图与构建产物不入库。

## 文档索引（按任务选读）

接手先看[进度摘要](项目文档/进度接手摘要.md)与[当前审核](项目文档/开发进度审核报告.md)；下表是职责索引，不是每次都要通读的清单。

| 文件 | 唯一职责 |
| --- | --- |
| [项目协作入口](AGENTS.md) | 下次开发的阅读顺序、工作区边界与执行约束 |
| [进度接手摘要](项目文档/进度接手摘要.md) | 新 AI 的当前停点、待办与误操作核查入口；不替代功能清单或审核报告 |
| [项目计划书](项目文档/项目计划书.md) | 功能范围、角色权限、业务状态、指标和验收 |
| [开发规范](项目文档/开发规范.md) | 前后端共同的工程、接口、错误、会话、日志与检查合同 |
| [前端架构与技术栈设计](项目文档/前端架构与技术栈设计.md) | Vue 3 前端技术、目录、模块所有权和组件复用 |
| [前端 UI 设计与规范](项目文档/前端UI设计与规范.md) | 唯一视觉 token、主题映射、布局、交互、组件使用及 UI 检查标准 |
| [后端架构与技术栈设计](项目文档/后端架构与技术栈设计.md) | Spring Boot／MySQL 单体架构、目录、事务和跨模块编排 |
| [功能开发清单](项目文档/功能开发清单.md) | 按大阶段与小阶段推进，记录真实完成情况 |
| [开发流程与顺序](项目文档/开发流程与顺序.md) | 剩余开发项的任务闭环与跨阶段补齐；不另记完成状态 |
| [开发进度审核报告](项目文档/开发进度审核报告.md) | 当前阶段的代码与运行复核、缺陷证据及修复顺序 |
| [部署与恢复说明](项目文档/部署与恢复说明.md) | 环境重建、附件与备份恢复、生产构建与 AI 接入参数 |
| [数据设计](项目文档/数据设计.md) | 物理字段、索引、约束与保留规则 |
| [接口说明](项目文档/接口说明.md) | 模块端点、字段、权限与页面消费合同 |

历史资料按需查阅：[开发日志](项目文档/历史记录/开发日志.md)、[UI 还原执行记录](项目文档/历史记录/UI还原执行说明书.md)、[历次 UI 送审证据](项目文档/历史记录/UI还原证据.md)、[开工前文档审核](项目文档/历史记录/文档审核报告.md)、[A01—A20 验收快照](项目文档/历史记录/验收复核记录.md)和[后端独立审核快照](项目文档/历史记录/后端独立审核记录.md)。它们保留当时结论，不作为当前规范或默认接手顺序。

技术栈的确切清单分别维护在前后端架构中。具体依赖版本见锁文件与下方运行说明；物理表字段、模块接口详情、模型服务商和部署参数在对应实施环节确定，不代表需要重新选择整套框架。

## 工作区目录

| 目录 | 用途 |
| --- | --- |
| `项目文档/` | 现行设计、规则、接口及当前审核入口，每种职责只保留一份正文 |
| `项目文档/历史记录/` | 开发历史与旧送审材料，默认不读，不覆盖现行规范 |
| `图示成果/` | [功能图与概念 ER 图 HTML](图示成果/项目ER图.html)、[功能图 PNG](图示成果/系统功能图.png)及 SVG；不作为业务实现或物理建表证据 |
| `UI演示/` | Vue 3、TypeScript、Vite 与 Element Plus 轻量风格样例，含源码及预览；运行方式见[演示说明](UI演示/README.md) |
| `基于Vue3的多组织活动管理系统_zcode/` | 业务工程：`frontend/` 与 `backend/`，现有前后端业务实现，阶段状态见功能清单 |
| `生成脚本/` | 图示生成、文档检查与开发验证脚本；用途见[脚本索引](生成脚本/README.md) |
| `历史参考/` | 原始 Word、旧原型和修订前压缩快照；仅明确追溯历史时读取，不用于默认开发上下文 |

业务工程位于 `基于Vue3的多组织活动管理系统_zcode/`，其下 `frontend/`、`backend/` 按实际功能建立目录。P0.1 最小工程已建立并验证联通；尚无部署服务。正式名称以本文件标题为准。

根目录使用一套本地 Git 仓库，已存在阶段提交；当前审核基准见开发进度审核报告。[.gitignore](.gitignore)排除依赖、构建、缓存和本机敏感配置；[.editorconfig](.editorconfig)统一文本格式。源码、锁文件、图示成果和正式文档保留；现有目录分类无需搬动，历史参考不加入默认开发上下文。

## 下一步开发入口

当前停点和下一步见[接手摘要](项目文档/进度接手摘要.md)；未关闭问题见[审核报告](项目文档/开发进度审核报告.md)。

AI 主密钥、域名限制和生产参数见[部署与恢复说明](项目文档/部署与恢复说明.md)第五节。

正式前后端的本地端口与代理约定见开发规范；原型保留原有运行方式。以下为当前工程命令；阶段历史见[开发日志](项目文档/历史记录/开发日志.md)，最新验证范围与限制见开发进度审核报告。

## 正式工程运行与检查

前置：Node v24.16.0、JDK 21（Temurin 21.0.11）、MySQL 8.4.9（本机 `C:\Program Files\MySQL\MySQL Server 8.4\`）。开发数据库为独立数据目录实例（仅监听 127.0.0.1:3306，`activity_dev`/`activity_test` 两库，root 空密码仅限本机回环）。**本地凭据文件为 `backend/config/application-local.yml`（主资源目录之外，不入库不进构建包；示例见 `backend/src/main/resources/application-local.example.yml`）**，后端经 `spring.config.additional-location=optional:file:./config/` 加载；测试侧为 `backend/src/test/resources/application-local.yml`（示例同目录 `application-local.example.yml`），且 `ActivityTestBase` 强制测试连接 `activity_test`。

```powershell
# 启动 MySQL 开发实例（数据目录在 C:\Users\MsRain\AppData\Local\activity-mysql-dev\）
& "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqld.exe" --defaults-file="C:\Users\MsRain\AppData\Local\activity-mysql-dev\my.ini" --console

# 后端（在 基于Vue3的多组织活动管理系统_zcode\backend 下；Git Bash 用 sh mvnw，CMD/PowerShell 用 .\mvnw.cmd）
.\mvnw.cmd spring-boot:run -D"spring-boot.run.profiles=local"   # 启动并自动执行 Flyway 迁移，监听 127.0.0.1:8080
.\mvnw.cmd verify                                                # 测试与打包；先确认连接 activity_test，见下方配置限制

# 前端（在 基于Vue3的多组织活动管理系统_zcode\frontend 下）
npm ci
npm run dev        # 127.0.0.1:5174，/api 代理到 8080；端口占用时报错不漂移
npm run typecheck  # vue-tsc 严格类型检查
npm run lint       # ESLint
npm run test -- --run
npm run check:ui   # UI 规范静态门禁（token 完整性与字面量检查）
npm run build      # 类型检查 + 产物构建
```

浏览器打开 <http://127.0.0.1:5174>，未登录进入登录页，登录后进入账号及组织目录；`GET /api/health` 提供只读健康状态。注册、恢复和 CSRF 入口允许匿名访问，写请求仍需 CSRF，组织接口逐请求鉴权。锁定版本：Spring Boot 3.5.16、MyBatis Starter 3.0.4、Flyway 11.7.2、MySQL Connector/J 9.7.0、Vue 3.5.42、Element Plus 2.14.5、Vite 7.3.6。

审核 R08 已修复：主资源构建排除 `application-local.yml`，凭据经不入库的外置 `config/` 目录或环境变量注入；分发前可用 `jar tf target/activity-0.0.1-SNAPSHOT.jar | findstr application-local` 复核（应只见无凭据的 example 文件）。测试连接由 `ActivityTestBase` 强制指向 `activity_test`；独立运行验证脚本或服务时仍须另行确认数据库目标。

## 查看 UI 演示

Demo 保留为原始风格与交互参考；当前正式页面已达到要求标准，后续修改先对照正式页面与[UI 规范](项目文档/前端UI设计与规范.md)。只有追溯原设计或处理正式页面未覆盖的交互时才需查看演示源码和预览，不按旧预览回退布局。

在 `UI演示/` 目录执行 `npm ci`、`npm run dev`，浏览器打开 <http://127.0.0.1:5173>。类型检查及构建使用 `npm run build`；浏览器检查使用 `npm run check:ui`，需要本地服务与 Chrome。演示功能和限制见[演示说明](UI演示/README.md)。

## 当前可运行的检查

在工作区根目录执行，均仅依赖 Python 标准库：

```powershell
python .\生成脚本\generate_diagrams.py
python .\生成脚本\check_docs.py
```

[图示生成脚本](生成脚本/generate_diagrams.py)读取当前计划书，校验模块及关系，更新 HTML 和两个 SVG；PNG 不自动更新。仅修改链接或非可视元数据不需要重绘 PNG；图形内容变化后需重新导出预览。

[文档检查脚本](生成脚本/check_docs.py)核对当前文档链接、模块与验收覆盖、阶段引用及过时入口；不代替对业务和架构的人工审核。业务运行与构建检查使用上方正式工程命令。
