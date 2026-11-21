package com.insurance.training.identity.vo;

/** 当前登录管理员信息（GET /admin/auth/me）。 */
public record AdminProfileResponse(
        long id,
        String username,
        String displayName,
        String roleCode,
        String dataScope,
        Long insuranceCompanyId,
        Long enterpriseId
) {
}
