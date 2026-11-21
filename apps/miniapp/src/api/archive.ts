import { http } from '@/http/http'
import { toPageResult } from '@/api/course'

/** 后端分页结构。 */
interface BackendPage<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

/** 学习档案累计汇总，全部由服务端算出，前端不再累加。 */
export interface ArchiveSummary {
  taskTotal: number
  taskCompleted: number
  courseTotal: number
  courseCompleted: number
  studyHours: number
  examPassed: number
}

export interface ArchiveExamBrief {
  scoreSum: number | null
  totalScore: number | null
  passed: boolean
  submittedAt: string | null
}

export interface ArchiveTask {
  assignmentId: number
  taskId: number
  title: string
  enterpriseName: string | null
  status: 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED'
  startAt: string | null
  learnDeadlineAt: string | null
  completedAt: string | null
  courseTotal: number
  courseCompleted: number
  sectionTotal: number
  sectionCompleted: number
  studyHours: number
  bestExam: ArchiveExamBrief | null
}

export interface LearningArchive {
  summary: ArchiveSummary
  tasks: ArchiveTask[]
}

/** 考试记录流水的一行：每次交卷一条，不合并。 */
export interface ExamRecord {
  attemptId: number
  assignmentId: number
  taskTitle: string
  enterpriseName: string | null
  paperTitle: string
  scoreSum: number | null
  totalScore: number | null
  passScore: number | null
  passed: boolean
  autoSubmitted: boolean
  startedAt: string | null
  submittedAt: string | null
  best: boolean
}

/** 隐私协议：url 为每次请求重新签名的短期地址。 */
export interface PrivacyPolicy {
  available: boolean
  filename: string | null
  updatedAt: string | null
  url: string | null
}

export function fetchLearningArchive() {
  return http.get<LearningArchive>('/miniapp/archive/learning')
}

export async function fetchExamRecords(page = 1, pageSize = 20) {
  const result = await http.get<BackendPage<ExamRecord>>('/miniapp/archive/exams', { page, pageSize })
  return toPageResult(result)
}

/** 免登录接口：登录前也要能查看协议。 */
export function fetchPrivacyPolicy() {
  return http.get<PrivacyPolicy>('/miniapp/legal/privacy-policy')
}

/** 得分百分比；总分为 0 或缺失时返回 0，不出现 NaN。 */
export function recordPercent(record: { scoreSum: number | null, totalScore: number | null }) {
  const total = record.totalScore ?? 0
  if (!total)
    return 0
  return Math.round(((record.scoreSum ?? 0) / total) * 100)
}

/** 完成率百分比；分母为 0 时返回 0。 */
export function ratioPercent(done: number, total: number) {
  if (!total)
    return 0
  return Math.round((done / total) * 100)
}
