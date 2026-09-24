<script setup lang="ts">
// 登录、注册、恢复共用的分屏承载壳（UI 规范 5.4 / 6.2）；
// 不含字段规则与接口逻辑，品牌区不展示任何虚构业务数据。
// 品牌标语用 p 而非 h1：一页一个 h1，留给右侧表单标题（UI 规范 3.2）。
</script>

<template>
  <main class="auth-page">
    <section class="auth-brand">
      <!-- 环形装饰：对照 Demo 的登录左侧构图，纯装饰不进可访问树 -->
      <span
        class="auth-decor"
        aria-hidden="true"
      />
      <div class="auth-brand-name">
        <svg
          class="auth-mark"
          viewBox="0 0 32 32"
          aria-hidden="true"
        >
          <path
            fill="currentColor"
            d="M8 8h5L8 27H3zm9-6h5l-7 25h-5z"
          />
          <path
            fill="var(--ui-color-on-dark-accent)"
            d="M23 17h5l-3 10h-5z"
          />
        </svg>
        多组织活动管理
      </div>
      <div class="auth-story">
        <p class="auth-story__eyebrow">
          让协作发生，让想法落地
        </p>
        <p class="auth-slogan">
          好想法，<br class="auth-slogan__break">值得一起<br class="auth-slogan__break">做成<span>。</span>
        </p>
        <p class="auth-story__note">
          从一份活动方案，到一场有回应的行动。<br>把人、计划与每一步进展，放在一起。
        </p>
      </div>
      <!-- 静态产品流程说明：不含日期、人数或具体活动断言 -->
      <div class="auth-flow">
        <span class="auth-flow__label">系统流程</span>
        <div>
          <strong>从方案到复盘，一条主线</strong>
          <span>提交确认 · 筹备协作 · 报名统筹 · 执行复盘</span>
        </div>
        <span class="auth-flow__step">4 个阶段</span>
      </div>
      <footer>
        <span>构思 · 筹备 · 执行 · 复盘</span>
        <span>ACTIVITY WORKSPACE</span>
      </footer>
    </section>
    <section class="auth-panel">
      <div class="auth-content">
        <slot />
      </div>
      <footer><span>多组织活动管理系统</span></footer>
    </section>
  </main>
</template>

<style>
/* 认证页共用排版（登录/注册/恢复三页一致，故集中于此）；选择器统一 auth- 前缀避免泄漏。 */
.auth-eyebrow {
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-caption);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
}

/* 表单区标题是本页唯一的 h1（UI 规范 3.2 要求一页一个 h1）。 */
.auth-heading {
  margin-top: var(--ui-space-2);
  font-size: var(--ui-font-size-page-title);
  font-weight: var(--ui-font-weight-bold);
  line-height: var(--ui-line-height-tight);
  letter-spacing: var(--ui-letter-spacing-title);
}

.auth-intro {
  margin: var(--ui-space-4) 0 var(--ui-space-8);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-body);
}

.auth-error {
  margin-bottom: var(--ui-space-4);
  padding: var(--ui-space-3) var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-danger);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-danger-bg);
  color: var(--ui-color-danger);
  font-size: var(--ui-font-size-small);
}

.auth-submit {
  width: 100%;
  margin-top: var(--ui-space-1);
  border-radius: var(--ui-radius-control);
}

.auth-links {
  display: flex;
  justify-content: space-between;
  gap: var(--ui-space-4);
  margin-top: var(--ui-space-6);
  font-size: var(--ui-font-size-small);
}

.auth-links a {
  color: var(--ui-color-selected-text);
  text-decoration: none;
}

.auth-links a:hover {
  text-decoration: underline;
}

/* 恢复码等一次性结果的展示块：与表单同宽、等宽字体、可换行。 */
.auth-result {
  margin: var(--ui-space-4) 0;
  padding: var(--ui-space-4);
  border: var(--ui-border-width) solid var(--ui-color-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-color-surface-muted);
  font-family: var(--ui-font-family-data);
  font-size: var(--ui-font-size-section);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
  text-align: center;
  overflow-wrap: anywhere;
}

.auth-note {
  margin-top: var(--ui-space-4);
  color: var(--ui-color-text-muted);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-prose);
}
</style>

<style scoped>
.auth-page {
  display: grid;
  grid-template-columns: var(--ui-layout-login-columns);
  min-height: 100vh;
}

