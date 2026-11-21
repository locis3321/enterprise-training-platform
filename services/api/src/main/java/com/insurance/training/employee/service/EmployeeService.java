package com.insurance.training.employee.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.web.PageQuery;
import com.insurance.training.employee.dto.EmployeeRequest;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.employee.mapper.EmployeeMapper;
import com.insurance.training.employee.vo.EmployeeVo;
import com.insurance.training.tenancy.entity.Enterprise;
import com.insurance.training.tenancy.service.EnterpriseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 员工档案管理。子公司归属与集团归属在写入时强校验。 */
@Service
public class EmployeeService {

    private final EmployeeMapper mapper;
    private final EnterpriseService enterpriseService;
    private final AuditService auditService;
    private final com.insurance.training.identity.service.LearnerAccountService accountService;
    private final int maxPageSize;

    public EmployeeService(EmployeeMapper mapper, EnterpriseService enterpriseService,
                           AuditService auditService,
                           com.insurance.training.identity.service.LearnerAccountService accountService,
                           @Value("${app.page.max-size:200}") int maxPageSize) {
        this.mapper = mapper;
        this.enterpriseService = enterpriseService;
        this.auditService = auditService;
        this.accountService = accountService;
        this.maxPageSize = maxPageSize;
    }

    public PageResult<EmployeeVo> page(CurrentUser user, Long enterpriseId, String keyword,
                                       Integer status, Long page, Long pageSize) {
        PageQuery pq = PageQuery.of(page, pageSize, maxPageSize);
        var wrapper = Wrappers.<Employee>lambdaQuery().orderByDesc(Employee::getId);

        if (user.isSuperAdmin()) {
            if (enterpriseId != null) {
                wrapper.eq(Employee::getEnterpriseId, enterpriseId);
            }
        }
        else if (user.scope() == DataScopeType.ENTERPRISE) {
            if (user.enterpriseId() == null) {
                return PageResult.empty(pq.page(), pq.pageSize());
            }
            if (enterpriseId != null && !enterpriseId.equals(user.enterpriseId())) {
                throw new BizException(ErrorCode.FORBIDDEN, "无权访问其他企业的数据");
            }
            wrapper.eq(Employee::getEnterpriseId, user.enterpriseId());
        }
        else {
            if (user.companyId() == null) {
                return PageResult.empty(pq.page(), pq.pageSize());
            }
            wrapper.eq(Employee::getInsuranceCompanyId, user.companyId());
            if (enterpriseId != null) {
                wrapper.eq(Employee::getEnterpriseId, enterpriseId);
            }
        }

        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Employee::getName, kw).or().like(Employee::getPhone, kw));
        }
        if (status != null) {
            wrapper.eq(Employee::getStatus, status);
        }
        Page<Employee> result = mapper.selectPage(new Page<>(pq.page(), pq.pageSize()), wrapper);
        List<EmployeeVo> items = result.getRecords().stream().map(EmployeeVo::of).toList();
        return PageResult.of(items, result.getTotal(), pq.page(), pq.pageSize());
    }

    /** 员工可见性校验：先校验企业范围，再校验员工归属一致。 */
    public Employee getVisible(CurrentUser user, long id) {
        Employee employee = mapper.selectById(id);
        if (employee == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "员工不存在");
        }
        DataScopeGuard.checkEnterprise(user, employee.getInsuranceCompanyId(), employee.getEnterpriseId());
        return employee;
    }

    @Transactional
    public EmployeeVo create(CurrentUser user, EmployeeRequest request, String ip) {
        DataScopeGuard.requireAdmin();
        Enterprise enterprise = enterpriseService.getVisible(user, request.enterpriseId());
        assertEnterpriseActive(enterprise);
        assertPhoneUnique(enterprise.getId(), request.phone(), null);

        Employee entity = new Employee();
        entity.setInsuranceCompanyId(enterprise.getInsuranceCompanyId());
        entity.setEnterpriseId(enterprise.getId());
        entity.setName(request.name().trim());
        entity.setPhone(request.phone());
        entity.setIdCardNo(blankToNull(request.idCardNo()));
        entity.setStatus(request.status() == null ? 1 : request.status());
        mapper.insert(entity);
        // 手工建员工与 Excel 导入是同一业务事实：不生成账号则该员工无法用密码登录
        accountService.ensureAccount(entity.getPhone());
        auditService.record(user, "EMPLOYEE_CREATED", "employee", entity.getId(),
                "enterpriseId=" + enterprise.getId(), ip);
        return EmployeeVo.of(entity);
    }

    @Transactional
    public EmployeeVo update(CurrentUser user, long id, EmployeeRequest request, String ip) {
        Employee existing = getVisible(user, id);
        Enterprise enterprise = enterpriseService.getVisible(user, request.enterpriseId());
        if (!existing.getEnterpriseId().equals(enterprise.getId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持修改员工所属企业");
        }
        assertPhoneUnique(enterprise.getId(), request.phone(), id);
        String oldPhone = existing.getPhone();
        existing.setName(request.name().trim());
        existing.setPhone(request.phone());
        existing.setIdCardNo(blankToNull(request.idCardNo()));
        if (request.status() != null) {
            existing.setStatus(request.status());
        }
        mapper.updateById(existing);
        // 手机号变更必须迁移账号，否则旧号与新号各有一个账号，两人都能登录同一档案
        if (oldPhone != null && !oldPhone.equals(existing.getPhone())) {
            accountService.migratePhone(oldPhone, existing.getPhone());
        }
        else {
            // 号码未变：确保账号存在（绝不覆盖已有密码）
            accountService.ensureAccount(existing.getPhone());
        }
        auditService.record(user, "EMPLOYEE_UPDATED", "employee", id, null, ip);
        return EmployeeVo.of(existing);
    }

    /**
     * 重置学员密码为初始值（`anzhi` + 手机号后 6 位）。
     *
     * <p>**跨租户影响必须显式暴露**：账号按手机号全局唯一，而本方法只能校验
     * 「员工记录」的可见性。企业管理员重置本企业员工时，实际改的是该手机号在
     * **所有企业**共用的账号，会让学员在其他企业的登录凭证一起失效。
     * 因此返回该账号关联的启用企业数，供界面与审计告知影响面。
     */
    @Transactional
    public PasswordResetResult resetPassword(CurrentUser user, long id, String ip) {
        Employee existing = getVisible(user, id);
        accountService.resetToInitial(existing.getPhone());
        long affected = accountService.affectedEnterpriseCount(existing.getPhone());
        auditService.record(user, "EMPLOYEE_PASSWORD_RESET", "employee", id,
                "affectedEnterpriseCount=" + affected, ip);
        return new PasswordResetResult(affected);
    }

    /** 重置结果：只回传影响面，不回传密码（初始密码由规则可推导，无需下发）。 */
    public record PasswordResetResult(long affectedEnterpriseCount) {
    }

    private void assertEnterpriseActive(Enterprise enterprise) {
        if (!Integer.valueOf(1).equals(enterprise.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "企业已停用，不能新增员工");
        }
    }

    private void assertPhoneUnique(long enterpriseId, String phone, Long excludeId) {
        var wrapper = Wrappers.<Employee>lambdaQuery()
                .eq(Employee::getEnterpriseId, enterpriseId)
                .eq(Employee::getPhone, phone);
        if (excludeId != null) {
            wrapper.ne(Employee::getId, excludeId);
        }
        Long dup = mapper.selectCount(wrapper);
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.CONFLICT, "该企业下手机号已存在");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
