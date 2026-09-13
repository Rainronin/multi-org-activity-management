/**
 * 创建防重键（开发规范第三节）：前端在一次用户意图开始时生成并保留，
 * 未知结果的重试沿用原键与内容；成功后丢弃，不跨意图复用。
 */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // 兜底：无 randomUUID 的环境（老浏览器）用时间戳与随机数拼出 1～64 字符的键
  return `k${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`
}
