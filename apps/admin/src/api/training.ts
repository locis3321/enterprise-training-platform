import { get, post, put, del } from '@/utils/request'
import type { PageResult } from '@/api/tenancy'

/** 培训包列表项。 */
export interface TrainingPackage {
  id: number
  insuranceCompanyId: number
  ownerEnterpriseId: number | null
  title: string
  description: string | null
  status: 'DRAFT' | 'PUBLISHED' | 'OFFLINE'
  latestVersionNo: number
  publishedVersionNo: number | null
  createdAt: string
  updatedAt: string
}

/** 包版本概要。 */
export interface PackageVersion {
  id: number
  versionNo: number
  title: string
  description: string | null
  status: 'DRAFT' | 'PUBLISHED'
  publishedAt: string | null
  courseCount: number
}

/** 包内一门课程版本。 */
export interface PackageCourse {
  courseVersionId: number
  courseId: number
  courseTitle: string
  courseVersionNo: number
  courseVersionTitle: string
  courseStatus: string
  sortNo: number
}

export interface PackageDetail {
  id: number
  insuranceCompanyId: number
  ownerEnterpriseId: number | null
  title: string
  description: string | null
  status: string
  latestVersionNo: number
  publishedVersionNo: number | null
  versions: PackageVersion[]
  version: {
    versionId: number
    versionNo: number
    title: string
    description: string | null
    status: 'DRAFT' | 'PUBLISHED'
    courses: PackageCourse[]
  }
}

export interface TrainingTask {
  id: number
  insuranceCompanyId: number
  enterpriseId: number
  packageVersionId: number
  title: string
  startAt: string
  learnDeadlineAt: string
  status: 'DRAFT' | 'PUBLISHED' | 'CLOSED'
  createdAt: string
}

export interface TaskProgress {
  total: number
  completed: number
  inProgress: number
  notStarted: number
}

export interface TaskDetail {
  task: TrainingTask
  progress: TaskProgress
}

/** 任务内单个员工的分配明细。effectiveSeconds 为服务端累计时长。 */
export interface TaskAssignmentRow {
  assignmentId: number
  employeeId: number
  name: string
  status: 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED'
  effectiveSeconds: number
  startedAt: string | null
  completedAt: string | null
}

export interface CreateTaskPayload {
  enterpriseId: number
  packageVersionId: number
  title: string
  startAt: string
  learnDeadlineAt: string
  assignAll?: boolean
  employeeIds?: number[]
}

// ---------- 培训包 ----------

export function fetchPackages(params: Record<string, unknown>) {
  return get<PageResult<TrainingPackage>>('/admin/training/packages', params)
}

export function createPackage(title: string, description?: string, insuranceCompanyId?: number) {
  return post<TrainingPackage>('/admin/training/packages', { title, description, insuranceCompanyId })
}

export function fetchPackageDetail(packageId: number, versionNo?: number) {
  return get<PackageDetail>(`/admin/training/packages/${packageId}`,
    versionNo ? { versionNo } : undefined)
}

export function updatePackage(packageId: number, title: string, description?: string) {
  return put<TrainingPackage>(`/admin/training/packages/${packageId}`, { title, description })
}

export function offlinePackage(packageId: number) {
  return post<TrainingPackage>(`/admin/training/packages/${packageId}/offline`)
}

export function onlinePackage(packageId: number) {
  return post<TrainingPackage>(`/admin/training/packages/${packageId}/online`)
}

export function createPackageVersion(packageId: number, title?: string) {
  return post<PackageVersion>(`/admin/training/packages/${packageId}/versions`, { title })
}

export function updatePackageVersion(packageId: number, versionNo: number, title: string, description?: string) {
  return put<PackageVersion>(`/admin/training/packages/${packageId}/versions/${versionNo}`,
    { title, description })
}

export function publishPackageVersion(packageId: number, versionNo: number) {
  return post<PackageVersion>(`/admin/training/packages/${packageId}/versions/${versionNo}/publish`)
}

export function copyPackageVersion(packageId: number, versionNo: number) {
  return post<PackageVersion>(`/admin/training/packages/${packageId}/versions/${versionNo}/copy`)
}

/** 关联课程版本；重复关联幂等并按新 sortNo 重排。 */
export function bindPackageCourse(packageId: number, versionNo: number,
                                  courseVersionId: number, sortNo: number) {
  return post<void>(`/admin/training/packages/${packageId}/versions/${versionNo}/courses`,
    { courseVersionId, sortNo })
}

export function unbindPackageCourse(packageId: number, versionNo: number, courseVersionId: number) {
  return del<void>(`/admin/training/packages/${packageId}/versions/${versionNo}/courses/${courseVersionId}`)
}

// ---------- 培训任务 ----------

export function fetchTasks(params: Record<string, unknown>) {
  return get<PageResult<TrainingTask>>('/admin/training/tasks', params)
}

export function createTask(payload: CreateTaskPayload) {
  return post<TrainingTask>('/admin/training/tasks', payload)
}

export function fetchTaskDetail(taskId: number) {
  return get<TaskDetail>(`/admin/training/tasks/${taskId}`)
}

export function fetchTaskAssignments(taskId: number) {
  return get<TaskAssignmentRow[]>(`/admin/training/tasks/${taskId}/assignments`)
}

export function fetchTaskProgress(taskId: number) {
  return get<TaskProgress>(`/admin/training/tasks/${taskId}/progress`)
}

export function assignTaskEmployees(taskId: number, employeeIds: number[]) {
  return post<{ inserted: number }>(`/admin/training/tasks/${taskId}/assign`, { employeeIds })
}

export function closeTask(taskId: number) {
  return post<TrainingTask>(`/admin/training/tasks/${taskId}/close`)
}

/**
 * 给原任务追加新版本任务，分配给原任务的同一批学员。
 *
 * 原任务保持不可变（版本快照约束），学员端会同时看到旧任务与新任务。
 * 必须使用与原任务不同的 packageVersionId，否则后端 409。
 */
export function createSuccessorTask(taskId: number, payload: CreateTaskPayload) {
  return post<TrainingTask>(`/admin/training/tasks/${taskId}/successor`, payload)
}
