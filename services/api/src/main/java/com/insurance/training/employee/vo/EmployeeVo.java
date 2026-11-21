package com.insurance.training.employee.vo;

import com.insurance.training.employee.entity.Employee;

import java.time.LocalDateTime;

/** 员工视图。手机号脱敏输出（管理端完整展示由独立权限控制，此处统一脱敏）。 */
public record EmployeeVo(
        long id,
        long insuranceCompanyId,
        long enterpriseId,
        String name,
        String phoneMasked,
        boolean idCardProvided,
        int status,
        LocalDateTime createdAt
) {

    public static EmployeeVo of(Employee entity) {
        return new EmployeeVo(entity.getId(), entity.getInsuranceCompanyId(), entity.getEnterpriseId(),
                entity.getName(), maskPhone(entity.getPhone()),
                entity.getIdCardNo() != null && !entity.getIdCardNo().isBlank(),
                entity.getStatus() == null ? 0 : entity.getStatus(), entity.getCreatedAt());
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
