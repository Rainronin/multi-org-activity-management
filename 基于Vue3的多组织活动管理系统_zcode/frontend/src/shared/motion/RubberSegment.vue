<script setup lang="ts">
// Vue Bits RubberSegment 的受控业务版：保留滑块过渡，移除拖拽惯性与图标依赖。
import { animate } from 'motion-v'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { readUiBezier, readUiDuration } from '../utils/readUiToken'
import { useMotionPreference } from './useMotionPreference'
const props = defineProps<{ modelValue: string; items: { value: string; label: string }[]; label: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const reduced = useMotionPreference()
const root = ref<HTMLElement>()
const thumb = ref<HTMLElement>()
const index = computed(() => Math.max(0, props.items.findIndex(item => item.value === props.modelValue)))
let animation: ReturnType<typeof animate> | undefined
let observer: ResizeObserver | undefined
function sync() {
  animation?.stop()
  const button = root.value?.querySelectorAll('button')[index.value]
  if (!button || !thumb.value) return
  const x = button.offsetLeft
  thumb.value.style.width = `${button.offsetWidth}px`
  animation = animate(thumb.value, { x }, { duration: reduced.value ? 0 : readUiDuration('--ui-motion-base') / 1000, ease: readUiBezier('--ui-motion-ease') })
}
function choose(value: string) { if (!props.disabled) emit('update:modelValue', value) }
function keydown(event: KeyboardEvent) {
  if (props.disabled || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const length = props.items.length
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? length - 1 : (index.value + (event.key === 'ArrowRight' ? 1 : -1) + length) % length
  const item = props.items[next]
  if (!item) return
  choose(item.value)
  root.value?.querySelectorAll('button')[next]?.focus()
}
watch([index, reduced, () => props.items], () => { void nextTick(sync) })
onMounted(() => { observer = new ResizeObserver(sync); if (root.value) observer.observe(root.value); sync() })
onUnmounted(() => { animation?.stop(); observer?.disconnect() })
</script>

<template>
  <div
    ref="root"
    class="rubber-segment"
    role="radiogroup"
    :aria-label="label"
    @keydown="keydown"
  >
    <span
      ref="thumb"
      class="rubber-segment__thumb"
      aria-hidden="true"
    />
    <button
      v-for="(item, i) in items"
      :key="item.value"
      type="button"
      role="radio"
      :aria-checked="item.value === modelValue"
      :tabindex="i === index ? 0 : -1"
      :disabled="disabled"
      @click="choose(item.value)"
    >
      {{ item.label }}
    </button>
  </div>
</template>

<style scoped>
.rubber-segment { position: relative; display: flex; gap: 0; padding: var(--ui-space-1); border: var(--ui-border-width) solid var(--ui-color-border); border-radius: var(--ui-radius-control); background: var(--ui-color-surface-muted); }
.rubber-segment__thumb { position: absolute; top: var(--ui-space-1); bottom: var(--ui-space-1); left: 0; border-radius: var(--ui-radius-small); background: var(--ui-color-surface); box-shadow: var(--ui-shadow-glass); pointer-events: none; }
.rubber-segment button { position: relative; flex: 1; min-width: 0; min-height: var(--ui-control-height); padding: var(--ui-space-1) var(--ui-space-3); border: 0; border-radius: var(--ui-radius-small); background: transparent; color: var(--ui-color-text-secondary); font-size: var(--ui-font-size-small); cursor: pointer; }
.rubber-segment button[aria-checked="true"] { color: var(--ui-color-action); font-weight: var(--ui-font-weight-semibold); }
.rubber-segment button:disabled { cursor: default; opacity: var(--ui-opacity-disabled); }
</style>
