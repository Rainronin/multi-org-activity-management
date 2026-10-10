import { onMounted, onUnmounted, ref } from 'vue'

/** 初始保守静态；实时响应系统偏好，并释放监听。 */
export function useMotionPreference() {
  const reduced = ref(true)
  let query: MediaQueryList | undefined
  const sync = () => { reduced.value = query?.matches ?? true }
  onMounted(() => {
    query = window.matchMedia('(prefers-reduced-motion: reduce)')
    sync()
    query.addEventListener('change', sync)
  })
  onUnmounted(() => query?.removeEventListener('change', sync))
  return reduced
}
