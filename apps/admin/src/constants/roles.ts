/** 角色与数据范围的展示名。
 *
 * 后端 `AdminProfileResponse` 只返回 roleCode，不返回 role.name，
 * 数据库里的中文角色名不会显示，因此展示名统一在前端映射。
 */

export const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: '平台管理员',
  INSURANCE_ADMIN: '集团管理员',
  ENTERPRISE_ADMIN: '子公司管理员',
}

export const SCOPE_LABELS: Record<string, string> = {
  ALL: '全平台',
  INSURANCE_COMPANY: '本集团',
  ENTERPRISE: '本子公司',
}

/** 未知 code 回退显示 code 本身，不静默编造中文名。 */
export function roleLabel(code?: string | null): string {
  if (!code) {
    return '未登录'
  }
  return ROLE_LABELS[code] ?? code
}

export function scopeLabel(scope?: string | null): string {
  if (!scope) {
    return '未登录'
  }
  return SCOPE_LABELS[scope] ?? scope
}
