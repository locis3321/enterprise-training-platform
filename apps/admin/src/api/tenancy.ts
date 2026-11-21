import { get, post, put } from '@/utils/request'

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

export interface InsuranceCompany {
  id: number
  name: string
  code: string
  contactName: string | null
  contactPhone: string | null
  status: number
  createdAt: string
}

export interface Enterprise {
  id: number
  insuranceCompanyId: number
  name: string
  code: string
  contactName: string | null
  contactPhone: string | null
  status: number
  createdAt: string
}

export interface CompanyForm {
  name: string
  code: string
  contactName?: string
  contactPhone?: string
  status?: number
}

export interface EnterpriseForm {
  insuranceCompanyId: number
  name: string
  code: string
  contactName?: string
  contactPhone?: string
  status?: number
}

export function fetchCompanies(params: Record<string, unknown>) {
  return get<PageResult<InsuranceCompany>>('/admin/insurance-companies', params)
}

export function createCompany(form: CompanyForm) {
  return post<InsuranceCompany>('/admin/insurance-companies', form)
}

export function updateCompany(id: number, form: CompanyForm) {
  return put<InsuranceCompany>(`/admin/insurance-companies/${id}`, form)
}

export function fetchEnterprises(params: Record<string, unknown>) {
  return get<PageResult<Enterprise>>('/admin/enterprises', params)
}

export function createEnterprise(form: EnterpriseForm) {
  return post<Enterprise>('/admin/enterprises', form)
}

export function updateEnterprise(id: number, form: EnterpriseForm) {
  return put<Enterprise>(`/admin/enterprises/${id}`, form)
}
