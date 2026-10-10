/** 将集中定义的 px 长度交给要求数值的组件；其他 CSS 值直接在样式中消费。 */
export function readUiLength(name: `--ui-${string}`): number {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  if (!/^\d+(?:\.\d+)?px$/.test(value)) {
    throw new Error(`设计 token ${name} 必须是有效的 px 长度`)
  }
  return Number.parseFloat(value)
}

/** 构建压缩会把 900ms 改成 .9s；统一将合法 CSS 时长换算为毫秒。 */
export function readUiDuration(name: `--ui-${string}`): number {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  const match = /^(\d+(?:\.\d+)?|\.\d+)(ms|s)$/.exec(value)
  if (!match) {
    throw new Error(`设计 token ${name} 必须是有效的 CSS 时长（ms 或 s）`)
  }
  return Number(match[1]) * (match[2] === 's' ? 1000 : 1)
}

/** CSS 与 Motion 共用同一缓动；拒绝缺失值及非法控制点。 */
export function readUiBezier(name: `--ui-${string}`): [number, number, number, number] {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  const match = /^cubic-bezier\(([^)]+)\)$/.exec(value)
  const points = match?.[1]?.split(',').map(value => value.trim())
  const values = points?.map(Number)
  if (!points || points.some(value => !value) || !values || values.length !== 4
    || values.some(value => !Number.isFinite(value))
    || values[0]! < 0 || values[0]! > 1 || values[2]! < 0 || values[2]! > 1) {
    throw new Error(`设计 token ${name} 必须是有效的 cubic-bezier 缓动`)
  }
  return values as [number, number, number, number]
}
