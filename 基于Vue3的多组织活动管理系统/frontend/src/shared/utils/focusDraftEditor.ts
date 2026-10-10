import { nextTick } from 'vue'

/** AI 放入正文后回到编辑上下文；定位不触发保存，也不播放滚动动画。 */
export async function focusDraftEditor(container: HTMLElement | undefined): Promise<void> {
  await nextTick()
  if (!container?.isConnected) return
  const editor = container.querySelector('textarea')
  if (!editor || editor.disabled) return
  editor.focus({ preventScroll: true })
  editor.scrollIntoView({ block: 'center', behavior: 'instant' })
}
