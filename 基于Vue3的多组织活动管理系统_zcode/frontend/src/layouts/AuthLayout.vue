<script setup lang="ts">
// 登录、注册、恢复共用的分屏承载壳（UI 规范 5.4 / 6.2）；
// 不含字段规则与接口逻辑，品牌区不展示任何虚构业务数据。
</script>

<template>
  <main class="auth-page">
    <section class="auth-brand">
      <div class="auth-brand-name">
        <span
          class="auth-mark"
          aria-hidden="true"
        ><span /><span /><span /></span>
        多组织活动管理
      </div>
      <div class="auth-story">
        <h1>好想法，<br>值得一起<br>做成<span>。</span></h1>
        <p>从一份活动方案，到一场有回应的行动。<br>把人、计划与每一步进展，放在一起。</p>
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

.auth-heading {
  margin-top: var(--ui-space-2);
  font-size: var(--ui-font-size-page-title);
  font-weight: var(--ui-font-weight-bold);
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
  margin-top: var(--ui-space-2);
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
  padding: var(--ui-layout-page-padding);
  background: var(--ui-surface-spotlight);
  color: var(--ui-color-on-dark);
}

.auth-brand-name {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  font-size: var(--ui-font-size-body);
  font-weight: var(--ui-font-weight-semibold);
}

.auth-mark {
  display: inline-flex;
  gap: var(--ui-space-1);
}

.auth-mark span {
  width: var(--ui-space-2);
  height: var(--ui-space-2);
  border-radius: var(--ui-radius-circle);
  background: var(--ui-color-on-dark);
}

.auth-mark span:last-child {
  background: var(--ui-color-on-dark-accent);
}

.auth-story {
  margin: auto 0;
}

.auth-story h1 {
  font-size: var(--ui-font-size-brand);
  font-weight: var(--ui-font-weight-bold);
  line-height: var(--ui-line-height-tight);
  letter-spacing: var(--ui-letter-spacing-title);
}

.auth-story h1 span {
  color: var(--ui-color-on-dark-accent);
}

.auth-story p {
  margin-top: var(--ui-space-8);
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-small);
  line-height: var(--ui-line-height-prose);
}

.auth-brand footer,
.auth-panel footer {
  display: flex;
  justify-content: space-between;
  color: var(--ui-color-on-dark-muted);
  font-size: var(--ui-font-size-caption);
}

.auth-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: var(--ui-layout-page-padding);
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
}
</style>
