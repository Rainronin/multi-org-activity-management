/** 将集中定义的 px 长度交给要求数值的组件；其他 CSS 值直接在样式中消费。 */
export function readUiLength(name: `--ui-${string}`): number {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  if (!/^\d+(?:\.\d+)?px$/.test(value)) {
    throw new Error(`设计 token ${name} 必须是有效的 px 长度`)
  }
  return Number.parseFloat(value)
}

/** 毫秒时长交给计时器；只接受 ms，秒值必须先换算（UI 规范 11 节读取规则）。 */
export function readUiDuration(name: `--ui-${string}`): number {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  if (!/^\d+(?:\.\d+)?ms$/.test(value)) {
    throw new Error(`设计 token ${name} 必须是有效的 ms 时长`)
  }
  return Number.parseFloat(value)
}
