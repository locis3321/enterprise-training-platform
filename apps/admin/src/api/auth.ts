import { get, post } from '@/utils/request'

export interface AdminLoginResult {
  token: string
  expiresAt: string
  displayName: string
  roleCode: string
  dataScope: string
  insuranceCompanyId: number | null
  enterpriseId: number | null
}

export interface AdminProfile {
  id: number
  username: string
  displayName: string
  roleCode: string
  dataScope: string
  insuranceCompanyId: number | null
  enterpriseId: number | null
}

export function login(username: string, password: string): Promise<AdminLoginResult> {
  return post<AdminLoginResult>('/admin/auth/login', { username, password })
}

export function fetchMe(): Promise<AdminProfile> {
  return get<AdminProfile>('/admin/auth/me')
}
