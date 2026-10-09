import { afterEach, expect, it, vi } from 'vitest'
import { readUiBezier, readUiDuration, readUiLength } from './readUiToken'
afterEach(() => vi.unstubAllGlobals())
it('Motion 从 CSS 读取缓动并拒绝空项、越界横坐标与非法值', () => {
  vi.stubGlobal('document', { documentElement: {} })
  let value = 'cubic-bezier(0.22, 1, 0.36, 1)'
  vi.stubGlobal('getComputedStyle', () => ({ getPropertyValue: () => value }))
  expect(readUiBezier('--ui-motion-ease')).toEqual([0.22, 1, 0.36, 1])
  for (const invalid of ['', 'ease', 'cubic-bezier(, 1, .3, 1)', 'cubic-bezier(2, 1, .3, 1)', 'cubic-bezier(0, NaN, 1, 1)']) {
    value = invalid
    expect(() => readUiBezier('--ui-motion-ease')).toThrow('cubic-bezier')
  }
})
it('列宽读取集中 px 值，缺失或非 px 值明确失败，不静默回退为零', () => {
  vi.stubGlobal('document', { documentElement: {} })
  let value = ' 160px '
  vi.stubGlobal('getComputedStyle', () => ({ getPropertyValue: () => value }))
  expect(readUiLength('--ui-table-col-160')).toBe(160)
  value = ''
  expect(() => readUiLength('--ui-missing')).toThrow('设计 token')
  value = '10rem'
  expect(() => readUiLength('--ui-invalid')).toThrow('px 长度')
})

it('时长统一返回毫秒，兼容生产 CSS 压缩后的秒值并拒绝非法值', () => {
  vi.stubGlobal('document', { documentElement: {} })
  let value = ' 200ms '
  vi.stubGlobal('getComputedStyle', () => ({ getPropertyValue: () => value }))
  expect(readUiDuration('--ui-loading-leading')).toBe(200)
  value = '0.2s'
  expect(readUiDuration('--ui-loading-leading')).toBe(200)
  value = '.9s'
  expect(readUiDuration('--ui-motion-chart')).toBe(900)
  value = '0s'
  expect(readUiDuration('--ui-motion-chart')).toBe(0)
  for (const invalid of ['', '-1ms', '10px', 'NaNs']) {
    value = invalid
    expect(() => readUiDuration('--ui-motion-chart')).toThrow('设计 token')
  }
})
