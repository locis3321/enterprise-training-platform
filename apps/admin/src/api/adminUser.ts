import { del, get, post, put } from '@/utils/request'
import type { PageResult } from '@/api/tenancy'
import type { AdminProfile } from '@/api/auth'

export interface AdminUser {
  id: number
  username: string
  displayName: string
  roleCode: string
  insuranceCompanyId: number | null
  enterpriseId: number | null
  status: number
  lastLoginAt: string | null
  createdAt: string
}

export interface AdminUserCreateForm {
  username: string
  password: string
  displayName: string
  roleCode: string
  insuranceCompanyId?: number | null
  enterpriseId?: number | null
}

export interface AdminUserUpdateForm {
  displayName: string
  roleCode: string
  insuranceCompanyId?: number | null
  enterpriseId?: number | null
  status?: number
}

export function fetchAdminUsers(params: Record<string, unknown>) {
  return get<PageResult<AdminUser>>('/admin/users', params)
}

export function fetchAdminUser(id: number) {
  return get<AdminUser>(`/admin/users/${id}`)
}

export function createAdminUser(form: AdminUserCreateForm) {
  return post<AdminUser>('/admin/users', form)
}

export function updateAdminUser(id: number, form: AdminUserUpdateForm) {
  return put<AdminUser>(`/admin/users/${id}`, form)
}

export function resetAdminPassword(id: number, password: string) {
  return post<void>(`/admin/users/${id}/reset-password`, { password })
}

export function deleteAdminUser(id: number) {
  return del<void>(`/admin/users/${id}`)
}

export function changeAdminSelfPassword(oldPassword: string, newPassword: string) {
  return put<AdminProfile>('/admin/users/me/password', { oldPassword, newPassword })
}

export function updateAdminSelfProfile(displayName: string) {
  return put<AdminProfile>('/admin/users/me', { displayName })
}
