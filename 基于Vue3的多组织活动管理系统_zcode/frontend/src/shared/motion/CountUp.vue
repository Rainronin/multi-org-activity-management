<script lang="ts">
// Vue Bits CountUp 适配；来源及许可见 THIRD_PARTY_NOTICES.md。
const visited = new Set<string>()
</script>
<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { readUiDuration } from '../utils/readUiToken'
import { useMotionPreference } from './useMotionPreference'

const props = defineProps<{ value: string; scopeKey: string }>()
const reduced = useMotionPreference()
const element = ref<HTMLElement>()
const display = ref(props.value)
let frame = 0
let observer: IntersectionObserver | undefined
function finish() {
  cancelAnimationFrame(frame)
  frame = 0
  display.value = props.value
}
function reveal() {
  observer?.disconnect()
  if (visited.has(props.scopeKey) || reduced.value || !/^\d+$/.test(props.value)) return
  if (visited.size >= 256) visited.clear()
  visited.add(props.scopeKey)
  const target = Number(props.value)
  if (!Number.isSafeInteger(target) || target === 0) return
  const duration = readUiDuration('--ui-motion-chart')
  if (!duration) return
  const start = performance.now()
  const tick = (now: number) => {
    const progress = Math.min(1, (now - start) / duration)
    display.value = String(Math.round(target * (1 - (1 - progress) ** 3)))
    if (progress < 1) frame = requestAnimationFrame(tick)
    else finish()
  }
  frame = requestAnimationFrame(tick)
}
const hide = () => { if (document.hidden) finish() }
watch([() => props.value, reduced], finish)
onMounted(() => {
  observer = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting)) reveal()
  })
  if (element.value) observer.observe(element.value)
  document.addEventListener('visibilitychange', hide)
})
onUnmounted(() => {
  finish()
  observer?.disconnect()
  document.removeEventListener('visibilitychange', hide)
})
</script>

<template>
  <span ref="element"><span aria-hidden="true">{{ display }}</span><span class="sr-only">{{ value }}</span></span>
</template>
