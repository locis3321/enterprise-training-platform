package com.insurance.training.identity.vo;

import java.time.OffsetDateTime;

/** 登录结果：token 与主体信息，前端据此渲染菜单与数据范围提示。 */
public record AdminLoginResponse(
        String token,
        OffsetDateTime expiresAt,
        String displayName,
        String roleCode,
        String dataScope,
        Long insuranceCompanyId,
        Long enterpriseId
) {
}
