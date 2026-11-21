package com.insurance.training.common.security;

/**
 * 认证主体类型。
 *
 * <p>两类主体的接口面与数据范围来源不同：管理端按角色与数据范围过滤，
 * 小程序学员只能访问自己绑定的员工数据，禁止混用。
 */
public enum PrincipalType {
    ADMIN,
    EMPLOYEE
}
