// 所有人名、活动及数量均为本地展示样例，不代表实际组织数据。
export type ActivityStatus = '草稿' | '待确认' | '筹备中' | '执行中' | '复盘中' | '已归档'
export interface Activity {
  id: number
  org: string
  title: string
  description: string
  date: string
  time: string
  location: string
  owner: string
  status: ActivityStatus
  registration: string
  approved: number
  capacity: number
  budget: string
  expense: string
  tasks: { title: string; owner: string; due: string; done: boolean }[]
}

export const organizations = [
  { id: 'volunteer', name: '青年志愿者协会', initials: '志', members: 24 },
  { id: 'photo', name: '光影摄影社', initials: '影', members: 18 },
]

export const initialActivities: Activity[] = [
  { id: 101, org: 'volunteer', title: '让旧书，遇见新朋友', description: '秋季校园旧书交换计划', date: '2026-09-19', time: '14:00 — 17:00', location: '图书馆南广场', owner: '林悦', status: '筹备中', registration: '报名开放', approved: 36, capacity: 60, budget: '1200.00', expense: '328.00', tasks: [
    { title: '确认场地与桌椅安排', owner: '林悦', due: '09-14', done: true },
    { title: '完成旧书交换指引', owner: '陈晨', due: '09-16', done: false },
    { title: '整理物资领取清单', owner: '林悦', due: '09-17', done: false },
  ] },
  { id: 102, org: 'volunteer', title: '周末，一起清理河岸', description: '城市河流保护志愿行动', date: '2026-09-26', time: '09:00 — 11:30', location: '滨河公园东入口', owner: '陈晨', status: '筹备中', registration: '报名未开放', approved: 0, capacity: 30, budget: '600.00', expense: '0.00', tasks: [
    { title: '确认清理路线', owner: '陈晨', due: '09-20', done: false },
    { title: '准备环保手套与垃圾袋', owner: '林悦', due: '09-23', done: false },
  ] },
  { id: 103, org: 'volunteer', title: '新学期的第一份温暖', description: '迎新志愿服务复盘', date: '2026-09-06', time: '08:00 — 18:00', location: '学校北门', owner: '周宁', status: '复盘中', registration: '报名结束', approved: 24, capacity: 24, budget: '800.00', expense: '652.00', tasks: [
    { title: '布置志愿服务站', owner: '周宁', due: '09-06', done: true },
    { title: '整理服务记录', owner: '林悦', due: '09-07', done: true },
  ] },
  { id: 104, org: 'volunteer', title: '社区里的数字课堂', description: '帮助长者使用智能手机', date: '2026-10-10', time: '14:00 — 16:00', location: '向阳社区活动室', owner: '林悦', status: '草稿', registration: '暂未发布', approved: 0, capacity: 20, budget: '300.00', expense: '0.00', tasks: [] },
  { id: 201, org: 'photo', title: '把秋天，装进取景框', description: '校园秋日摄影漫步', date: '2026-09-20', time: '16:00 — 18:00', location: '梧桐大道', owner: '林悦', status: '筹备中', registration: '报名开放', approved: 12, capacity: 20, budget: '200.00', expense: '0.00', tasks: [
    { title: '整理拍摄路线', owner: '林悦', due: '09-18', done: false },
  ] },
  { id: 202, org: 'photo', title: '我们的夏日影像', description: '暑期作品交流与归档', date: '2026-09-05', time: '19:00 — 20:30', location: '学生活动中心 203', owner: '许知', status: '已归档', registration: '报名结束', approved: 16, capacity: 20, budget: '100.00', expense: '60.00', tasks: [
    { title: '汇总作品与创作说明', owner: '许知', due: '09-05', done: true },
  ] },
]

// 每日批准记录的汇总样例，截止 2026-09-12；每场累计与 approved 一致。
// 本例没有取消记录，趋势描述每日批准人次，不解释为到场或成员活跃度。
export const approvalHistory = [
  { activityId: 103, date: '2026-09-01', count: 3 },
  { activityId: 103, date: '2026-09-02', count: 4 },
  { activityId: 103, date: '2026-09-03', count: 6 },
  { activityId: 103, date: '2026-09-04', count: 5 },
  { activityId: 103, date: '2026-09-05', count: 6 },
  ...[2, 3, 4, 5, 7, 9, 6].map((count, index) => ({ activityId: 101, date: `2026-09-${String(index + 6).padStart(2, '0')}`, count })),
  ...[2, 2, 4, 4].map((count, index) => ({ activityId: 201, date: `2026-09-${String(index + 9).padStart(2, '0')}`, count })),
  ...[2, 3, 5, 6].map((count, index) => ({ activityId: 202, date: `2026-09-0${index + 1}`, count })),
]
