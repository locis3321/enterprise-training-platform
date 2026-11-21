package com.insurance.training.tenancy.vo;

import com.insurance.training.tenancy.entity.Enterprise;

import java.time.LocalDateTime;

/** 企业列表/详情视图。 */
public record EnterpriseVo(
        long id,
        long insuranceCompanyId,
        String name,
        String code,
        String contactName,
        String contactPhone,
        int status,
        LocalDateTime createdAt
) {

    public static EnterpriseVo of(Enterprise entity) {
        return new EnterpriseVo(entity.getId(), entity.getInsuranceCompanyId(), entity.getName(),
                entity.getCode(), entity.getContactName(), entity.getContactPhone(),
                entity.getStatus() == null ? 0 : entity.getStatus(), entity.getCreatedAt());
    }
}
