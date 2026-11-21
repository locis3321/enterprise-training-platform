package com.insurance.training.report.vo;

/** 子公司维度汇总。比率均以百分数（0-100，1 位小数）返回，同时给出分子分母。 */
public record EnterpriseSummaryVo(
        long enterpriseId,
        String enterpriseName,
        String companyName,
        int assignedCount,
        int completedCount,
        double completionRate,
        double studyHours,
        int examTakers,
        int examPassed,
        double examPassRate,
        int overdueCount) {
}
