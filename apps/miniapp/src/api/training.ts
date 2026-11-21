import { http } from '@/http/http'
import { toPageResult } from '@/api/course'

/** 后端分页结构。 */
interface BackendPage<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

export interface MyAssignment {
  assignmentId: number
  taskId: number
  title: string | null
  status: 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED'
  startAt: string | null
  learnDeadlineAt: string | null
  daysLeft: number | null
  courseTotal: number
  courseDone: number
  sectionTotal: number
  sectionDone: number
}

export interface MyTaskResource {
  resourceId: number
  kind: string
  originalName: string
  sizeBytes: number
}

export interface MyTaskSection {
  sectionId: number
  title: string
  sortNo: number
  durationSeconds: number | null
  /** 服务端判定：该章节是否已完成（视频播完 / 资料已打开）。 */
  done: boolean
  resources: MyTaskResource[]
}

export interface MyTaskCourse {
  courseVersionId: number
  courseId: number | null
  title: string
  coverResourceId: number | null
  sections: MyTaskSection[]
}

export interface MyTaskDetail {
  assignmentId: number
  title: string
  startAt: string
  learnDeadlineAt: string
  status: 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED'
  courseTotal: number
  courseDone: number
  sectionTotal: number
  sectionDone: number
  courses: MyTaskCourse[]
}

/** 我的培训任务（服务端按状态与 id 排序）。 */
export async function fetchMyTasks(page = 1, pageSize = 20) {
  const result = await http.get<BackendPage<MyAssignment>>('/miniapp/training/tasks', { page, pageSize })
  return toPageResult(result)
}

export function fetchMyTaskDetail(assignmentId: number) {
  return http.get<MyTaskDetail>(`/miniapp/training/tasks/${assignmentId}`)
}

/**
 * 任务上下文取签名地址。
 *
 * <p>**必须用这个路由**：版本由 assignment 推导，旧任务不会读到课程改版后的新内容。
 * Task 03 的 `versions/latest` 路由属于「非任务上下文」，培训场景禁用。
 */
export function fetchAssignmentResourceUrl(assignmentId: number, courseId: number, resourceId: number) {
  return http.get<string>(
    `/miniapp/training/assignments/${assignmentId}/courses/${courseId}/resources/${resourceId}/url`,
  )
}
