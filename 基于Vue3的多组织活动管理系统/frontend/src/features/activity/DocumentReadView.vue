<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ text: string }>()
// 仅识别独占一行的章节标题；其余原文按纯文本展示，不执行 HTML 或 Markdown。
const blocks = computed(() => props.text.split(/\r?\n/).map(line => ({
  text: line,
  heading: /^(?:#{1,3}\s+|[一二三四五六七八九十百]+[、．.]\s*|第[一二三四五六七八九十百0-9]+[章节]\s*)[^。！？]{1,48}$/.test(line.trim()),
})))
</script>
<template>
  <article
    class="document-read"
    aria-label="活动方案正文"
  >
    <div class="document-read__meta">
      <span>方案正文</span><span>已保存内容</span>
    </div>
    <template v-if="text.trim()">
      <template
        v-for="(block, index) in blocks"
        :key="index"
      >
        <h3 v-if="block.heading">
          {{ block.text }}
        </h3>
        <p v-else-if="block.text.trim()">
          {{ block.text }}
        </p>
        <div
          v-else
          class="document-read__break"
          aria-hidden="true"
        />
      </template>
    </template>
    <p
      v-else
      class="document-read__empty"
    >
      暂未填写活动方案。
    </p>
  </article>
</template>
<style scoped>
.document-read { width: 100%; max-width: var(--ui-document-reading-width); margin: 0 auto; padding: var(--ui-layout-card-padding); border: var(--ui-border-width) solid var(--ui-color-border); border-radius: var(--ui-radius-control); background: var(--ui-color-surface-muted); overflow-wrap: anywhere; }
.document-read__meta { display: flex; justify-content: space-between; gap: var(--ui-space-3); padding-bottom: var(--ui-space-4); margin-bottom: var(--ui-space-5); border-bottom: var(--ui-border-width) solid var(--ui-color-border); color: var(--ui-color-text-muted); font-size: var(--ui-font-size-caption); }
.document-read h3 { margin: var(--ui-space-6) 0 var(--ui-space-3); padding-left: var(--ui-space-3); border-left: var(--ui-space-1) solid var(--ui-color-action); font-size: var(--ui-font-size-emphasis); color: var(--ui-color-text); }
.document-read p { white-space: pre-wrap; line-height: var(--ui-line-height-prose); color: var(--ui-color-text-secondary); margin-bottom: var(--ui-space-2); }
.document-read__break { height: var(--ui-space-2); }
.document-read__empty { padding: var(--ui-space-8) 0; text-align: center; }
</style>
