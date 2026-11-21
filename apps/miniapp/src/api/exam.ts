import { http } from '@/http/http'

/** 考试选项：只有标签与内容，不含正确与否。 */
export interface ExamOption {
  label: string
  content: string
}

export interface ExamQuestion {
  questionId: number
  stem: string
  type: 'SINGLE' | 'MULTIPLE'
  options: ExamOption[]
  score: number
}

export interface ExamAnswer {
  questionId: number
  labels: string[]
}

/**
 * 考试尝试。
 *
 * <p>`resumed=true` 表示恢复上次未完成的考试（同一份卷子、原 deadline），
 * 前端据此显示「已恢复上次未完成的考试」而不是「已开始新考试」。
 */
export interface ExamAttempt {
  attemptId: number
  paperId: number
  status: 'IN_PROGRESS' | 'SUBMITTED'
  startedAt: string
  deadlineAt: string
  submittedAt: string | null
  scoreSum: number | null
  totalScore: number | null
  /** 及格百分比，不是绝对分。 */
  passScore: number | null
  passed: boolean
  autoSubmitted: boolean
  resumed: boolean
  questions: ExamQuestion[]
  answers: ExamAnswer[]
}

export interface ExamAvailability {
  canStart: boolean
  reason: string | null
  paperIds: number[]
}

/** 首页「待考考试」：可考任务数与已通过任务数，均由服务端判定。 */
export interface ExamOverview {
  pendingExams: number
  passedExams: number
}

/** 最佳已交卷成绩：列表标识与成绩页共用同一口径。 */
export interface BestAttempt {
  attemptId: number
  paperId: number
  scoreSum: number | null
  totalScore: number | null
  passScore: number | null
  passed: boolean
  submittedAt: string | null
}

/** 考试卡片：只含有已发布试卷的任务。 */
export interface ExamCard {
  assignmentId: number
  taskTitle: string | null
  paperIds: number[]
  canStart: boolean
  reason: string | null
  /** 已交过卷；为 true 时不再显示「开始考试」。 */
  attempted: boolean
  best: BestAttempt | null
}

export function fetchExamOverview() {
  return http.get<ExamOverview>('/miniapp/exams/overview')
}

/** 考试卡片列表（服务端已过滤掉未配卷任务）。 */
export function fetchExamCards() {
  return http.get<ExamCard[]>('/miniapp/exams/assignments')
}

/** 某任务的最佳成绩；未考过返回 null。 */
export function fetchBestAttempt(assignmentId: number) {
  return http.get<BestAttempt | null>(`/miniapp/exams/best/${assignmentId}`)
}

/** 可考状态：canStart 与 reason 都由服务端给出，前端不推算。 */
export function fetchExamAvailability(assignmentId: number) {
  return http.get<ExamAvailability>(`/miniapp/exams/available/${assignmentId}`)
}

/** 开考（幂等：已有未超时的进行中考试则恢复同一份）。 */
export function startExam(paperId: number, assignmentId: number) {
  return http.post<ExamAttempt>(`/miniapp/exams/${paperId}/attempts`, undefined, { assignmentId })
}

/** 保存单题答案（幂等覆盖）。 */
export function saveExamAnswer(attemptId: number, questionId: number, labels: string[]) {
  return http.post<void>('/miniapp/exams/answers', { attemptId, questionId, labels })
}

/** 交卷（幂等）；auto=true 表示切页或超时自动交卷。 */
export function submitExam(attemptId: number, auto = false) {
  return http.post<ExamAttempt>(`/miniapp/exams/attempts/${attemptId}/submit`, undefined, { auto })
}

/** 考试历史：同一试卷的多次考试全部返回。 */
export function fetchExamHistory(assignmentId: number) {
  return http.get<ExamAttempt[]>('/miniapp/exams/attempts', { assignmentId })
}

/** 得分百分比（整数）。接受 attempt 或最佳成绩摘要，二者字段同名。 */
export function scorePercent(attempt: { scoreSum: number | null, totalScore: number | null }) {
  const total = attempt.totalScore ?? 0
  const sum = attempt.scoreSum ?? 0
  if (!total)
    return 0
  return Math.round((sum / total) * 100)
}
