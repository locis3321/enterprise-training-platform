import { get } from '@/utils/request'
import type { PageResult } from '@/api/tenancy'

const base = import.meta.env.VITE_API_BASE_URL || '/api/v1'

export interface EnterpriseSummary {
  enterpriseId: number
  enterpriseName: string | null
  companyName: string | null
  assignedCount: number
  completedCount: number
  completionRate: number
  studyHours: number
  examTakers: number
  examPassed: number
  examPassRate: number
  overdueCount: number
}

export interface TaskSummary {
  taskId: number
  taskTitle: string | null
  enterpriseId: number
  enterpriseName: string | null
  packageVersionId: number
  startAt: string | null
  learnDeadlineAt: string | null
  taskStatus: string
  assignedCount: number
  learningCompleted: number
  completionRate: number
  examTakers: number
  examPassed: number
  examPassRate: number
  overdueCount: number
}

export interface TaskAssignmentRow {
  assignmentId: number
  employeeId: number
  name: string
  status: string
  effectiveSeconds: number
  startedAt: string | null
  completedAt: string | null
}

export interface SectionDetail {
  sectionId: number
  title: string
  completed: boolean
  effectiveSeconds: number
  requiredSeconds: number
}

export interface CourseDetail {
  courseVersionId: number
  courseTitle: string
  totalSeconds: number
  sections: SectionDetail[]
}

export interface AttemptRow {
  attemptId: number
  paperTitle: string
  scoreSum: number | null
  totalScore: number | null
  passScore: number | null
  passed: boolean
  startedAt: string | null
  submittedAt: string | null
  autoSubmitted: boolean
  best: boolean
}

export interface TaskDetail {
  taskId: number
  taskTitle: string
  assignmentStatus: string
  startedAt: string | null
  completedAt: string | null
  totalSeconds: number
  courses: CourseDetail[]
  attempts: AttemptRow[]
  bestAttemptId: number | null
}

export interface LearnerReport {
  employeeId: number
  employeeName: string
  phoneMasked: string
  enterpriseId: number
  enterpriseName: string | null
  tasks: TaskDetail[]
}

export function fetchEnterpriseSummary(params: Record<string, unknown>) {
  return get<PageResult<EnterpriseSummary>>('/admin/reports/enterprise-summary', params)
}

export function fetchTaskSummary(params: Record<string, unknown>) {
  return get<PageResult<TaskSummary>>('/admin/reports/task-summary', params)
}

export function fetchLearnerReport(employeeId: number) {
  return get<LearnerReport>(`/admin/reports/learners/${employeeId}`)
}

export function fetchTaskAssignments(taskId: number) {
  return get<TaskAssignmentRow[]>(`/admin/training/tasks/${taskId}/assignments`)
}

/**
 * 下载导出文件。
 *
 * 后端失败时返回 JSON 错误体而非 xlsx；必须先判 ok 并解析 message，
 * 否则会把错误 JSON 存成损坏的 xlsx（既有 downloadTemplate 的陷阱）。
 */
async function download(url: string, params: Record<string, unknown>, fallbackName: string) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') {
      query.append(k, String(v))
    }
  })
  const full = `${base.replace(/\/$/, '')}${url}${query.toString() ? `?${query}` : ''}`
  const response = await fetch(full, {
    headers: { Authorization: `Bearer ${sessionStorage.getItem('it_admin_token') ?? ''}` },
  })
  if (!response.ok) {
    let message = `导出失败（HTTP ${response.status}）`
    try {
      const body = await response.json()
      message = body?.message || message
    }
    catch {
      // 非 JSON 错误体，保留状态码消息
    }
    throw new Error(message)
  }
  const disposition = response.headers.get('Content-Disposition') || ''
  const match = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  const filename = match ? decodeURIComponent(match[1]) : fallbackName
  const blob = await response.blob()
  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = filename
  link.click()
  URL.revokeObjectURL(objectUrl)
}

export function exportEnterpriseSummary(params: Record<string, unknown>) {
  return download('/admin/reports/enterprise-summary/export', params, '企业汇总.xlsx')
}

export function exportTaskSummary(params: Record<string, unknown>) {
  return download('/admin/reports/task-summary/export', params, '任务汇总.xlsx')
}

export function exportTaskLearners(taskId: number) {
  return download(`/admin/reports/tasks/${taskId}/learners/export`, {}, `任务名单-${taskId}.xlsx`)
}

export function exportLearner(employeeId: number) {
  return download(`/admin/reports/learners/${employeeId}/export`, {}, `个人明细-${employeeId}.xlsx`)
}
