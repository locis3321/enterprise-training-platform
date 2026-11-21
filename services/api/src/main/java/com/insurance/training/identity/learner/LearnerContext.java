package com.insurance.training.identity.learner;

import com.insurance.training.employee.entity.Employee;

import java.util.List;

/**
 * 学员上下文：一个账号 + 其名下的全部员工档案。
 *
 * <p>账号按手机号全局唯一，同一学员可在多个企业各有员工档案，因此「当前学员」不是
 * 单一 employee，而是「一个账号 + 多条企业关联」。所有按员工过滤的数据面（任务分配、
 * 学习记录、资源归属）都必须改用 {@link #ownsAny(List)} 判定，不能再用
 * {@code user.id() == assignment.employeeId}。
 *
 * @param accountId 账号主键（JWT 的 sub）
 * @param phone     账号手机号，仅用于服务端匹配，不写日志
 * @param employees 该手机号下所有**启用**的员工档案（已过滤停用员工与停用企业）
 * @param primary  主展示员工：用于档案页展示企业名与姓名；取 id 最小的一条，保证稳定
 */
public record LearnerContext(
        long accountId,
        String phone,
        List<Employee> employees,
        Employee primary
) {

    /** 该账号名下的员工 id 集合。 */
    public java.util.Set<Long> employeeIds() {
        return employees.stream().map(Employee::getId).collect(java.util.stream.Collectors.toSet());
    }

    /** 判断一批员工 id 中是否至少有一个属于本账号。 */
    public boolean ownsAny(java.util.Collection<Long> employeeIds) {
        java.util.Set<Long> mine = employeeIds();
        return employeeIds.stream().anyMatch(mine::contains);
    }

    /** 判断单个员工是否属于本账号。 */
    public boolean owns(long employeeId) {
        return employees.stream().anyMatch(e -> e.getId() != null && e.getId() == employeeId);
    }
}
