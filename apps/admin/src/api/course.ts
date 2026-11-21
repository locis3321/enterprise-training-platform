import { get, post, put, del } from '@/utils/request'
import { uploadFile } from '@/utils/upload'
import type { PageResult } from '@/api/tenancy'

export interface Course {
  id: number
  insuranceCompanyId: number
  ownerEnterpriseId: number | null
  title: string
  description: string | null
  coverResourceId: number | null
  status: 'DRAFT' | 'PUBLISHED' | 'OFFLINE'
  latestVersionNo: number
  publishedVersionNo: number | null
  createdAt: string
  updatedAt: string
}

export interface CourseVersion {
  id: number
  versionNo: number
  title: string
  status: 'DRAFT' | 'PUBLISHED'
  publishedAt: string | null
  sectionCount: number
  resourceCount: number
}

export interface SectionResource {
  resourceId: number
  kind: string
  originalName: string
  sizeBytes: number
  sortNo: number
}

export interface DetailSection {
  id: number
  title: string
  sortNo: number
  durationSeconds: number | null
  resources: SectionResource[]
}

export interface CourseDetail {
  id: number
  title: string
  description: string | null
  status: string
  coverResourceId: number | null
  latestVersionNo: number
  publishedVersionNo: number | null
  version: {
    versionId: number
    versionNo: number
    title: string
    description: string | null
    status: 'DRAFT' | 'PUBLISHED'
    sections: DetailSection[]
  }
  unboundResources: ResourceItem[]
}

export interface ResourceItem {
  id: number
  courseId: number
  kind: string
  originalName: string
  contentType: string
  sizeBytes: number
  status: string
}

export function fetchCourses(params: Record<string, unknown>) {
  return get<PageResult<Course>>('/admin/courses', params)
}

/** 建课。超级管理员必须显式传 insuranceCompanyId，其他角色传了也会被后端忽略。 */
export function createCourse(title: string, description?: string, insuranceCompanyId?: number) {
  return post<Course>('/admin/courses', { title, description, insuranceCompanyId })
}

export function updateCourse(id: number, title: string, description?: string) {
  return put<Course>(`/admin/courses/${id}`, { title, description })
}

export function deleteCourse(id: number) {
  return del<void>(`/admin/courses/${id}`)
}

export function offlineCourse(id: number) {
  return post<Course>(`/admin/courses/${id}/offline`, {})
}

export function onlineCourse(id: number) {
  return post<Course>(`/admin/courses/${id}/online`, {})
}

export function fetchVersions(courseId: number) {
  return get<CourseVersion[]>(`/admin/courses/${courseId}/versions`)
}

export function createVersion(courseId: number, title?: string) {
  return post<CourseVersion>(`/admin/courses/${courseId}/versions`, { title })
}

export function publishVersion(courseId: number, versionNo: number) {
  return post<CourseVersion>(`/admin/courses/${courseId}/versions/${versionNo}/publish`, {})
}

export function copyVersion(courseId: number, versionNo: number) {
  return post<CourseVersion>(`/admin/courses/${courseId}/versions/${versionNo}/copy`, {})
}

export function fetchCourseDetail(courseId: number, versionNo?: number) {
  return get<CourseDetail>(`/admin/courses/${courseId}`, versionNo ? { versionNo } : undefined)
}

export function createSection(courseId: number, versionNo: number, title: string,
                             sortNo?: number, durationSeconds?: number) {
  return post<DetailSection>(`/admin/courses/${courseId}/versions/${versionNo}/sections`,
    { title, sortNo, durationSeconds })
}

export function updateSection(courseId: number, versionNo: number, sectionId: number,
                              title: string, sortNo?: number, durationSeconds?: number) {
  return put<DetailSection>(`/admin/courses/${courseId}/versions/${versionNo}/sections/${sectionId}`,
    { title, sortNo, durationSeconds })
}

export function deleteSection(courseId: number, versionNo: number, sectionId: number) {
  return del<void>(`/admin/courses/${courseId}/versions/${versionNo}/sections/${sectionId}`)
}

/** 上传资源：只落元数据，不绑定；绑定是下一步动作。 */
export function uploadResource(courseId: number, kind: string, file: File,
                               onProgress?: (p: number) => void) {
  const form = new FormData()
  form.append('courseId', String(courseId))
  form.append('kind', kind)
  form.append('file', file)
  const base = import.meta.env.VITE_API_BASE_URL || '/api/v1'
  return uploadFile<ResourceItem>(`${base}/admin/resources/upload`, form, { onProgress })
}

export function bindResource(courseId: number, versionNo: number, sectionId: number, resourceId: number) {
  return put<void>(`/admin/courses/${courseId}/versions/${versionNo}/sections/${sectionId}/resources/${resourceId}`, {})
}

export function unbindResource(courseId: number, versionNo: number, sectionId: number, resourceId: number) {
  return del<void>(`/admin/courses/${courseId}/versions/${versionNo}/sections/${sectionId}/resources/${resourceId}`)
}

export function fetchResourceUrl(resourceId: number) {
  return get<string>(`/admin/resources/${resourceId}/url`)
}
