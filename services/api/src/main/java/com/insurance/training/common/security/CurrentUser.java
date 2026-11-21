package com.insurance.training.common.security;

/**
 * 当前请求的认证主体。
 *
 * <p>只由 {@link JwtService} 校验通过的 token 构造，Service 层据此判定数据范围。
 * 任何来自请求体/查询参数的 tenant、enterprise、employee 标识都不能替代这里的值。
 *
 * @param id         主体主键（admin_user.id 或 employee.id）
 * @param type       主体类型
 * @param roleCode   角色编码，仅 ADMIN 有意义
 * @param scope      数据范围
 * @param companyId  集团 id（字段名为历史命名），scope 为 INSURANCE_COMPANY 时必填
 * @param enterpriseId 企业 id，scope 为 ENTERPRISE 或 EMPLOYEE 时必填
 * @param displayName 展示名，用于审计日志
 */
public record CurrentUser(
        long id,
        PrincipalType type,
        String roleCode,
        DataScopeType scope,
        Long companyId,
        Long enterpriseId,
        String displayName
) {

    public boolean isAdmin() {
        return type == PrincipalType.ADMIN;
    }

    public boolean isEmployee() {
        return type == PrincipalType.EMPLOYEE;
    }

    public boolean isSuperAdmin() {
        return scope == DataScopeType.ALL;
    }
}
