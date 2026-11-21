import axios from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { TOKEN_KEY } from '@/stores/auth'

/** 与后端 `ApiResponse` 一一对应；code 语义与 HTTP 状态码一致。 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  traceId?: string
}

export class RequestError extends Error {
  readonly code: number
  readonly traceId?: string

  constructor(code: number, message: string, traceId?: string) {
    super(message)
    this.name = 'RequestError'
    this.code = code
    this.traceId = traceId
  }
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 15000,
})

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = sessionStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

http.interceptors.response.use(
  (response: AxiosResponse<ApiResponse<unknown>>) => {
    const body = response.data
    // 业务错误以 code 判定（HTTP 200 也可能是业务失败）
    if (body && typeof body.code === 'number' && body.code !== 200) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new RequestError(body.code, body.message, body.traceId))
    }
    return response
  },
  (error: unknown) => {
    if (axios.isAxiosError(error)) {
      const status = error.response?.status
      const body = error.response?.data as ApiResponse<unknown> | undefined
      if (status === 401) {
        // 不在此处 import router，避免 api -> router -> views -> api 循环依赖
        sessionStorage.removeItem(TOKEN_KEY)
        ElMessage.error('登录已过期，请重新登录')
        window.location.assign('/login')
      } else {
        ElMessage.error(body?.message || `请求失败（HTTP ${status ?? '网络异常'}）`)
      }
      return Promise.reject(
        new RequestError(status ?? 0, body?.message ?? error.message, body?.traceId),
      )
    }
    ElMessage.error('请求失败')
    return Promise.reject(error)
  },
)

export async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url, { params })
  return response.data.data
}

export async function post<T>(url: string, data?: unknown): Promise<T> {
  const response = await http.post<ApiResponse<T>>(url, data)
  return response.data.data
}

export async function put<T>(url: string, data?: unknown): Promise<T> {
  const response = await http.put<ApiResponse<T>>(url, data)
  return response.data.data
}

export async function del<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const response = await http.delete<ApiResponse<T>>(url, { params })
  return response.data.data
}
