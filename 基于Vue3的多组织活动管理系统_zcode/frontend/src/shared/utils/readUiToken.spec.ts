import { afterEach, expect, it, vi } from 'vitest'
import { readUiDuration, readUiLength } from './readUiToken'
afterEach(() => vi.unstubAllGlobals())
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

it('加载延迟读取集中 ms 值，秒值或缺失明确失败（UI 规范 11 节）', () => {
  vi.stubGlobal('document', { documentElement: {} })
  let value = ' 200ms '
  vi.stubGlobal('getComputedStyle', () => ({ getPropertyValue: () => value }))
  expect(readUiDuration('--ui-loading-leading')).toBe(200)
  value = '0.2s'
  expect(() => readUiDuration('--ui-loading-leading')).toThrow('ms 时长')
  value = ''
  expect(() => readUiDuration('--ui-loading-leading')).toThrow('设计 token')
})
