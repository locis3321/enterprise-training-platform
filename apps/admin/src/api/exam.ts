import { del, get, post, put } from '@/utils/request'
import type { PageResult } from '@/api/tenancy'

/** 题目选项（前端表单用）。 */
export interface QuestionOptionInput {
  content: string
  correct: boolean
}

export interface QuestionOption {
  id: number
  label: string
  content: string
  correct: boolean
}

export interface Question {
  id: number
  courseId: number
  type: 'SINGLE' | 'MULTIPLE'
  stem: string
  status: 'ACTIVE' | 'INACTIVE'
  /** 被已发布试卷引用时为 true：编辑与删除按钮应禁用。 */
  referencedByPublished: boolean
  options: QuestionOption[]
}

export interface QuestionForm {
  stem: string
  type: 'SINGLE' | 'MULTIPLE'
  options: QuestionOptionInput[]
}

export interface ImportRow {
  rowNo: number
  stem: string
  ok: boolean
  error: string | null
}

export interface QuestionImportResult {
  total: number
  success: number
  failed: number
  rows: ImportRow[]
}

export interface ExamPaper {
  id: number
  insuranceCompanyId: number
  packageId: number
  title: string
  durationMinutes: number
  /** 及格百分比 1-100，不是绝对分。 */
  passScore: number
  status: 'DRAFT' | 'PUBLISHED'
}

export interface ExamPaperQuestion {
  id: number
  paperId: number
  questionId: number
  score: number
  sortNo: number
}

/** 试卷内的一道题（含题干等展示字段，避免只看到题目 id）。 */
export interface PaperQuestionItem {
  id: number
  questionId: number
  sortNo: number
  score: number
  stem: string
  type: 'SINGLE' | 'MULTIPLE' | null
  optionsCount: number
  status: 'ACTIVE' | 'INACTIVE' | null
}

export interface PaperDetail {
  paper: ExamPaper
  questions: ExamPaperQuestion[]
  totalScore: number
}

export function fetchQuestions(courseId: number, params: Record<string, unknown>) {
  return get<PageResult<Question>>(`/admin/courses/${courseId}/questions`, params)
}

export function createQuestion(courseId: number, form: QuestionForm) {
  return post<Question>(`/admin/courses/${courseId}/questions`, form)
}

export function updateQuestion(courseId: number, id: number, form: QuestionForm) {
  return put<Question>(`/admin/courses/${courseId}/questions/${id}`, form)
}

export function deleteQuestion(courseId: number, id: number) {
  return del<void>(`/admin/courses/${courseId}/questions/${id}`)
}

/** 停用只影响能否被新抽题选中，不影响已发布试卷与进行中考试的判分。 */
export function changeQuestionStatus(courseId: number, id: number, status: 'ACTIVE' | 'INACTIVE') {
  return put<Question>(`/admin/courses/${courseId}/questions/${id}/status`, { status })
}

export function downloadQuestionTemplate(courseId: number) {
  const base = import.meta.env.VITE_API_BASE_URL || '/api/v1'
  return fetch(`${base}/admin/courses/${courseId}/questions/template`, {
    headers: { Authorization: `Bearer ${sessionStorage.getItem('it_admin_token') ?? ''}` },
  })
}

export async function importQuestions(courseId: number, file: File): Promise<QuestionImportResult> {
  const form = new FormData()
  form.append('file', file)
  const base = import.meta.env.VITE_API_BASE_URL || '/api/v1'
  const response = await fetch(`${base}/admin/courses/${courseId}/questions/import`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${sessionStorage.getItem('it_admin_token') ?? ''}` },
    body: form,
  })
  const json = await response.json()
  if (!response.ok || (json.code !== 200 && json.code !== 0)) {
    throw new Error(json.message || '导入失败')
  }
  return json.data as QuestionImportResult
}

export function createPaper(payload: {
  packageId: number
  title: string
  draws: { courseId: number, count: number }[]
  durationMinutes: number
  passScore: number
}) {
  return post<ExamPaper>('/admin/exam-papers', payload)
}

export function fetchPaper(id: number) {
  return get<PaperDetail>(`/admin/exam-papers/${id}`)
}

/** 按培训包取试卷；该包还没有试卷时返回 null。 */
export function fetchPaperByPackage(packageId: number) {
  return get<PaperDetail | null>(`/admin/exam-papers/by-package/${packageId}`)
}

export function updatePaperScores(id: number, items: { questionId: number, score: number }[]) {
  return put<void>(`/admin/exam-papers/${id}/scores`, items)
}

export function updatePaperSettings(id: number, durationMinutes: number, passScore: number) {
  return put<ExamPaper>(`/admin/exam-papers/${id}/settings`, { durationMinutes, passScore })
}

export function publishPaper(id: number) {
  return post<ExamPaper>(`/admin/exam-papers/${id}/publish`)
}

/** 取消发布：恢复可编辑；进行中的考试仍可考完，历史成绩保留。 */
export function unpublishPaper(id: number) {
  return post<ExamPaper>(`/admin/exam-papers/${id}/unpublish`)
}

/** 试卷题目分页（含题干、题型、选项数）。 */
export function fetchPaperQuestions(id: number, params: { page?: number, pageSize?: number }) {
  return get<PageResult<PaperQuestionItem>>(`/admin/exam-papers/${id}/questions`, params)
}

/** 复制到另一个培训包：新试卷为草稿，与源试卷完全独立。 */
export function copyPaper(id: number, targetPackageId: number, title?: string) {
  return post<ExamPaper>(`/admin/exam-papers/${id}/copy`, { targetPackageId, title })
}
