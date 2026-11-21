package com.insurance.training.common.security;

/** 数据范围类型，与 {@code role.data_scope_type} 一一对应。 */
// INSURANCE_COMPANY 是历史枚举名，语义为「集团/公司」；落库值不可改，展示层已去行业词。
public enum DataScopeType {
    /** 全平台。 */
    ALL,
    /** 限定到集团，可见其下所有子公司。 */
    INSURANCE_COMPANY,
    /** 限定到单个子公司/部门。 */
    ENTERPRISE
}
