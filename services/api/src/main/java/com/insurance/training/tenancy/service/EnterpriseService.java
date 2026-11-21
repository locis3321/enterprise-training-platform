package com.insurance.training.tenancy.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.common.web.PageQuery;
import com.insurance.training.tenancy.dto.EnterpriseRequest;
import com.insurance.training.tenancy.entity.Enterprise;
import com.insurance.training.tenancy.mapper.EnterpriseMapper;
import com.insurance.training.tenancy.vo.EnterpriseVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 子公司/部门管理。归属集团不可跨范围指定。 */
@Service
public class EnterpriseService {

    private final EnterpriseMapper mapper;
    private final InsuranceCompanyService companyService;
    private final AuditService auditService;
    private final int maxPageSize;

    public EnterpriseService(EnterpriseMapper mapper, InsuranceCompanyService companyService,
                             AuditService auditService, @Value("${app.page.max-size:200}") int maxPageSize) {
        this.mapper = mapper;
        this.companyService = companyService;
        this.auditService = auditService;
        this.maxPageSize = maxPageSize;
    }

    public PageResult<EnterpriseVo> page(CurrentUser user, Long companyId, String keyword,
                                         Integer status, Long page, Long pageSize) {
        PageQuery pq = PageQuery.of(page, pageSize, maxPageSize);
        var wrapper = Wrappers.<Enterprise>lambdaQuery().orderByDesc(Enterprise::getId);
        if (user.isSuperAdmin()) {
            if (companyId != null) {
                wrapper.eq(Enterprise::getInsuranceCompanyId, companyId);
            }
        }
        else if (user.scope() == com.insurance.training.common.security.DataScopeType.ENTERPRISE) {
            if (user.enterpriseId() == null) {
                return PageResult.empty(pq.page(), pq.pageSize());
            }
            wrapper.eq(Enterprise::getId, user.enterpriseId());
        }
        else {
            if (user.companyId() == null) {
                return PageResult.empty(pq.page(), pq.pageSize());
            }
            wrapper.eq(Enterprise::getInsuranceCompanyId, user.companyId());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Enterprise::getName, kw).or().like(Enterprise::getCode, kw));
        }
        if (status != null) {
            wrapper.eq(Enterprise::getStatus, status);
        }
        Page<Enterprise> result = mapper.selectPage(new Page<>(pq.page(), pq.pageSize()), wrapper);
        List<EnterpriseVo> items = result.getRecords().stream().map(EnterpriseVo::of).toList();
        return PageResult.of(items, result.getTotal(), pq.page(), pq.pageSize());
    }

    public Enterprise getVisible(CurrentUser user, long id) {
        Enterprise enterprise = mapper.selectById(id);
        if (enterprise == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "企业不存在");
        }
        DataScopeGuard.checkEnterprise(user, enterprise.getInsuranceCompanyId(), enterprise.getId());
        return enterprise;
    }

    @Transactional
    public EnterpriseVo create(CurrentUser user, EnterpriseRequest request, String ip) {
        DataScopeGuard.requireAdmin();
        // 企业管理员只能维护本企业信息，不具备新建企业的权限
        if (user.scope() == com.insurance.training.common.security.DataScopeType.ENTERPRISE) {
            throw new BizException(ErrorCode.FORBIDDEN, "企业管理员不能新建企业");
        }
        DataScopeGuard.checkCompany(user, request.insuranceCompanyId());
        companyService.requireExists(request.insuranceCompanyId());

        Long dup = mapper.selectCount(Wrappers.<Enterprise>lambdaQuery()
                .eq(Enterprise::getInsuranceCompanyId, request.insuranceCompanyId())
                .eq(Enterprise::getCode, request.code()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.CONFLICT, "同一集团下子公司编码已存在");
        }
        Enterprise entity = new Enterprise();
        entity.setInsuranceCompanyId(request.insuranceCompanyId());
        entity.setName(request.name().trim());
        entity.setCode(request.code());
        entity.setContactName(blankToNull(request.contactName()));
        entity.setContactPhone(blankToNull(request.contactPhone()));
        entity.setStatus(request.status() == null ? 1 : request.status());
        mapper.insert(entity);
        auditService.record(user, "ENTERPRISE_CREATED", "enterprise", entity.getId(),
                "companyId=" + entity.getInsuranceCompanyId(), ip);
        return EnterpriseVo.of(entity);
    }

    @Transactional
    public EnterpriseVo update(CurrentUser user, long id, EnterpriseRequest request, String ip) {
        Enterprise existing = getVisible(user, id);
        DataScopeGuard.checkCompany(user, request.insuranceCompanyId());
        if (!existing.getInsuranceCompanyId().equals(request.insuranceCompanyId())) {
            // 迁移归属属于高风险动作，MVP 只允许同公司内维护
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持修改子公司归属集团");
        }
        Long dup = mapper.selectCount(Wrappers.<Enterprise>lambdaQuery()
                .eq(Enterprise::getInsuranceCompanyId, request.insuranceCompanyId())
                .eq(Enterprise::getCode, request.code())
                .ne(Enterprise::getId, id));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.CONFLICT, "同一集团下子公司编码已存在");
        }
        existing.setName(request.name().trim());
        existing.setCode(request.code());
        existing.setContactName(blankToNull(request.contactName()));
        existing.setContactPhone(blankToNull(request.contactPhone()));
        if (request.status() != null) {
            existing.setStatus(request.status());
        }
        mapper.updateById(existing);
        auditService.record(user, "ENTERPRISE_UPDATED", "enterprise", id, null, ip);
        return EnterpriseVo.of(existing);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