.auth-brand {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: var(--ui-auth-padding);
  background: var(--ui-surface-spotlight);
  color: var(--ui-color-on-dark);
  position: relative;
  overflow: hidden;
}

/* 环形装饰：纯 CSS 绘制，不承载信息。 */
.auth-decor {
  position: absolute;
  top: 30%;
  right: var(--ui-auth-decor-offset);
  width: var(--ui-auth-decor-size);
  height: var(--ui-auth-decor-size);
  border: var(--ui-auth-decor-ring) solid var(--ui-color-chart-area);
  border-radius: var(--ui-radius-circle);
  pointer-events: none;
}

.auth-brand-name {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  font-size: var(--ui-font-size-body);
  font-weight: var(--ui-font-weight-semibold);
}

.auth-mark {
  width: var(--ui-nav-logo-size);
  height: var(--ui-nav-logo-size);
  flex-shrink: 0;
}

.auth-story {
  margin: auto 0;
  padding: var(--ui-auth-story-padding);
  position: relative;
}

/* 品牌标语沿用页面标题的字重与字距，但语义是标语段落而不是标题。 */
.auth-slogan {
  font-size: var(--ui-font-size-auth-slogan);
  font-weight: var(--ui-font-weight-bold);
  line-height: var(--ui-line-height-tight);
  letter-spacing: var(--ui-letter-spacing-title);
}

.auth-slogan span {
  color: var(--ui-color-on-dark-accent);
}

.auth-story__eyebrow {
  margin-bottom: var(--ui-space-6);
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-caption);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
}

.auth-story__note {
  margin-top: var(--ui-space-8);
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-prose);
}

/* 静态产品流程卡：对照 Demo 的玻璃信息卡，内容改为通用流程说明。 */
.auth-flow {
  display: flex;
  align-items: center;
  gap: var(--ui-space-6);
  max-width: var(--ui-auth-flow-width);
  margin-bottom: var(--ui-space-16);
  padding: var(--ui-auth-note-padding);
  border: var(--ui-border-width) solid var(--ui-color-glass-edge-dark);
  border-radius: var(--ui-radius-island);
  background: var(--ui-surface-glass-on-dark);
  box-shadow: var(--ui-shadow-spotlight);
  backdrop-filter: blur(var(--ui-auth-decor-blur)) saturate(var(--ui-glass-saturation));
}

.auth-flow__label {
  flex-shrink: 0;
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-caption);
  white-space: nowrap;
}

.auth-flow strong {
  display: block;
  margin: var(--ui-space-2) 0;
  color: var(--ui-color-on-dark);
  font-size: var(--ui-font-size-body);
}

.auth-flow div > span {
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-caption);
}

.auth-flow__step {
  margin-left: auto;
  color: var(--ui-color-on-dark-accent);
  font-family: var(--ui-font-family-data);
  font-size: var(--ui-font-size-caption);
  white-space: nowrap;
}

@supports not (backdrop-filter: blur(1px)) {
  .auth-flow {
    background: var(--ui-color-spotlight-end);
  }
}

.auth-brand footer,
.auth-panel footer {
  display: flex;
  justify-content: space-between;
  gap: var(--ui-space-3);
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-caption);
}

.auth-brand footer > span:last-child {
  font-family: var(--ui-font-family-data);
  letter-spacing: var(--ui-letter-spacing-eyebrow);
}

.auth-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: var(--ui-auth-padding);
  background: var(--ui-color-surface-muted);
}

.auth-panel footer {
  color: var(--ui-color-text-muted);
}

.auth-content {
  width: 100%;
  min-width: 0;
  max-width: var(--ui-login-form-width);
  margin: auto;
  padding: var(--ui-auth-form-padding);
}

/* 手机：装饰与页脚让位，字段与错误一个不隐藏，页面允许滚动。 */
@media (width <= 700px) {
  .auth-decor,
  .auth-flow,
  .auth-brand footer {
    display: none;
  }

  .auth-slogan__break {
    display: none;
  }

  .auth-story {
    margin: 0;
  }

  /* 手机主滚动交给文档，品牌区只占顶部一段。 */
  .auth-page {
    display: block;
    min-height: 100dvh;
  }

  .auth-brand {
    min-height: auto;
  }

  .auth-panel {
    min-height: auto;
  }
}
</style>
