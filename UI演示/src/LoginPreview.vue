<script setup lang="ts">
import { ref } from 'vue'
import { ElButton, ElForm, ElFormItem, ElInput, type FormInstance } from 'element-plus'
import { ArrowRight, Lock, User } from '@element-plus/icons-vue'
const emit = defineEmits<{ enter: [] }>()
const form = ref<FormInstance>()
const fields = ref({ account: 'demo', password: 'demo2026' })
const rules = {
  account: [{ required: true, whitespace: true, message: '请输入演示账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入演示密码', trigger: 'blur' }, { min: 8, message: '演示密码至少 8 位', trigger: 'blur' }],
}
async function enter() {
  if (await form.value?.validate().catch(() => false)) emit('enter')
}
</script>
<template>
  <main class="login-page">
    <section class="login-brand">
      <div class="login-brand-name"><span class="brand-mark"><span></span><span></span><span></span></span>多组织活动管理</div>
      <div class="login-story"><p class="eyebrow">让协作发生，让想法落地</p><h1>好想法，<br>值得一起<br>做成<span>。</span></h1><p>从一份活动方案，到一场有回应的行动。<br>把人、计划与每一步进展，放在一起。</p></div>
      <div class="login-glass-note"><span class="note-date">09<span> / 19</span></span><div><small>下一场共同期待</small><strong>让旧书，遇见新朋友</strong><span>图书馆南广场 · 14:00</span></div><ArrowRight /></div>
      <footer>构思 · 筹备 · 执行 · 复盘<span>ACTIVITY WORKSPACE</span></footer>
    </section>
    <section class="login-form-panel">
      <button class="back-preview" @click="emit('enter')">返回工作台预览<ArrowRight /></button>
      <div class="login-form-content"><p class="eyebrow">欢迎回来</p><h2>继续，把事情做好。</h2><p class="login-intro">登录你的工作空间，与组织保持同步。</p>
        <ElForm ref="form" :model="fields" :rules="rules" label-position="top" @submit.prevent="enter">
          <ElFormItem label="演示账号" prop="account"><ElInput v-model="fields.account" :prefix-icon="User" autocomplete="off" placeholder="请输入演示账号" /></ElFormItem>
          <ElFormItem label="演示密码" prop="password"><ElInput v-model="fields.password" :prefix-icon="Lock" type="password" show-password autocomplete="off" placeholder="至少 8 位" /></ElFormItem>
          <ElButton type="primary" native-type="submit" class="login-submit">进入演示工作台<ArrowRight /></ElButton>
        </ElForm>
        <div class="login-demo-note"><span class="tiny-dot"></span>预填内容仅用于界面演示，不进行账号验证，也不保存凭据。</div>
      </div>
      <footer>多组织活动管理系统<span>UI 风格评审</span></footer>
    </section>
  </main>
</template>
