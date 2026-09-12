export interface HealthReport {
  status: string
  database: string
  checkedAt: string
}

export function describeHealth(report: HealthReport): string {
  if (report.status === 'UP' && report.database === 'UP') {
    return '后端服务与数据库连接正常。'
  }
  return `后端状态 ${report.status}，数据库状态 ${report.database}。`
}
