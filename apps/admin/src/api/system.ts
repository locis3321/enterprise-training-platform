import { get } from '@/utils/request'

/** 与后端 `SystemController#ping` 对应。 */
export interface PingResult {
  app: string
  version: string | null
  serverTime: string
  timeZone: string
}

export function fetchPing(): Promise<PingResult> {
  return get<PingResult>('/ping')
}
