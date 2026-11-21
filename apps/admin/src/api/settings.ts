import { del, get, post, put } from '@/utils/request'

export interface SystemSetting {
  key: string
  group: string
  label: string
  description: string | null
  valueType: 'STRING' | 'INT' | 'DURATION' | 'CSV'
  effectiveValue: string | null
  source: 'DATABASE' | 'DEFAULT'
  defaultValue: string | null
  updatedAt: string | null
  updatedByAdminId: number | null
}

export interface SystemSettingsView {
  items: SystemSetting[]
  lastRefreshAt: string | null
  lastRefreshError: string | null
  effectiveNotice: string
}

export function fetchSettings() {
  return get<SystemSettingsView>('/admin/settings')
}

export function updateSetting(key: string, value: string) {
  return put<SystemSetting>(`/admin/settings/${key}`, { value })
}

export function resetSetting(key: string) {
  return del<void>(`/admin/settings/${key}`)
}

export function refreshSettings() {
  return post<SystemSettingsView>('/admin/settings/refresh')
}

/** 隐私协议：url 为每次请求重新签名的短期地址。 */
export interface PrivacyPolicy {
  available: boolean
  filename: string | null
  updatedAt: string | null
  url: string | null
}

export function fetchPrivacyPolicy() {
  return get<PrivacyPolicy>('/admin/settings/legal/privacy-policy')
}

/** 上传替换隐私协议 PDF；后端校验魔数与 10MB 上限。 */
export async function uploadPrivacyPolicy(file: File): Promise<PrivacyPolicy> {
  const base = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')
  const form = new FormData()
  form.append('file', file)
  const response = await fetch(`${base}/admin/settings/legal/privacy-policy`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${sessionStorage.getItem('it_admin_token') ?? ''}` },
    body: form,
  })
  const body = await response.json().catch(() => null)
  if (!response.ok || body?.code !== 200) {
    throw new Error(body?.message || `上传失败（HTTP ${response.status}）`)
  }
  return body.data as PrivacyPolicy
}
