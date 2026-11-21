import { http } from '@/http/http'

/** 后端统一分页结构。 */
interface BackendPage<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

/**
 * 后端 `items` → unibest `PageResult.list`。
 *
 * <p>unibest 的 `PageResult` 用 `list`，而后端统一返回 `items`。
 * 这里只在课程域转换，避免修改 unibest 全局类型影响模板既有代码。
 */
export function toPageResult<T>(input: BackendPage<T>): {
  list: T[]
  total: number
  page: number
  pageSize: number
} {
  return {
    list: input?.items ?? [],
    total: input?.total ?? 0,
    page: input?.page ?? 1,
    pageSize: input?.pageSize ?? 0,
  }
}

export interface MiniappCourse {
  id: number
  title: string
  description: string | null
  status: string
  latestVersionNo: number
  publishedVersionNo: number | null
}

export interface MiniappResourceBrief {
  resourceId: number
  kind: string
  originalName: string
  sizeBytes: number
}

export interface MiniappSection {
  id: number
  title: string
  sortNo: number
  durationSeconds: number | null
  resourceCount: number
  resources: MiniappResourceBrief[]
}

export interface MiniappCourseDetail {
  id: number
  title: string
  description: string | null
  versionNo: number
  sectionCount: number
  sections: MiniappSection[]
}

/** 已发布课程列表（同集团）。 */
export async function fetchCourses(page = 1, pageSize = 10) {
  const result = await http.get<BackendPage<MiniappCourse>>('/miniapp/courses', { page, pageSize })
  return toPageResult(result)
}

export function fetchCourseDetail(courseId: number) {
  return http.get<MiniappCourseDetail>(`/miniapp/courses/${courseId}`)
}

/** 资源签名地址：每次进入页面或重试都重新申请，不长期缓存。 */
export function fetchResourceUrl(courseId: number, resourceId: number) {
  return http.get<string>(`/miniapp/courses/${courseId}/versions/latest/resources/${resourceId}/url`)
}
