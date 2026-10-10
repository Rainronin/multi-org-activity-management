import { onScopeDispose, ref } from 'vue'
import { readUiDuration } from './readUiToken'

/**
 * 首次加载的 leading／trailing 延迟（UI 规范第 10 节）：
 * - leading：请求很快返回时不显示骨架，避免闪一帧（本地接口常只有几十毫秒）；
 * - trailing：骨架一旦出现就至少停留这段时间，避免出现即消失的闪烁。
 *
 * 只有"首次进入、尚无内容"的加载才走这两个延迟；已有数据的后台刷新不经过本 composable，
 * 由调用方自己用就地提示表达。延迟值默认读集中 token。
 */
export function useLoadingSkeleton(trailing = readUiDuration('--ui-loading-trailing'),
  leading = readUiDuration('--ui-loading-leading')) {
  const loading = ref(false)
  const shownAt = ref(0)
  let leadingTimer: ReturnType<typeof setTimeout> | undefined
  let trailingTimer: ReturnType<typeof setTimeout> | undefined

  function clearTimers(): void {
    clearTimeout(leadingTimer)
    clearTimeout(trailingTimer)
    leadingTimer = undefined
    trailingTimer = undefined
  }

  /** 开始一次首次加载：领先延迟后才显示骨架，很快结束就完全不显示。 */
  function begin(): void {
    clearTimers()
    leadingTimer = setTimeout(() => {
      loading.value = true
      shownAt.value = Date.now()
    }, leading)
  }

  /** 请求结束（成功或失败）：骨架已出现时补足最小停留时间。 */
  function end(): void {
    clearTimeout(leadingTimer)
    leadingTimer = undefined
    if (!loading.value) {
      return
    }
    const rest = trailing - (Date.now() - shownAt.value)
    if (rest <= 0) {
      loading.value = false
      return
    }
    trailingTimer = setTimeout(() => {
      loading.value = false
    }, rest)
  }

  /** 切换组织或活动：收起当前骨架，下一次首次加载重新计时。 */
  function hide(): void {
    clearTimers()
    loading.value = false
    shownAt.value = 0
  }

  onScopeDispose(clearTimers)

  return { loading, begin, end, hide }
}
