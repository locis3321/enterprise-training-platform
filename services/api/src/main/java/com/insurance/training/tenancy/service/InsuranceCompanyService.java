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
import com.insurance.training.tenancy.dto.InsuranceCompanyRequest;
import com.insurance.training.tenancy.entity.InsuranceCompany;
import com.insurance.training.tenancy.mapper.InsuranceCompanyMapper;
import com.insurance.training.tenancy.vo.InsuranceCompanyVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 集团/公司管理：仅平台管理员可写，读操作按数据范围过滤。 */
@Service
public class InsuranceCompanyService {

    private final InsuranceCompanyMapper mapper;
    private final AuditService auditService;
    private final int maxPageSize;

    public InsuranceCompanyService(InsuranceCompanyMapper mapper, AuditService auditService,
                                   @Value("${app.page.max-size:200}") int maxPageSize) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.maxPageSize = maxPageSize;
    }

    public PageResult<InsuranceCompanyVo> page(CurrentUser user, String keyword, Long page, Long pageSize) {
        PageQuery pq = PageQuery.of(page, pageSize, maxPageSize);
        var wrapper = Wrappers.<InsuranceCompany>lambdaQuery().orderByDesc(InsuranceCompany::getId);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(InsuranceCompany::getName, keyword.trim())
                    .or().like(InsuranceCompany::getCode, keyword.trim()));
        }
        // 非超管只能看到自己所属公司
        if (!user.isSuperAdmin()) {
            if (user.companyId() == null) {
                return PageResult.empty(pq.page(), pq.pageSize());
            }
            wrapper.eq(InsuranceCompany::getId, user.companyId());
        }
        Page<InsuranceCompany> result = mapper.selectPage(new Page<>(pq.page(), pq.pageSize()), wrapper);
        List<InsuranceCompanyVo> items = result.getRecords().stream().map(InsuranceCompanyVo::of).toList();
        return PageResult.of(items, result.getTotal(), pq.page(), pq.pageSize());
    }

    @Transactional
    public InsuranceCompanyVo create(CurrentUser user, InsuranceCompanyRequest request, String ip) {
        DataScopeGuard.requireSuperAdmin();
        Long dup = mapper.selectCount(Wrappers.<InsuranceCompany>lambdaQuery()
                .eq(InsuranceCompany::getCode, request.code()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.CONFLICT, "集团编码已存在");
        }
        InsuranceCompany entity = new InsuranceCompany();
        entity.setName(request.name().trim());
        entity.setCode(request.code());
        entity.setContactName(blankToNull(request.contactName()));
        entity.setContactPhone(blankToNull(request.contactPhone()));
        entity.setStatus(request.status() == null ? 1 : request.status());
        mapper.insert(entity);
        auditService.record(user, "COMPANY_CREATED", "insurance_company", entity.getId(),
                "code=" + entity.getCode(), ip);
        return InsuranceCompanyVo.of(entity);
    }

    @Transactional
    public InsuranceCompanyVo update(CurrentUser user, long id, InsuranceCompanyRequest request, String ip) {
        DataScopeGuard.requireSuperAdmin();
        InsuranceCompany existing = mapper.selectById(id);
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "集团不存在");
        }
        Long dup = mapper.selectCount(Wrappers.<InsuranceCompany>lambdaQuery()
                .eq(InsuranceCompany::getCode, request.code())
                .ne(InsuranceCompany::getId, id));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.CONFLICT, "集团编码已存在");
        }
        existing.setName(request.name().trim());
        existing.setCode(request.code());
        existing.setContactName(blankToNull(request.contactName()));
        existing.setContactPhone(blankToNull(request.contactPhone()));
        if (request.status() != null) {
            existing.setStatus(request.status());
        }
        mapper.updateById(existing);
        auditService.record(user, "COMPANY_UPDATED", "insurance_company", id, null, ip);
        return InsuranceCompanyVo.of(existing);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 供其他服务校验公司存在性，避免直接跨模块访问 Mapper。 */
    public void requireExists(long companyId) {
        if (mapper.selectById(companyId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "所属集团不存在");
        }
    }
}
