package com.insurance.training.identity.vo;

import com.insurance.training.identity.entity.AdminUser;

import java.time.LocalDateTime;

/** 账号视图。显式列出可见字段，不含 passwordHash。 */
public record AdminUserVo(long id, String username, String displayName, String roleCode,
        Long insuranceCompanyId, Long enterpriseId, Integer status,
        LocalDateTime lastLoginAt, LocalDateTime createdAt) {

    public static AdminUserVo of(AdminUser u) {
        return new AdminUserVo(u.getId(), u.getUsername(), u.getDisplayName(), u.getRoleCode(),
                u.getInsuranceCompanyId(), u.getEnterpriseId(), u.getStatus(),
                u.getLastLoginAt(), u.getCreatedAt());
    }
}
