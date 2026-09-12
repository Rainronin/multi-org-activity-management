# 基于 Vue 的多组织活动管理系统设计与实现

## 前端 UI 设计与规范

状态：用户已认可当前 Demo，以此作为正式前端的视觉与交互基线。本文统一设计 token、布局、组件使用与验收规则。业务工程尚未创建，规范成立不代表代码已经实现或通过业务验收。

业务范围与权限引用[项目计划书](项目计划书.md)，模块所有权引用[前端架构](前端架构与技术栈设计.md)，接口、错误与会话引用[开发规范](开发规范.md)。本文是 UI 规则的唯一维护位置，其他文档只引用，不复制 token 数值。

指定实现参考：[工作台首屏](../UI演示/预览/工作台首屏.png)、[完整工作台](../UI演示/预览/活动列表.png)、[登录界面](../UI演示/预览/登录界面.png)、[演示运行说明](../UI演示/README.md)。当前单侧栏版本是用户确认的前端还原基准，不只是灵感参考；具体执行要求见下节。设计数值与工程约束以本文为准。

### 前端开发必须以当前 Demo 为基础

AI 开发任何前端页面前，必须读取本文、演示说明及相关演示源码，查看对应预览；涉及交互时运行 Demo 核对行为。默认还原现有效果，不自行更换后台模板、重做配色或改变信息层级。`历史参考/` 中旧原型不作为开发基准。

| 开发对象 | 必须参考的现有实现 |
| --- | --- |
| 页面框架、组织切换、活动列表与详情 | [App.vue](../UI演示/src/App.vue)、[sidebar.css](../UI演示/src/sidebar.css)、[折叠侧栏预览](../UI演示/预览/侧栏折叠.png) |
| 重点活动与 AI 文稿入口、编辑反馈 | [FocusBoard.vue](../UI演示/src/FocusBoard.vue)、[AI 编辑预览](../UI演示/预览/AI总结编辑.png) |
| KPI、趋势与经费展示 | [Overview.vue](../UI演示/src/Overview.vue)、完整工作台预览 |
| 分屏登录、表单状态 | [LoginPreview.vue](../UI演示/src/LoginPreview.vue)、登录界面预览 |
| 字体、布局、质感、动效及响应式 | [tokens.css](../UI演示/src/tokens.css)、[style.css](../UI演示/src/style.css)、[手机预览](../UI演示/预览/手机布局.png)；具体 token 以本文为准 |

执行与验收约束：

1. **还原效果。** 保持黑白橙配色、字体层级、单个可折叠深色悬浮侧栏、卡片比例、留白、圆角、阴影、玻璃质感及动效节奏。工作台沿用重点活动与 AI 卡 → KPI → 图表 → 明细的顺序；登录沿用左右分屏。正式数据、文案长度、权限和屏幕尺寸可引起合理自适应，不要求固定截图中的人名、数值或行数。
2. **复用合规实现。** 优先迁移已有合规模板、样式和交互，按前端架构拆入正式模块；先用 Element Plus，按本文规则封装复用。存量硬编码样式须转换为统一 token，不能直接把整个 Demo 或其大组件当作生产工程。业务实现放入 `frontend/`，保留 `UI演示/` 供对照。
3. **替换演示行为。** 虚构数据、内存保存、假登录、模拟 AI、固定加载延迟及“演示选项”不迁入正式业务。改接真实接口、权限、持久化与请求状态。图表仅呈现已确认且有接口支持的统计；不能为还原画面新增业务范围或填入假数据。未实现入口按开发流程不展示。
4. **延续未覆盖页面。** Demo 没有的设置、成员管理等页面，复用同一布局、表单、表格、状态和 token 体系，按计划书补全功能，不另起视觉风格。真实业务、安全与可访问性要求优先；必要差异在交付时说明，整体风格和导航结构的重设计须先由用户确认。
5. **逐页对照。** 每个页面交付前，以相同视口和可比状态对照 Demo；至少核对桌面展开、折叠和手机布局，以及本页涉及的加载、空态、错误、失焦校验和减少动效。保存或展示正式页面截图，说明必要差异；构建通过不能代替视觉检查，Demo 检查通过不能代替业务验收。

## 一、设计原则与范围

1. **黑白橙为主。** 浅色工作区、近黑文字、深色重点活动卡与暖橙 AI 文稿卡构成辨识度。橙色用于主要操作、当前重点与图表主序列，不将整页文字染成橙色。
2. **先行动，再数字。** 工作台依次展示重点活动与 AI 文稿入口、精简 KPI、图表、明细。活动列表、报名审核等业务页面直接突出本页任务，不复制工作台大卡片。
3. **玻璃有边界。** 悬浮导航和展示卡使用轻玻璃；输入框、长文编辑、表格正文和错误提示使用清晰底色。采用半透明、边缘高光与有限模糊，不折射或扭曲文字。
4. **动效解释变化。** 入场、展开、选中、加载与操作反馈可以有动效；不使用粒子背景、持续漂浮、鼠标跟随光团、文字逐字跳动、滚动劫持或全站弹簧效果。
5. **组件库优先。** Element Plus 负责基础交互与可访问行为；项目负责主题、组合、业务约束和内容。CSS 能完成的简单状态不用 GSAP 重写。
6. **事实优先。** 不为填满卡片编造指标、趋势、到场人数、健康度或 AI 分析。没有权限不取数，没有数据展示空状态。

仅提供当前浅色工作区与局部深色卡片，不同时建设全站深色主题、主题编辑器或可配置仪表盘。正式项目名称不得使用私人简称。

## 二、统一 token 的强制约束

### 2.1 唯一来源与消费规则

正式工程的值源固定为 `frontend/src/styles/tokens.css`。第三节给出初始化基线；工程创建前以本文为准，创建后 token 实现与本文须在同一次修改中保持一致，不建立另一份手工维护的 JSON／TS 数值清单。

| 范围 | 必须遵守 |
| --- | --- |
| 颜色 | 文字、背景、边框、渐变停靠色、透明遮罩、图表与 SVG 颜色均引用语义 token |
| 字体 | 字体族、字号、字重、行高、字距均引用 token；不在页面写独立 font 简写 |
| 几何尺寸 | 间距、圆角、描边宽度、控件高度、图标尺寸、固定容器宽度与留边均引用 token |
| 质感 | 阴影、模糊、饱和度、禁用透明度和焦点环引用完整 token，不在页面叠加临时阴影 |
| 动效 | 位移、时长、延迟、错峰和缓动来自 token；GSAP 与 CSS 使用同一来源 |
| 组件库 | `--el-*` 映射集中维护；页面不能单独发明一套组件主题 |
| JavaScript | 图表库配置、GSAP 参数、组件数值 props 通过统一读取函数取得 token，不能绕过 CSS 规则硬编码 |

业务 CSS 允许 `0`、`auto`、`none`、`inherit`、`currentColor`、`transparent`、`100%`、`min-content`、`max-content` 等重置或结构值。Flex／Grid 的等分数量、SVG 的绘图坐标、真实数据决定的百分比与坐标不属于设计数值；其颜色、字号、描边、留白仍受 token 约束。`calc()` 可以组合已定义 token，不能藏入新的像素补丁。

