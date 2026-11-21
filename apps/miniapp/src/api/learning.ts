import { http } from '@/http/http'

export interface HeartbeatPayload {
  assignmentId: number
  sectionId: number
  resourceId: number
  positionSeconds?: number
  clientTickMs?: number
  /** 播放器读到的媒体总时长（秒），仅诊断，不参与判定。 */
  mediaDurationSeconds?: number
  /**
   * 播放到结尾（ended 事件）。
   *
   * <p>这是唯一的完成信号：播放器已禁用拖动，想触发 ended 必须真的播完。
   * 服务端据此标记该视频完成，幂等。
   */
  finished?: boolean
}

export interface HeartbeatResult {
  /** 本次是否把「该视频已播完」写入（重复上报时仍为 true，服务端幂等）。 */
  finishedRecorded: boolean
  assignmentStatus: 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED'
  completed: boolean
}

/**
 * 视频心跳。
 *
 * <p>`positionSeconds` / `clientTickMs` 只是诊断字段，服务端不据此计时，
 * 前端也不得用它推算完成度——完成状态一律以服务端返回为准。
 */
export function sendHeartbeat(payload: HeartbeatPayload) {
  return http.post<HeartbeatResult>('/miniapp/learning/heartbeat', payload)
}

export interface OpenResourceResult {
  firstOpen: boolean
  assignmentStatus: 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED'
  completed: boolean
}

/**
 * 非视频资源「已打开」登记。
 *
 * <p>必须**成功打开/渲染之后**才调用；打开失败不得调用，否则等于伪造「已读」。
 * 视频不得调用（服务端会 400）。
 */
export function openResource(assignmentId: number, resourceId: number) {
  return http.post<OpenResourceResult>('/miniapp/learning/resources/open', { assignmentId, resourceId })
}

/** 页面是否仍可学习：服务端未开始/已过期/已关闭时统一 409。 */
export function isLearnableConflict(error: unknown) {
  const status = (error as { statusCode?: number, code?: number } | null)?.statusCode
    ?? (error as { code?: number } | null)?.code
  return status === 409
}

/** 章节进度。`done` 由服务端判定，前端只展示不推算。 */
export interface SectionProgress {
  sectionId: number
  courseVersionId: number
  title: string
  durationSeconds: number | null
  done: boolean
}

export interface AssignmentProgress {
  assignmentId: number
  title: string | null
  status: 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED'
  learnDeadlineAt: string | null
  courseTotal: number
  courseDone: number
  sectionTotal: number
  sectionDone: number
  completed: boolean
  sections: SectionProgress[]
}

export interface LearningOverview {
  pendingCourses: number
  completedCourses: number
  pendingExams: number
  /** 考试数据以 `/miniapp/exams/overview` 为准，此字段已不再用于展示。 */
  examsAvailable: boolean
}

/** 单个任务的学习进度（含章节完成判定）。 */
export function fetchAssignmentProgress(assignmentId: number) {
  return http.get<AssignmentProgress>(`/miniapp/learning/progress/${assignmentId}`)
}

/** 我的学习总览：首页三个统计的唯一来源。 */
export function fetchLearningOverview() {
  return http.get<LearningOverview>('/miniapp/learning/progress')
}
