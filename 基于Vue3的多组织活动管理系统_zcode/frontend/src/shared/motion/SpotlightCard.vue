<script setup lang="ts">
// Vue Bits SpotlightCard 适配；来源及许可见 THIRD_PARTY_NOTICES.md。
import { ref } from 'vue'
import { useMotionPreference } from './useMotionPreference'
const reduced = useMotionPreference()
const position = ref({ x: 50, y: 50 })
const active = ref(false)
const focused = ref(false)
function move(event: PointerEvent) {
  if (reduced.value || event.pointerType !== 'mouse' || focused.value) return
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  position.value = { x: (event.clientX - rect.left) / rect.width * 100, y: (event.clientY - rect.top) / rect.height * 100 }
  active.value = true
}
function focus() { focused.value = true; position.value = { x: 50, y: 50 } }
</script>

<template>
  <article
    class="motion-spotlight"
    @pointermove="move"
    @pointerleave="active = false"
    @focusin="focus"
    @focusout="focused = false"
  >
    <span
      class="motion-spotlight__light"
      aria-hidden="true"
      :class="{ visible: !reduced && (active || focused) }"
      :style="{ '--spot-x': `${position.x}%`, '--spot-y': `${position.y}%` }"
    />
    <slot />
  </article>
</template>

<style scoped>
.motion-spotlight { position: relative; isolation: isolate; overflow: hidden; }
.motion-spotlight__light { position: absolute; inset: 0; z-index: var(--ui-z-content); pointer-events: none; border-radius: inherit; background: radial-gradient(circle at var(--spot-x) var(--spot-y), var(--ui-color-glass-on-dark), transparent 70%); opacity: 0; transition: opacity var(--ui-motion-base) var(--ui-motion-ease); }
.motion-spotlight__light.visible { opacity: 1; }
</style>