响应式断点只能在 `tokens.css` 的集中媒体查询中声明并覆盖布局 token；组件消费结果，不在各页面复制断点。CSS 自定义属性不能直接用作媒体查询条件，禁止写 `@media (max-width: var(--ui-breakpoint))`。这一限制见 [MDN 自定义属性说明](https://developer.mozilla.org/en-US/docs/Web/CSS/Guides/Cascading_variables/Using_custom_properties)。

### 2.2 不能做的“伪统一”

- 不得在页面写 `--local-gap: 19px`、`--page-orange: #...`，再声称已经使用 token。
- 不得写 `var(--ui-space-4, 16px)` 作为业务代码兜底，掩盖 token 缺失。运行时缺失 token 应在开发检查中失败。
- 不得用内联 style、SVG 属性、GSAP 数字、Element Plus props 或任意类名绕过规则。
- 不得将 Demo 的每一个历史像素值逐一登记为 token。相近值归并到下面的尺度；新增值先说明现有尺度不足的原因。
- 不得修改依赖包源码来换主题，不把第三方库内部常量复制成项目维护责任。

统一主题不意味着禁止页面写 CSS：页面仍负责 Grid／Flex 结构、内容布局与响应式组合，只是不拥有设计数值。

### 2.3 修改流程

先搜索已有 token → 优先使用现有语义 → 确实需要时在 `tokens.css` 新增或修改 → 同步本文的用途说明 → 检查受影响页面、弹层和手机布局。只在确有新语义时加组件 token，不按每个页面建立私有主题。不要求为低风险 token 调整另外走审批流程，但必须可审查。

## 三、设计 token 基线

### 3.1 命名与尺度

统一前缀 `--ui-`；使用 `color-*`、`font-*`、`space-*`、`radius-*`、`shadow-*`、`motion-*`、`layout-*` 等类别。业务样式优先引用语义名，禁止按“看起来差不多”选其他用途的 token。

以下代码块是 token 初始化的唯一数值表。颜色、阴影与渐变都在此定义，不在后文重复维护色值。`rem` 尊重浏览器根字号；不强制将根字号设小来实现紧凑界面。

```css
:root {
  /* 颜色：明亮橙负责强调，较深橙承载白色按钮文字。 */
  --ui-color-canvas: #f3f3ef;
  --ui-color-surface: #ffffff;
  --ui-color-surface-muted: #f8f8f6;
  --ui-color-text: #1a1814;
  --ui-color-text-secondary: #55534e;
  --ui-color-text-muted: #706e68;
  --ui-color-border: #e8e7e4;
  --ui-color-control-border: #918c81;
  --ui-color-accent: #ff6b2c;
  --ui-color-action: #ce4313;
  --ui-color-action-hover: #b53a10;
  --ui-color-action-active: #9f320d;
  --ui-color-action-tint-3: color-mix(in srgb, var(--ui-color-action) 70%, var(--ui-color-surface));
  --ui-color-action-tint-5: color-mix(in srgb, var(--ui-color-action) 50%, var(--ui-color-surface));
  --ui-color-action-tint-7: color-mix(in srgb, var(--ui-color-action) 30%, var(--ui-color-surface));
  --ui-color-action-tint-8: color-mix(in srgb, var(--ui-color-action) 20%, var(--ui-color-surface));

  /* Element Plus 语义色阶（Tag／Button 的浅色底与深色悬停）；
     tint-N 对应 --el-color-*-light-N，dark 对应 dark-2。由正式工程 2026-09-12 主题接入新增。 */
  --ui-color-success-tint-3: color-mix(in srgb, var(--ui-color-success) 70%, var(--ui-color-surface));
  --ui-color-success-tint-5: color-mix(in srgb, var(--ui-color-success) 50%, var(--ui-color-surface));
  --ui-color-success-tint-7: color-mix(in srgb, var(--ui-color-success) 30%, var(--ui-color-surface));
  --ui-color-success-tint-8: color-mix(in srgb, var(--ui-color-success) 20%, var(--ui-color-surface));
  --ui-color-success-tint-9: color-mix(in srgb, var(--ui-color-success) 10%, var(--ui-color-surface));
  --ui-color-success-dark: color-mix(in srgb, var(--ui-color-success) 80%, #000);
  --ui-color-warning-tint-3: color-mix(in srgb, var(--ui-color-warning) 70%, var(--ui-color-surface));
  --ui-color-warning-tint-5: color-mix(in srgb, var(--ui-color-warning) 50%, var(--ui-color-surface));
  --ui-color-warning-tint-7: color-mix(in srgb, var(--ui-color-warning) 30%, var(--ui-color-surface));
  --ui-color-warning-tint-8: color-mix(in srgb, var(--ui-color-warning) 20%, var(--ui-color-surface));
  --ui-color-warning-tint-9: color-mix(in srgb, var(--ui-color-warning) 10%, var(--ui-color-surface));
  --ui-color-warning-dark: color-mix(in srgb, var(--ui-color-warning) 80%, #000);
  --ui-color-danger-tint-3: color-mix(in srgb, var(--ui-color-danger) 70%, var(--ui-color-surface));
  --ui-color-danger-tint-5: color-mix(in srgb, var(--ui-color-danger) 50%, var(--ui-color-surface));
  --ui-color-danger-tint-7: color-mix(in srgb, var(--ui-color-danger) 30%, var(--ui-color-surface));
  --ui-color-danger-tint-8: color-mix(in srgb, var(--ui-color-danger) 20%, var(--ui-color-surface));
  --ui-color-danger-tint-9: color-mix(in srgb, var(--ui-color-danger) 10%, var(--ui-color-surface));
  --ui-color-danger-dark: color-mix(in srgb, var(--ui-color-danger) 80%, #000);
  --ui-color-info-tint-3: color-mix(in srgb, var(--ui-color-info) 70%, var(--ui-color-surface));
  --ui-color-info-tint-5: color-mix(in srgb, var(--ui-color-info) 50%, var(--ui-color-surface));
  --ui-color-info-tint-7: color-mix(in srgb, var(--ui-color-info) 30%, var(--ui-color-surface));
  --ui-color-info-tint-8: color-mix(in srgb, var(--ui-color-info) 20%, var(--ui-color-surface));
  --ui-color-info-tint-9: color-mix(in srgb, var(--ui-color-info) 10%, var(--ui-color-surface));
  --ui-color-info-dark: color-mix(in srgb, var(--ui-color-info) 80%, #000);

  --ui-color-on-action: #ffffff;
  --ui-color-selected-bg: #fff0e5;
  --ui-color-selected-text: #a33713;
  --ui-color-focus: #a33713;
  --ui-color-success: #337153;
  --ui-color-success-bg: #edf5ef;
  --ui-color-warning: #85600d;
  --ui-color-warning-bg: #fff5df;
  --ui-color-danger: #b43420;
  --ui-color-danger-bg: #fff0eb;
  --ui-color-info: #676860;
  --ui-color-info-bg: #f0f0eb;
  --ui-color-disabled-bg: #efeee9;
  --ui-color-disabled-text: #79766e;
  --ui-color-overlay: #1a181480;
  --ui-color-skeleton: #e7e6e0;
  --ui-color-skeleton-highlight: #f4f3ef;

  /* 局部深色与暖橙卡，不作为全站暗色模式。 */
  --ui-color-spotlight-start: #282723;
  --ui-color-spotlight-end: #151613;
  --ui-color-on-dark: #f8f5ef;
  --ui-color-on-dark-muted: #c0b9af;
  --ui-color-on-dark-accent: #ffb17c;
  --ui-color-border-dark: #5e594e;
  --ui-color-ai-start: #fff4e8;
  --ui-color-ai-end: #ffdfc1;
  --ui-color-ai-text: #76370f;
  --ui-color-ai-muted: #78573f;
  --ui-color-ai-border: #e3b68f;
  --ui-color-glass-start: #fffffff2;
  --ui-color-glass-end: #ffffffd9;
  --ui-color-glass-edge: #ffffff;
  --ui-color-glass-on-dark: #ffffff18;
  --ui-color-glass-edge-dark: #ffffff43;
  --ui-surface-glass: linear-gradient(135deg, var(--ui-color-glass-start), var(--ui-color-glass-end));
  --ui-surface-spotlight: linear-gradient(125deg, var(--ui-color-spotlight-start), var(--ui-color-spotlight-end));
  --ui-surface-ai: linear-gradient(130deg, var(--ui-color-ai-start), var(--ui-color-ai-end));
  --ui-surface-glass-on-dark: linear-gradient(125deg, var(--ui-color-glass-on-dark), transparent);
  --ui-glass-blur: 16px;
  --ui-glass-saturation: 125%;
  --ui-opacity-disabled: 0.55;

  /* 图表不借用业务成功／失败色表达普通序列。 */
  --ui-color-chart-primary: var(--ui-color-action);
  --ui-color-chart-secondary: #5b6158;
  --ui-color-chart-track: #d9d6cd;
  --ui-color-chart-grid: var(--ui-color-border);
  --ui-color-chart-area: #ff6b2c24;
  --ui-chart-stroke: 2px;
  --ui-chart-ring-stroke: 10px;
  --ui-chart-point-radius: 4px;
  --ui-chart-height: 220px;

  /* 字体：中文正文用系统字体，数值可用等宽或等宽数字。 */
  --ui-font-family-body: "PingFang SC", "Microsoft YaHei", Arial, sans-serif;
  --ui-font-family-display: "PingFang SC", "Microsoft YaHei", Arial, sans-serif;
  --ui-font-family-data: ui-monospace, "SFMono-Regular", Consolas, monospace;
  --ui-font-size-caption: 0.75rem;
  --ui-font-size-small: 0.8125rem;
  --ui-font-size-body: 0.875rem;
  --ui-font-size-emphasis: 1rem;
  --ui-font-size-section: 1.125rem;
  --ui-font-size-card-title: 1.5rem;
  --ui-font-size-page-title: 2rem;
  --ui-font-size-kpi: 1.75rem;
  --ui-font-size-brand: 4rem;
  --ui-font-size-input: var(--ui-font-size-body);
  --ui-font-weight-regular: 400;
  --ui-font-weight-medium: 500;
  --ui-font-weight-semibold: 600;
  --ui-font-weight-bold: 700;
  --ui-line-height-tight: 1.3;
  --ui-line-height-body: 1.6;
  --ui-line-height-prose: 1.8;
  --ui-letter-spacing-body: normal;
  --ui-letter-spacing-title: -0.02em;
  --ui-letter-spacing-eyebrow: 0.08em;

  /* 基础间距：不扩散成每个整数一档。 */
  --ui-space-0: 0;
  --ui-space-1: 4px;
  --ui-space-2: 8px;
  --ui-space-3: 12px;
  --ui-space-4: 16px;
  --ui-space-5: 20px;
  --ui-space-6: 24px;
  --ui-space-8: 32px;
  --ui-space-10: 40px;
  --ui-space-12: 48px;
  --ui-space-16: 64px;
  --ui-radius-small: 4px;
  --ui-radius-control: 8px;
  --ui-radius-card: 16px;
  --ui-radius-island: 20px;
  --ui-radius-pill: 999px;
  --ui-radius-circle: 50%;
  --ui-border-width: 1px;
  --ui-focus-width: 2px;
  --ui-focus-offset: 3px;
  --ui-focus-ring: var(--ui-focus-width) solid var(--ui-color-focus);
  --ui-shadow-none: none;
  --ui-shadow-glass: inset 0 1px 0 #ffffff, 0 5px 18px #2520150a;
  --ui-shadow-hover: inset 0 1px 0 #ffffff, 0 14px 32px #30221414;
  --ui-shadow-spotlight: inset 0 1px 0 #ffffff25, 0 10px 28px #201e1520;
  --ui-shadow-popover: 0 12px 35px #29221826;
  --ui-shadow-modal: 0 24px 64px #1a18142e;

  /* 密度和布局语义。 */
  --ui-control-height-small: 32px;
  --ui-control-height: 40px;
  --ui-control-height-large: 48px;
  --ui-control-min-touch: 44px;
  --ui-icon-small: 16px;
  --ui-icon: 20px;
  --ui-icon-large: 24px;
  --ui-table-header-height: 40px;
  --ui-table-row-height: 64px;
  --ui-table-detail-row-height: 80px;
  --ui-form-field-gap: var(--ui-space-6);
  --ui-form-max-width: 640px;
  --ui-login-form-width: 384px;
  --ui-dialog-width: 480px;
  --ui-drawer-width: 740px;
  --ui-layout-outer-gap: var(--ui-space-4);
  --ui-layout-nav-collapsed-width: 72px;
  --ui-layout-nav-width: 240px;
  --ui-layout-nav-left: var(--ui-layout-outer-gap);
  --ui-layout-main-offset: calc(var(--ui-layout-nav-left) + var(--ui-layout-nav-width) + var(--ui-layout-outer-gap));
  --ui-layout-content-max: 1440px;
  --ui-layout-page-padding: var(--ui-space-8);
  --ui-layout-card-padding: var(--ui-space-6);
  --ui-layout-grid-gap: var(--ui-space-5);
  --ui-layout-topbar-height: 64px;
  --ui-layout-focus-columns: minmax(0, 1.7fr) minmax(280px, 1fr);
  --ui-layout-analysis-columns: minmax(0, 1.5fr) minmax(0, 1fr);
  --ui-layout-kpi-columns: repeat(4, minmax(0, 1fr));
  --ui-layout-form-columns: repeat(2, minmax(0, 1fr));
  --ui-layout-login-columns: 1.04fr 1fr;
  --ui-layout-nav-position: fixed;

  /* 层级：Element Plus 浮层从 overlay-base 开始，由库管理增量。 */
  --ui-z-content: 0;
  --ui-z-sticky: 10;
  --ui-z-navigation: 20;
  --ui-z-overlay-base: 2000;

  /* CSS 用 ms；GSAP 读取后统一转换为秒。 */
  --ui-motion-instant: 0ms;
  --ui-motion-fast: 160ms;
  --ui-motion-base: 240ms;
  --ui-motion-enter: 600ms;
  --ui-motion-chart: 900ms;
  --ui-motion-stagger: 60ms;
  --ui-motion-lift: -4px;
  --ui-motion-enter-y: 16px;
  --ui-motion-ease: cubic-bezier(0.22, 1, 0.36, 1);
  --ui-motion-ease-gsap: power3.out;
  --ui-motion-ease-chart-gsap: power2.inOut;
  --ui-loading-leading: 200ms;
  --ui-loading-trailing: 150ms;
  --ui-skeleton-duration: 1400ms;
}

/* 单栏导航的状态与响应式变量仍在唯一值源中维护。 */
:root {
  --ui-nav-label-display: block;
  --ui-nav-group-display: flex;
  --ui-nav-display: block;
  --ui-nav-items-display: block;
  --ui-nav-item-direction: row;
  --ui-nav-columns: none;
  --ui-nav-item-padding: var(--ui-space-3);
  --ui-nav-meta-display: flex;
  --ui-nav-toggle-display: flex;
  --ui-nav-height: calc(100dvh - var(--ui-layout-outer-gap) * 2);
  --ui-nav-margin: 0;
  --ui-nav-logo-size: 32px;
}
.workspace[data-nav-collapsed="true"] {
  --ui-layout-nav-width: var(--ui-layout-nav-collapsed-width);
  --ui-nav-label-display: none;
  --ui-nav-group-display: none;
  --ui-nav-meta-display: none;
}
.workspace[data-group-collapsed="true"][data-nav-collapsed="false"] {
  --ui-nav-items-display: none;
}
.workspace {
  --ui-layout-main-offset: calc(var(--ui-layout-nav-left) + var(--ui-layout-nav-width) + var(--ui-layout-outer-gap));
}
/* 断点也是集中设计决策；不要把这些查询再复制到业务页面。 */
@media (width <= 1250px) {
  :root {
    --ui-layout-focus-columns: minmax(0, 1fr);
    --ui-layout-analysis-columns: minmax(0, 1fr);
    --ui-layout-kpi-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (width <= 1000px) {
  :root {
    --ui-layout-nav-width: 240px;
    --ui-layout-page-padding: var(--ui-space-6);
    --ui-layout-form-columns: minmax(0, 1fr);
  }
}
@media (width <= 700px) {
  :root {
    --ui-layout-nav-position: static;
    --ui-layout-nav-width: auto;
    --ui-layout-main-offset: 0px;
    --ui-layout-page-padding: var(--ui-space-4);
    --ui-layout-card-padding: var(--ui-space-5);
    --ui-layout-grid-gap: var(--ui-space-3);
    --ui-layout-login-columns: minmax(0, 1fr);
    --ui-font-size-page-title: 1.5rem;
    --ui-font-size-card-title: 1.25rem;
    --ui-font-size-brand: 2.5rem;
    --ui-font-size-input: 1rem;
    --ui-control-height: var(--ui-control-min-touch);
  }
}
@media (width <= 700px) {
  .workspace, .workspace[data-nav-collapsed="true"], .workspace[data-group-collapsed="true"][data-nav-collapsed="false"] {
    --ui-layout-nav-width: auto;
    --ui-layout-main-offset: 0px;
    --ui-nav-label-display: block;
    --ui-nav-group-display: none;
    --ui-nav-meta-display: none;
    --ui-nav-toggle-display: none;
    --ui-nav-display: grid;
    --ui-nav-items-display: contents;
    --ui-nav-columns: repeat(3, minmax(0, 1fr));
    --ui-nav-item-direction: column;
    --ui-nav-item-padding: var(--ui-space-2);
    --ui-nav-height: auto;
    --ui-nav-margin: var(--ui-layout-outer-gap);
  }
}
@media (pointer: coarse) {
  :root {
    --ui-control-height-small: var(--ui-control-min-touch);
    --ui-control-height: var(--ui-control-min-touch);
  }
}
@media (prefers-reduced-motion: reduce) {
  :root {
    --ui-motion-fast: var(--ui-motion-instant);
    --ui-motion-base: var(--ui-motion-instant);
    --ui-motion-enter: var(--ui-motion-instant);
    --ui-motion-chart: var(--ui-motion-instant);
    --ui-motion-stagger: var(--ui-motion-instant);
    --ui-motion-lift: 0px;
    --ui-motion-enter-y: 0px;
  }
}
```

### 3.2 使用语义

| 内容 | token 与限制 |
| --- | --- |
| 普通正文、表单、表格主内容 | `font-size-body`、`line-height-body`、`color-text`；不能用 caption 把整页缩小 |
| 日期、来源、次要解释 | `font-size-caption` 或 `font-size-small`；真实信息不得低于 caption |
| 页面标题 | `font-size-page-title`、`font-weight-bold`；一页一个 h1 |
| 卡片／区段标题 | 分别使用 `font-size-card-title`、`font-size-section`，不能每张卡都用页面标题 |
| KPI | `font-size-kpi`；单位使用 caption，数字设置 `font-variant-numeric: tabular-nums` |
| 登录品牌区 | `font-size-brand`；限制在登录／品牌场景，不进入日常表格页面 |
| 标签、提示文案 | 颜色按语义成对选取前景和背景，文字可读，不仅靠颜色传达状态 |
| 组件内部间距 | 紧邻图标／文本 space-2；字段与区段 space-3 至 space-6；页级间距使用 layout token |
| 长文 | `line-height-prose`，正常自动换行；不对整段中文增加字距或居中 |

Demo 的少量装饰性小字、非标准间距和历史覆盖样式不直接照搬。正式工程将正文统一到 body、辅助信息至少 caption；保留风格，不追求逐像素复制 Demo 的所有数值。

颜色验收按最终合成背景检查：普通文字至少 4.5:1，大号文字至少 3:1；玻璃与渐变必须检查最不利背景。阈值来自 [W3C 对比度说明](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)。装饰边缘与输入边框不是同一种语义：浅色 card border 可以柔和，控件边界和焦点必须清楚。

## 四、工程落点与 Element Plus 主题

### 4.1 只建立实际需要的文件

```text
frontend/src/
  styles/
    tokens.css                 唯一设计数值来源，含集中响应式覆盖
    element-plus.css           --el-* 映射与必要的库样式适配
    base.css                   根字体、页面底色、焦点、排版与少量重置
  shared/
    utils/readUiToken.ts       实际出现 GSAP／图表数值消费时才建立
    components/                下表确认需要共享的展示组件
  layouts/                     导航与登录壳，按架构实际建立
  features/                    业务组件、页面及各自状态映射
```

不现在创建空目录、主题切换 store、TokenProvider、万能样式 hooks 或自动生成平台。主题应在整个应用及传送到 body 的弹层上生效，不能只挂在某个页面根节点。

导入顺序：项目 token → Element Plus 官方组件样式 → 项目 `element-plus.css` → `base.css` → 页面／组件样式。使用显式组件导入时，后续懒加载仍可能追加官方 CSS；适配层在根节点使用统一且适度更高的选择器，例如 `html:root`，并通过一次“直接打开懒加载页面 + 打开其弹层”的检查验证覆盖顺序。不要靠不断添加 `!important` 解决主题失效。

### 4.2 基础映射示例

下面示例只引用第三节的值源，放入 `styles/element-plus.css`；并非把第三方所有 CSS 复制到项目。

```css
html:root {
  --el-color-primary: var(--ui-color-action);
  --el-color-primary-dark-2: var(--ui-color-action-active);
  --el-color-primary-light-3: var(--ui-color-action-tint-3);
  --el-color-primary-light-5: var(--ui-color-action-tint-5);
  --el-color-primary-light-7: var(--ui-color-action-tint-7);
  --el-color-primary-light-8: var(--ui-color-action-tint-8);
  --el-color-primary-light-9: var(--ui-color-selected-bg);
  --el-color-success: var(--ui-color-success);
  --el-color-warning: var(--ui-color-warning);
  --el-color-danger: var(--ui-color-danger);
  --el-color-info: var(--ui-color-info);
  --el-bg-color: var(--ui-color-surface);
  --el-bg-color-page: var(--ui-color-canvas);
  --el-bg-color-overlay: var(--ui-color-surface);
  --el-text-color-primary: var(--ui-color-text);
  --el-text-color-regular: var(--ui-color-text-secondary);
  --el-text-color-secondary: var(--ui-color-text-muted);
  --el-text-color-placeholder: var(--ui-color-text-muted);
  --el-border-color: var(--ui-color-control-border);
  --el-border-color-light: var(--ui-color-border);
  --el-fill-color-blank: var(--ui-color-surface);
  --el-fill-color-light: var(--ui-color-surface-muted);
  --el-mask-color: var(--ui-color-overlay);
  --el-font-family: var(--ui-font-family-body);
  --el-font-size-base: var(--ui-font-size-body);
  --el-font-size-small: var(--ui-font-size-small);
  --el-font-size-extra-small: var(--ui-font-size-caption);
  --el-font-size-medium: var(--ui-font-size-emphasis);
  --el-font-size-large: var(--ui-font-size-section);
  --el-font-size-extra-large: var(--ui-font-size-card-title);
  --el-border-radius-base: var(--ui-radius-control);
  --el-border-radius-small: var(--ui-radius-small);
  --el-border-radius-round: var(--ui-radius-pill);
  --el-border-radius-circle: var(--ui-radius-circle);
  --el-component-size: var(--ui-control-height);
  --el-component-size-small: var(--ui-control-height-small);
  --el-component-size-large: var(--ui-control-height-large);
  --el-box-shadow: var(--ui-shadow-popover);
  --el-box-shadow-light: var(--ui-shadow-glass);
  --el-box-shadow-lighter: var(--ui-shadow-glass);
  --el-box-shadow-dark: var(--ui-shadow-modal);
  --el-transition-duration: var(--ui-motion-base);
  --el-transition-duration-fast: var(--ui-motion-fast);
}
```

色阶也在 token 值源定义，适配层只引用，页面禁止自行混色。正式接入还须对**实际使用的** success／warning／danger／info 标签色阶、disabled、Input、Select、Table、Skeleton 与浮层局部变量做完整映射；不能只改主色，留下默认蓝色、绿底或白字浅橙按钮。数值 props（例如 Config Provider 的 `zIndex`、骨架 throttle）通过 token 读取函数传入。`primary-hover` 的具体按钮适配使用 `color-action-hover`，不依赖库默认浅色悬停导致对比度下降。

变量名称以锁定版本的 Element Plus CSS 为准，升级时核对；官方支持的主题入口见 [Element Plus Theming](https://element-plus.org/en-US/guide/theming.html)。第三方基础实现无需彻底重写为项目 token，但项目有意设置的每一处视觉覆盖必须来自 token。

### 4.3 页面消费示例

```css
.focus-board {
  display: grid;
  grid-template-columns: var(--ui-layout-focus-columns);
  gap: var(--ui-layout-grid-gap);
}
.display-card {
  padding: var(--ui-layout-card-padding);
  border: var(--ui-border-width) solid var(--ui-color-glass-edge);
  border-radius: var(--ui-radius-card);
  background: var(--ui-surface-glass);
  color: var(--ui-color-text);
  box-shadow: var(--ui-shadow-glass);
  backdrop-filter: blur(var(--ui-glass-blur)) saturate(var(--ui-glass-saturation));
  transition: translate var(--ui-motion-base) var(--ui-motion-ease),
              box-shadow var(--ui-motion-base) var(--ui-motion-ease);
}
@media (hover: hover) and (pointer: fine) {
  .display-card:hover {
    translate: 0 var(--ui-motion-lift);
    box-shadow: var(--ui-shadow-hover);
  }
}
.display-card:focus-visible {
  outline: var(--ui-focus-ring);
  outline-offset: var(--ui-focus-offset);
}
@supports not (backdrop-filter: blur(1px)) {
  .display-card { background: var(--ui-color-surface); }
}
```

`@supports` 中的探测值不是设计尺寸。减少动态效果时除将时长与位移置零，还应禁用骨架 shimmer 等无限关键帧，避免零时长循环。单纯展示的卡片不加无意义的 Tab 停靠点；可点击整卡用原生 button／link，卡片中已有多项操作时保持 article 容器，不嵌套交互元素。

## 五、布局与信息层级

### 5.1 组织工作区

- 左侧只保留一个深色悬浮侧栏，顶部放一处品牌及组织切换，中部承载菜单，底部放一处账号入口；移除第二条常驻图标栏和工作区品牌标语。展开时显示图标与文字及必要子菜单，折叠时同一侧栏变为窄图标岛，两种形态不同时出现。
- 侧栏宽度通过展开／折叠按钮切换，按钮有可访问名称与展开状态；折叠后叶子入口可直接访问并提供名称提示，组织仍可切换。展开恢复原子菜单状态。手机不保留窄栏，使用同一导航的紧凑文档流布局。二级目录最多一层展开，即总计两级；使用 Element Plus Menu／SubMenu 或等价的既有组件能力，统一键盘与折叠行为，不重写树形导航引擎。
- 顶部显示当前组织、面包屑、必要站内提示和账号菜单。演示中的“重播骨架”“登录预览”不得进入正式用户菜单。
- 当前组织由 URL 决定，切换时的请求、未保存提示和权限处理遵守前端架构。导航选中只表达当前位置，不作为权限凭证。
- 左侧固定导航内部可以滚动，底部账号入口保持可达；不能让短屏用户无法到达菜单末尾。
- 页面主滚动由文档承担；表格、抽屉长文在必要时局部滚动。避免全页固定高度和多层滚动容器叠加。

### 5.2 工作台

| 层级 | 内容与规则 |
| --- | --- |
| 页面头 | 页面标题、简短上下文与一个主操作；不放无意义欢迎语轮播 |
| 核心区 | 左侧深色重点活动，右侧暖橙 AI 文稿入口；重点活动显示主状态、时间、负责人／地点和实际报名摘要 |
| KPI 信息带 | 最多四项，数字、单位、统计口径一起出现；能跳转对应明细才做可点击卡片 |
| 分析区 | 最多两张必要图表，不因布局留白强加图表；无真实统计接口时不出示伪造趋势 |
| 明细区 | 表格、筛选、分页与必要操作；本人待办与全组织管理视图明确区分 |

以上是组织负责人／活动管理视图的视觉层级，实际数据范围遵循计划书；普通成员以本人待办、报名和提示为中心，不展示内部经费或 AI 管理卡。无权创建活动时不展示创建入口；无权生成时不请求 AI 候选数据。

工作台不能把“报名”插成活动主状态；筹备期间的报名开关与活动状态分别显示。没有近期活动时可展示创建／浏览入口，不能假造倒计时。没有可总结或可生成方案的活动时，AI 卡解释原因并提供符合权限的下一步。

### 5.3 业务页与详情

活动列表以列表、筛选和创建为中心；长方案、复盘等完整编辑使用可直达的业务路由。抽屉适合快速查看、短编辑与预览，不把所有功能塞进一个无限长抽屉。

详情页签按权限与功能组织为概览、方案、任务、报名、经费、物资、复盘、资料；只展示可用区段，不一次请求所有页签。选择和 URL 可恢复，浏览器前进／后退有效。移动端页签可以横向滚动，不能挤成不可读的小字。

### 5.4 登录与公开参与页

登录采用左右分屏：左边为品牌文字与一处活动主题图形，右边为有明确标签的表单；不展示未授权的真实组织资料。正式登录不预填演示密码，也不附带跳过登录入口。注册和恢复入口仅对应计划书已有能力。

手机按顺序显示压缩的品牌区与表单，装饰可以隐藏，字段与错误不能隐藏。公开活动页面独立于组织管理壳，不为了复用外观暴露内部导航、预算、成员或附件。

### 5.5 响应式与内容压力

集中断点的三次变化分别承担：主卡与图表转单列、表单转单列、单个组织导航进入正常文档流并取消桌面折叠形态。KPI 在手机为两列；极长文字允许增加高度，不截断关键数字。

至少检查 1440、1024、768、390 与 320 CSS 像素宽度，及短屏、200% 缩放。屏幕宽度是验收输入，不是可以在页面新增的断点。弹窗、抽屉宽度使用 `min(100%, var(--ui-drawer-width))` 等结构式与布局 token；考虑安全区 `env(safe-area-inset-*)`。

固定尺寸只用于边界和最小可操作面积。按钮文字变长、状态变多、中文姓名变长、长数字和错误出现时，页面仍能换行或在指定区段滚动，不能整体横向溢出。

## 六、组件使用与封装边界

### 6.1 直接使用组件库

| 场景 | 采用方式 |
| --- | --- |
| 操作 | ElButton；图标按钮有可访问名称，危险操作语义明确 |
| 表单 | ElForm、ElFormItem、Input、InputNumber、DatePicker、Checkbox、Radio 等，按数据类型选用 |
| 多选／选择 | Select、Cascader、TreeSelect 按真实数据关系选择，不为外观伪造层级 |
| 明细 | ElTable、Pagination、Tag、Empty；只组合，不包装同名 BaseTable |
| 弹层 | Dialog、Drawer、MessageBox、Popover、Tooltip，保留焦点管理与键盘行为 |
| 加载 | Skeleton／SkeletonItem、Button loading；统一外观与触发策略 |
| 导航 | Menu／SubMenu、Breadcrumb、Dropdown 等已有能力 |
| 文件 | Upload 通过已约定的 ActivityAttachments 接入共同 HTTP 协议 |

金额输入使用 ElInput 保存十进制字符串并按接口精度校验；不因组件库提供 InputNumber 就将金额经 Number 转换后传输。数量等普通数值才按其类型选用 InputNumber。

不创建 BaseButton、BaseInput、BaseDialog、BaseSelect 等只转发参数或换色的包装。统一主题与默认配置能够解决的问题不再用组件套一层。

### 6.2 封装顺序和所有者

| 时机 | 所有者／组件 | 复用内容 | 不包含 |
| --- | --- | --- | --- |
| 首个组织页面 | `layouts/OrganizationLayout` | 单个可折叠悬浮侧栏、子菜单承载、面包屑、主滚动与插槽 | 业务权限推断、每个模块数据加载 |
| 首个账号页面 | `layouts/AuthLayout` | 分屏结构、品牌区与表单插槽 | 登录／注册字段规则与接口 |
| 第一对实际展示卡 | `shared/components/SurfaceCard` | 按实际使用逐步支持 solid／glass／spotlight／assistant 语义表面、统一 padding／radius、标题与内容插槽 | API、业务跳转、统计计算、任意 color／shadow／padding 数值 props |
| 账号与活动表单产生相同排版后 | 已复用的表单区段 | 标签、帮助、错误占位等确实重复的排版 | 通用表单 schema 引擎或几十个布局开关 |
| 组织切换首次实现 | `organization/OrganizationSwitcher` | 可搜索的组织选择、头像／成员摘要展示，发出选择事件 | 自行保存另一个 currentOrgId |
| 成员选择首次实现 | `organization/MemberSelect` | 当前组织有效成员、选中值与加载／空状态 | 在组件里赋权或跨组织缓存 |
| 工作台首次实现 | `dashboard/KpiCard` | 数字、单位、口径、可选跳转与动效；复用多张 KPI | 在浏览器汇总正式全组织统计 |
| 活动主卡首次实现 | `activity/ActivitySpotlight` | 主状态、关键安排与动作入口 | AI 分析、把报名当阶段 |
| 总结与方案接入 | `ai/AiDraftPanel` | 输入范围、新生成草稿、加载／失败、采用事件 | 直接保存方案／总结、自动批准、全局聊天 |
| 两个页面共享加载结构后 | 对应布局或模块的骨架 | 与实际内容近似的占位结构 | 根据 arbitrary schema 生成万能骨架 |

表面组件的 variant 是项目有限语义，不把所有 CSS 属性做成 props。布局不同用插槽或页面组合；需要定制时先判断是否属于业务组件。业务状态映射仍分别归活动、任务、报名、费用等模块，不统一成“全局审批状态”。

## 七、表单与校验

1. 文本输入首次校验默认在 blur。用户还在首次输入时，不逐字显示错误；IME 合成期间不打断输入。
2. Select、日期、开关等离散选择使用 change 校验，因为选择就是完整动作；相关字段依赖变化时重新检查受影响字段。
3. 已出现错误的字段，用户修改后可以进行轻量同步校验并及时消除错误；不每个按键请求服务端。耗时检查在失焦后触发并取消旧请求。
4. 提交必须执行整表校验，不能只相信用户已经触发过 blur。滚动到首个错误并聚焦对应控件；错误就地展示，不把所有错误挤成 toast。
5. 标签常驻且与控件关联；placeholder 只作示例，不代替标签。必填、格式、长度或金额约束在需要时清楚说明。
6. 验证失败、接口失败或结果未知时保留输入。字段错误映射由页面负责；同一次失败只提示一次，详见开发规范。
7. 使用 ElForm 的规则与验证机制；不再实现第二个表单状态引擎。密码允许粘贴与密码管理器，提供显示／隐藏，不自动 trim 密码。
8. 写入期间禁用重复提交并显示 loading；读数据的 loading 不应无理由冻结整页。提交校验失败不是按钮永远禁用的理由。
9. 表单输入高度使用 control token，字段间距使用 form-field-gap。双列仅用于独立短字段；名称、说明、方案和反馈等长文本独占一行。
10. 未保存内容离开、换组织、关闭抽屉或用新 AI 草稿覆盖时遵循统一未保存提示；不通过 localStorage 偷存敏感草稿。

## 八、选择器、菜单与弹层

| 类型 | 交互与外观 |
| --- | --- |
| 组织／成员选择 | 可搜索的 Select，选项可以展示标记、名称与必要摘要；键盘可选、有空结果状态 |
| 少量互斥范围 | Segmented、Tabs 或单选组；例如近 7／30 天，当前项明确 |
| 状态筛选 | Select 或带勾选的单选菜单，名称、当前值、清除入口明确；同类页面保持同一种方式 |
| 操作集合 | DropdownMenu，例如导出、复制链接、更多操作；不能伪装成字段选择 |
| 真实层级数据 | 仅在业务确有层级时使用 Cascader／TreeSelect；当前扁平成员不做伪树 |

“下拉不要只有一种”指按语义选组件，不是同一种筛选每页换一种皮肤。菜单使用库自带展开过渡并接入 token，不同时叠加 GSAP 动画。菜单通过 Esc 关闭、方向键导航，关闭后焦点返回触发器；传送到 body 的菜单仍使用集中主题。

Dialog 用于短编辑或确认；Drawer 用于查看与相对较长的编辑。保留焦点圈定、可访问标题、滚动边界及关闭按钮；未保存时关闭也须处理。`z-index` 由布局 token 与 Element Plus 管理，不在页面写越来越大的数字。

## 九、表格、图表与数据表达

### 9.1 表格

- 主列在左，标识与说明有明确层级，金额右对齐，日期和编号采用等宽数字；关键标题用可点击链接／按钮进入详情。
- 表头、行内边距、最小高度来自 token，内容超长可增高；不要强制固定行高后裁掉多行错误或辅助信息。
- 普通密度为默认。暂不增加全站密度切换器；触控设备优先保证点击区域。
- 筛选在表格上方，分页在下方；已生效筛选可以识别和清除。排序和分页口径来自接口，不对当前页假做全量统计。
- 窄屏保留名称、关键状态与操作，次要信息通过详情承载；必须保留的宽表在本区段横向滚动并提供提示，不缩小整个表格。
- 卡片轻玻璃不应透出滚动行文字。表头固定时使用实色背景，保证不同层内容不重叠。

### 9.2 图表

- 简单进度、单个占比或固定少量点的展示优先采用已确认组件或 SVG；出现多系列、缩放、复杂轴或复杂 tooltip 时才评估成熟图表库，不扩写 Demo 的 SVG 成图表引擎。
- 必须标明单位、时间范围、统计口径、是否包含草稿／取消／归档，以及必要的数据截止时间。
- 主要序列使用 chart-primary，对照序列使用 chart-secondary；不能把普通比较画成成功与失败。多个状态用文字／纹理／标记辅助区分。
- 图表配置所有颜色、字号、描边、尺寸仍读取 token；SVG 的真实数据坐标可以计算，不能用魔法颜色或缩放后不可读的轴标签。
- 金额遵守两位小数合同，默认不擅自四舍五入成整数；如为摘要使用紧凑格式，明细必须可查看完整金额。
- 不把预算减已确认支出叫作“账户余额”，不把已批准报名叫作“到场人数”。零分母显示“—”及解释，不伪造 0% 或 100%。
- 无记录用 Empty／说明；时间区间有完整来源但发生次数为零才显示零曲线。未知、未加载和零不同。
- hover 信息也能通过键盘或点击读取，颜色不作为唯一信息。手机轴标签按最终显示尺寸保证 caption 可读，不能仅设置 viewBox 内字号后任其缩成小字。

## 十、加载、空状态、失败与权限状态

| 状态 | 展示与行为 |
| --- | --- |
| 初次进入尚无内容 | 与页面主结构接近的灰色 Skeleton；有 `aria-busy` 和简短 loading 状态 |
| 很快完成的请求 | 使用 leading 延迟避免骨架闪现；不强行播放完整入场动画后才显示数据 |
| 已有列表刷新 | 保留同一组织的已有结果，并就地显示更新状态；筛选变化结果不能冒充新结果 |
| 切换组织 | 先按架构处理未保存内容，清除旧组织数据，再展示新组织骨架，不能透出旧结果 |
| 按钮提交 | 原位置 loading 与禁重复；保留布局和文字含义，不用整页骨架替代按钮反馈 |
| AI 生成 | 新草稿区域单独加载；已有人工编辑继续保留；允许取消读取／停止等待，不宣称已经撤销服务端生成 |
| 无记录 | 说明“尚未创建”等真实原因，给出符合权限的操作 |
| 筛选无结果 | 保留筛选条件，提供清除筛选，不引导误创建重复数据 |
| 请求失败 | 区段错误与重试入口，保留可保留的数据和表单；不无限转圈 |
| 无权限／资源不存在 | 独立明确状态与返回入口；不残留受限数据摘要 |

Skeleton 使用 Element Plus，自带 shimmer 的持续时间接入 skeleton-duration；leading／trailing 接入 loading token，实际请求状态决定结束。不复制 Demo 的固定等待，也不设置最少等待数秒让动画显得完整。减少动态效果时停止 shimmer，仍保留灰色占位与状态文字。

进入终态后结束 loading；异常、取消、组件卸载与组织切换都要清理计时器及旧请求副作用。骨架只表示结构，不伪造真实用户名、人数或费用。

## 十一、动效规范

| 场景 | 使用与限制 |
| --- | --- |
| 普通 hover／focus | CSS transition，fast／base；卡片可使用 motion-lift，触屏不依赖 hover |
| 卡片入场 | GSAP，enter 与 stagger；同组错峰一次，少量 y 位移与 opacity，内容不依赖动画结束才能操作 |
| KPI 数字 | 首次显示或真实范围变化时过渡到结果；辅助技术读最终值，不能朗读每帧数字 |
| 折线／环图 | GSAP，chart 时长；切换日期只更新图表，不重播整页卡片 |
| 下拉、Dialog、Drawer | Element Plus 自身过渡接入主题，不能再包一套同时作用的 GSAP |
| 骨架 | 组件库 shimmer；减少动态效果时关闭循环 |

同一个元素同一个属性只能由一个动画系统拥有。GSAP 入场使用 transform 时，CSS 悬停可使用独立 translate；或者把两者分在不同包装元素上，不能相互覆盖。

读取规则：`readUiToken` 统一从根 computed style 获取变量，去除空白、校验类型；时间只接受 ms 并转换为 GSAP 秒，位移只接受 px，文本缓动直接读取。`rem` 字号转为图表像素时要结合实际根字号，不能简单 `parseFloat` 当 px。颜色若库不支持 CSS 变量，先经浏览器解析为有效颜色再传入。

不另建一套硬编码 TS 动画常量。媒体偏好可以直接用原生 `matchMedia('(prefers-reduced-motion: reduce)')`；按 [GSAP matchMedia](https://gsap.com/docs/v3/GSAP/gsap.matchMedia()/)／context 管理范围和清理。组织变化或卸载时撤销旧动画，恢复正确数值和可见性；打开减少动效后应直接显示最终内容。

不使用全局无限循环时间线；不为每个表格行创建动画；不动画化大面积 blur、box-shadow 或布局高度来制造“液态”。普通展示只在少量表面保留静态模糊，低性能设备或不支持时降级到实色。

## 十二、AI 文稿界面

AI 工作台卡只是业务入口：总结进入单场活动复盘，方案进入单场草稿的方案编辑。正式模块所有权仍为 review／activity，AiDraftPanel 只生成和发出采用事件，不拥有业务保存。

固定操作顺序：关联活动与权限校验 → 输入范围和缺失项 → 生成新草稿 → 新旧内容分开展示 → 用户采用 → 人工编辑 → 业务保存／确认。再次生成不能覆盖编辑区；采用替换已有文字前确认。失败保留原文，手工路径一直可用。

生成、保存草稿、确认总结、立项批准是不同动作，按钮文案与后端结果一致。无可生成活动时给出原因；不要为了入口常亮允许对已归档活动随意改稿。

正式系统移除“模拟失败”“示例生成”和模板结果；必须接入计划书要求的真实模型调用与故障处理。Demo 文字不作为真实 AI 效果证据，不扩展为周报、全组织运营分析或聊天机器人。

### 12.1 组织模型设置页

“组织设置 → AI 模型”采用标准表单，归 ai 模块；只向组织负责人展示配置入口。协议用 ElSelect，完整 API 地址和模型 ID 用 ElInput，密钥用密码输入；基础字段失焦校验，协议选择即时校验。协议与厂商分开，模型 ID 允许手工填写，不强制从固定厂商列表选择。

显示保存、测试连接、启用／停用和明确的替换／清除密钥动作；保存不自动测试，测试期间原位 loading，并提示会发送短测试文本、可能消耗少量额度。测试结果展示安全的成功／失败说明、模型及时间，不展示原始请求头或上游错误正文。

已配置密钥只显示状态，输入留空表示保留，不把星号串作为实际字段值提交。切换地址或协议后提示重新输入密钥；更改调用配置使测试状态失效。测试结果绑定被测配置，不能在修改后继续显示“当前配置可用”。页面不允许自行设置任意请求头或 JSON 模板；高级区仅承载后端明确支持且有上限的参数。

活动页面区分未配置、未启用、配置已变更及调用失败。只有负责人可跳转设置；活动负责人提示联系组织负责人，手工编辑始终可用。已生成的人工内容不随停用清空。样式与其他设置页面共用 token 和 Element Plus，不另建模型配置的独立视觉系统。

## 十三、可访问性与文案

- 图标按钮、菜单触发器、表单和图表点有名称；操作元素支持键盘，焦点始终可见。禁用原因有可读取说明。
- 控件触控区域遵守 control-min-touch；可以保留较小视觉图标，但点击区域不能同样缩小。
- 正文不使用大段全大写或过度字距，中文词语不为对齐强行插空格。长姓名、活动名、日期和金额不靠省略号隐藏全部关键信息。
- 状态文字简洁具体：如“筹备中”“已确认支出”“暂无复盘中的活动”。不写“系统繁忙请稍后”掩盖已知字段错误。
- 不用纯颜色、动画或 hover 作为唯一提示；菜单和抽屉关闭后恢复焦点，弹层不遮挡当前字段错误。
- 成功反馈只在真实操作成功后出现；结果未知不能写“保存成功”。重要错误在用户有机会处理前不自动消失。
- 正式界面移除开发参数、示例开关和私人简称；实现细节留在文档，不进入普通用户流程。

## 十四、开发检查与落地顺序

### 14.1 最小有效门禁

正式工程建立后提供 `npm run check:ui`，与 typecheck、lint、必要测试和 build 一起执行。此名称在正式工程表示 UI 规范门禁，Demo 同名命令仍仅是演示回归，两者不能互作证据。

门禁覆盖项目拥有的 CSS、Vue style、模板内联 style／尺寸 props、SVG、图表配置和动画参数，排除依赖与生成产物：

1. 检查 token 未定义引用、重复定义和循环依赖；集中响应式覆盖属于有意覆盖，不误判成并列值源。
2. 检查值源之外的颜色、字体、间距、圆角、阴影及固定视觉尺寸字面量。结构／绘图数值按第二节判定，不用一个正则把全部数字禁止掉。
3. 优先用已有 ESLint 扩展点和样式解析器完成检查；先覆盖实际写法，再根据真实漏检补充，不先引入整套设计系统治理平台。静态检查不容易准确判断的 props／数据几何由代码审核补足，不宣称工具能理解全部语义。
4. 初次主题接入保留一条浏览器检查，覆盖 Button／Input／Select／Table／Tag／Skeleton／弹层的实际配色、焦点及局部变量；懒加载和 teleported 弹层一起检查。
5. 验证普通、hover、focus、disabled、loading、error、empty、手机、减少动效与不支持 blur 的状态；只测试本次实际变化及相关风险，不每次全量重跑无关场景。

审查时出现越界值应回到 token 或主题适配修复，不能靠全局禁用规则通过。自动检查之外，至少查看实际渲染，防止“没有硬编码但视觉不可读”。

### 14.2 实施顺序

1. P0.2 建立 token、Element Plus 主题映射与 base 样式，先验证首批实际基础组件和弹层，建立静态 token 检查；浏览器检查和库局部适配随组件接入补齐，不先建设全组件演示平台。
2. 随 P1 建立 AuthLayout、OrganizationLayout 与 OrganizationSwitcher，接入真实会话与未保存处理。
3. 随 P2—P6 建立业务页面；仅抽取已经出现的共享表面与业务组件，逐项接入状态、骨架、错误和手机布局。
4. 随 P7 以真实服务端数据实现重点活动、KPI 与图表；未实现的统计不摆样例占位装作已完成。
5. 随 P8 接入真实 AI 生成，沿用本文的草稿操作边界；最后验证典型完整流程。

本次交付为规范与文档同步，不创建正式业务工程，不勾选尚未实现的功能。`UI演示/` 保留为已认可的外观参考，其存量 CSS 尚未全部 token 化；不得直接复制为符合规范的生产源码。后续若继续修改 Demo，也遵守新增样式的 token 规则，并按实际触及范围迁移；正式工程的首个页面不得携带这项原型债务。
