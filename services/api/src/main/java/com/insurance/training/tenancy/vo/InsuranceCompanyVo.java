package com.insurance.training.tenancy.vo;

import com.insurance.training.tenancy.entity.InsuranceCompany;

import java.time.LocalDateTime;

/** 集团列表/详情视图。 */
public record InsuranceCompanyVo(
        long id,
        String name,
        String code,
        String contactName,
        String contactPhone,
        int status,
        LocalDateTime createdAt
) {

    public static InsuranceCompanyVo of(InsuranceCompany entity) {
        return new InsuranceCompanyVo(entity.getId(), entity.getName(), entity.getCode(),
                entity.getContactName(), entity.getContactPhone(),
                entity.getStatus() == null ? 0 : entity.getStatus(), entity.getCreatedAt());
    }
}
