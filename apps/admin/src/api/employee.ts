import { get, post, put } from '@/utils/request'
import type { PageResult } from '@/api/tenancy'

export interface Employee {
  id: number
  insuranceCompanyId: number
  enterpriseId: number
  name: string
  phoneMasked: string
  idCardProvided: boolean
  status: number
  createdAt: string
}

export interface EmployeeForm {
  enterpriseId: number
  name: string
  phone: string
  idCardNo?: string
  status?: number
}

export interface ImportRow {
  rowNo: number
  name: string
  phone: string
  ok: boolean
  action: string
  error: string | null
}

export interface ImportResult {
  total: number
  success: number
  failed: number
  rows: ImportRow[]
}

export function fetchEmployees(params: Record<string, unknown>) {
  return get<PageResult<Employee>>('/admin/employees', params)
}

export function createEmployee(form: EmployeeForm) {
  return post<Employee>('/admin/employees', form)
}

export function updateEmployee(id: number, form: EmployeeForm) {
  return put<Employee>(`/admin/employees/${id}`, form)
}

/**
 * 重置学员密码为初始密码（anzhi + 手机号后 6 位）。
 *
 * 账号按手机号全局唯一，重置会影响该学员在所有企业的登录，
 * 因此响应含 `affectedEnterpriseCount`，界面必须提示影响面。
 */
export function resetEmployeePassword(id: number) {
  return post<{ affectedEnterpriseCount: number }>(`/admin/employees/${id}/password/reset`)
}

/**
 * 导入必须用 FormData：multipart 由浏览器设置 boundary，不能手工指定 Content-Type。
 */
export async function importEmployees(file: File): Promise<ImportResult> {
  const form = new FormData()
  form.append('file', file)
  const base = import.meta.env.VITE_API_BASE_URL || '/api/v1'
  const response = await fetch(`${base}/admin/employees/import`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${sessionStorage.getItem('it_admin_token') ?? ''}` },
    body: form,
  })
  const body = await response.json()
  if (!response.ok || body.code !== 200) {
    throw new Error(body?.message || `导入失败（HTTP ${response.status}）`)
  }
  return body.data as ImportResult
}

export async function downloadTemplate(): Promise<void> {
  const base = import.meta.env.VITE_API_BASE_URL || '/api/v1'
  const response = await fetch(`${base}/admin/employees/import-template`, {
    headers: { Authorization: `Bearer ${sessionStorage.getItem('it_admin_token') ?? ''}` },
  })
  if (!response.ok) {
    throw new Error(`模板下载失败（HTTP ${response.status}）`)
  }
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'employee-import-template.xlsx'
  link.click()
  URL.revokeObjectURL(url)
}
