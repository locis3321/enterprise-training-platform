package com.insurance.training.identity.learner;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.identity.entity.LearnerAccount;
import com.insurance.training.identity.mapper.LearnerAccountMapper;
import com.insurance.training.identity.service.LearnerAccountService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 学员身份校验。
 *
 * <p>每次请求重新查库，不信任 JWT 里的状态快照：员工被停用、企业被停用必须立即生效。
 *
 * <p>账号化后（T1）「当前学员」是**一个账号 + 多条企业员工档案**，因此本类返回
 * {@link LearnerContext} 而不是单个 {@code Employee}；调用方必须用
 * {@code context.owns(assignment.employeeId)} 判定归属，不能再用
 * {@code user.id() == assignment.employeeId}。
 */
@Component
public class LearnerGuard {

    private final LearnerAccountMapper accountMapper;
    private final LearnerAccountService accountService;

    public LearnerGuard(LearnerAccountMapper accountMapper, LearnerAccountService accountService) {
        this.accountMapper = accountMapper;
        this.accountService = accountService;
    }

    public LearnerContext requireLearner(CurrentUser user) {
        if (!user.isEmployee()) {
            throw new BizException(ErrorCode.FORBIDDEN, "该接口仅学员可访问");
        }
        LearnerAccount account = accountMapper.selectById(user.id());
        if (account == null) {
            // sub 指向不存在的账号：多为账号化之前的旧 token，要求重新登录
            throw new BizException(ErrorCode.UNAUTHORIZED, "学员账号不存在，请重新登录");
        }
        if (!Integer.valueOf(1).equals(account.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已停用，请联系管理员");
        }
        List<Employee> employees = accountService.usableEmployees(account.getPhone());
        if (employees.isEmpty()) {
            throw new BizException(ErrorCode.FORBIDDEN, "该手机号下没有可用的员工档案，无法访问培训内容");
        }
        return new LearnerContext(account.getId(), account.getPhone(), employees, employees.get(0));
    }
}
