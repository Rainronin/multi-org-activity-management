<script setup lang="ts">
import { onMounted, onUnmounted, ref, shallowRef, watch, type Component } from 'vue'
import { readUiLength } from '../utils/readUiToken'
import { useMotionPreference } from './useMotionPreference'
defineProps<{ formFocused: boolean }>()
const reduced = useMotionPreference()
const scene = shallowRef<Component>()
const wide = ref(false)
const paused = ref(false)
const failed = ref(false)
let alive = true
let query: MediaQueryList | undefined
let loading = false
const syncWidth = () => { wide.value = query?.matches ?? false }
async function load() {
  if (reduced.value || !wide.value || scene.value || loading || failed.value) return
  loading = true
  try {
    const module = await import('./BrandBeams.vue')
    if (alive) scene.value = module.default
  } catch { if (alive) failed.value = true }
  finally { loading = false }
}
watch([reduced, wide], load)
onMounted(() => {
  query = window.matchMedia(`(min-width: ${readUiLength('--ui-motion-desktop-min')}px)`)
  syncWidth()
  query.addEventListener('change', syncWidth)
  void load()
})
onUnmounted(() => { alive = false; query?.removeEventListener('change', syncWidth) })
</script>

<template>
  <template v-if="wide && !reduced && !failed">
    <component
      :is="scene"
      v-if="scene"
      :paused="paused || formFocused"
      @failed="failed = true"
    />
    <button
      v-if="scene"
      class="atmosphere-toggle"
      type="button"
      :aria-pressed="paused"
      @click="paused = !paused"
    >
      {{ paused ? '播放背景' : '暂停背景' }}
    </button>
  </template>
</template>

<style scoped>
.atmosphere-toggle { position: absolute; top: var(--ui-space-8); right: var(--ui-space-8); z-index: var(--ui-z-sticky); padding: var(--ui-space-2) var(--ui-space-3); min-height: var(--ui-control-min-touch); border: var(--ui-border-width) solid var(--ui-color-border-dark); border-radius: var(--ui-radius-control); color: var(--ui-color-on-dark); background: var(--ui-color-spotlight-end); font-size: var(--ui-font-size-small); cursor: pointer; }
</style>
