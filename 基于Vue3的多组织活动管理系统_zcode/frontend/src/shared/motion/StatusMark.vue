<script setup lang="ts">
// Vue Bits StatusMark SVG 适配；不使用划线，未知结果单独显示。
defineProps<{ state: 'running' | 'done' | 'failed' | 'unknown'; label: string }>()
</script>

<template>
  <span
    class="status-mark"
    :data-state="state"
    role="status"
  >
    <svg
      viewBox="0 0 24 24"
      aria-hidden="true"
    >
      <circle
        cx="12"
        cy="12"
        r="9"
      />
      <path
        v-if="state === 'done'"
        d="M7.5 12.25 10.5 15.25 16.75 8.75"
        pathLength="1"
      />
      <path
        v-else-if="state === 'failed'"
        d="M8.5 8.5 15.5 15.5M15.5 8.5 8.5 15.5"
      />
      <path
        v-else-if="state === 'unknown'"
        d="M12 7v6m0 3v1"
      />
    </svg>
    {{ label }}
  </span>
</template>

<style scoped>
.status-mark { display: inline-flex; align-items: center; gap: var(--ui-space-2); color: var(--ui-color-text-secondary); font-size: var(--ui-font-size-small); }
.status-mark svg { flex-shrink: 0; width: var(--ui-icon); height: var(--ui-icon); fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
.status-mark[data-state="done"] { color: var(--ui-color-success); }
.status-mark[data-state="failed"] { color: var(--ui-color-danger); }
.status-mark[data-state="unknown"] { color: var(--ui-color-warning); }
.status-mark[data-state="running"] circle { stroke-dasharray: 38 20; transform-origin: center; animation: status-spin var(--ui-skeleton-duration) linear infinite; }
.status-mark[data-state="done"] path { stroke-dasharray: 1; animation: status-draw var(--ui-motion-base) var(--ui-motion-ease); }
@keyframes status-spin { to { transform: rotate(360deg); } }
@keyframes status-draw { from { stroke-dashoffset: 1; } to { stroke-dashoffset: 0; } }
@media (prefers-reduced-motion: reduce) { .status-mark[data-state] circle, .status-mark[data-state] path { animation: none; } }
</style>
