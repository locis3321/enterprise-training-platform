package com.insurance.training.training.service;

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
import com.insurance.training.tenancy.service.InsuranceCompanyService;
import com.insurance.training.training.dto.PackageRequest;
import com.insurance.training.training.entity.TrainingPackage;
import com.insurance.training.training.entity.TrainingPackageVersion;
import com.insurance.training.training.mapper.TrainingPackageMapper;
import com.insurance.training.training.mapper.TrainingPackageVersionMapper;
import com.insurance.training.training.vo.PackageVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 培训包基本信息的读写。归属与可见范围规则与课程一致。 */
@Service
public class PackageService {

    private final TrainingPackageMapper packageMapper;
    private final TrainingPackageVersionMapper versionMapper;
    private final InsuranceCompanyService companyService;
    private final AuditService auditService;
    private final int maxPageSize;

    public PackageService(TrainingPackageMapper packageMapper, TrainingPackageVersionMapper versionMapper,
                          InsuranceCompanyService companyService, AuditService auditService,
                          @Value("${app.page.max-size:200}") int maxPageSize) {
        this.packageMapper = packageMapper;
        this.versionMapper = versionMapper;
        this.companyService = companyService;
        this.auditService = auditService;
        this.maxPageSize = maxPageSize;
    }

    /** 列表：同公司共享；企业管理员也能看到同公司其他企业的包，只读。 */
    public PageResult<PackageVo> page(CurrentUser user, String keyword, String status,
                                      Long page, Long pageSize) {
        PageQuery pq = PageQuery.of(page, pageSize, maxPageSize);
        var wrapper = Wrappers.<TrainingPackage>lambdaQuery().orderByDesc(TrainingPackage::getId);

        if (!user.isSuperAdmin()) {
            if (user.companyId() == null) {
                return PageResult.empty(pq.page(), pq.pageSize());
            }
            wrapper.eq(TrainingPackage::getInsuranceCompanyId, user.companyId());
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(TrainingPackage::getTitle, keyword.trim());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(TrainingPackage::getStatus, status);
        }

        Page<TrainingPackage> result = packageMapper.selectPage(new Page<>(pq.page(), pq.pageSize()), wrapper);
        List<PackageVo> items = result.getRecords().stream().map(PackageVo::of).toList();
        return PageResult.of(items, result.getTotal(), pq.page(), pq.pageSize());
    }

    /** 读取并校验可见（同公司）。不存在与无权分别 404 / 403。 */
    public TrainingPackage getVisible(CurrentUser user, long packageId) {
        TrainingPackage pkg = packageMapper.selectById(packageId);
        if (pkg == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训包不存在");
        }
        if (!user.isSuperAdmin()
                && (user.companyId() == null || !user.companyId().equals(pkg.getInsuranceCompanyId()))) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问其他集团的培训包");
        }
        return pkg;
    }

    /** 读取并校验可写：企业管理员只能写本企业归属的包，公司级包只读。 */
    public TrainingPackage getWritable(CurrentUser user, long packageId) {
        TrainingPackage pkg = getVisible(user, packageId);
        DataScopeGuard.checkCourseWrite(user, pkg.getInsuranceCompanyId(), pkg.getOwnerEnterpriseId());
        return pkg;
    }

    /** 新建培训包：同事务创建 v1 草稿。归属由服务端决定，客户端无法伪造。 */
    @Transactional
    public PackageVo create(CurrentUser user, PackageRequest request, String ip) {
        DataScopeGuard.requireAdmin();
        Long companyId = resolveCompanyId(user, request.insuranceCompanyId());
        Long ownerEnterpriseId = resolveOwnerEnterprise(user);

        TrainingPackage pkg = new TrainingPackage();
        pkg.setInsuranceCompanyId(companyId);
        pkg.setOwnerEnterpriseId(ownerEnterpriseId);
        pkg.setTitle(request.title().trim());
        pkg.setDescription(blankToNull(request.description()));
        pkg.setStatus("DRAFT");
        pkg.setLatestVersionNo(1);
        pkg.setCreatedByAdminId(user.id());
        packageMapper.insert(pkg);

        TrainingPackageVersion v1 = new TrainingPackageVersion();
        v1.setPackageId(pkg.getId());
        v1.setVersionNo(1);
        v1.setTitle(pkg.getTitle());
        v1.setDescription(pkg.getDescription());
        v1.setStatus("DRAFT");
        try {
            versionMapper.insert(v1);
        }
        catch (DuplicateKeyException ex) {
            throw new BizException(ErrorCode.CONFLICT, "版本号冲突，请重试");
        }

        auditService.record(user, "PACKAGE_CREATED", "training_package", pkg.getId(),
                "companyId=" + companyId + ",ownerEnterpriseId=" + ownerEnterpriseId, ip);
        return PackageVo.of(pkg);
    }

    /**
     * 修改基本信息。
     *
     * <p>包标题是列表权威来源；若存在最高 DRAFT 版本则条件同步其标题，
     * 并发发布导致影响 0 行时跳过同步（已发布快照必须保持冻结），包改名本身仍成功。
     */
    @Transactional
    public PackageVo update(CurrentUser user, long packageId, PackageRequest request, String ip) {
        TrainingPackage pkg = getWritable(user, packageId);
        pkg.setTitle(request.title().trim());
        pkg.setDescription(blankToNull(request.description()));
        packageMapper.updateById(pkg);

        TrainingPackageVersion draft = versionMapper.selectOne(Wrappers.<TrainingPackageVersion>lambdaQuery()
                .eq(TrainingPackageVersion::getPackageId, packageId)
                .eq(TrainingPackageVersion::getStatus, "DRAFT")
                .orderByDesc(TrainingPackageVersion::getVersionNo)
                .last("limit 1"));
        if (draft != null) {
            versionMapper.update(null, Wrappers.<TrainingPackageVersion>lambdaUpdate()
                    .set(TrainingPackageVersion::getTitle, pkg.getTitle())
                    .set(TrainingPackageVersion::getDescription, pkg.getDescription())
                    .eq(TrainingPackageVersion::getId, draft.getId())
                    .eq(TrainingPackageVersion::getStatus, "DRAFT"));
        }

        auditService.record(user, "PACKAGE_UPDATED", "training_package", packageId, null, ip);
        return PackageVo.of(pkg);
    }

    /**
     * 下线：PUBLISHED → OFFLINE。学员不再可见；已发布版本与已下发任务不受影响
     * （任务绑定的是不可变包版本，运营下线新内容不应中断已下发的培训）。
     */
    @Transactional
    public PackageVo offline(CurrentUser user, long packageId, String ip) {
        TrainingPackage pkg = getWritable(user, packageId);
        if (!"PUBLISHED".equals(pkg.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "只有已发布培训包可下线");
        }
        int updated = packageMapper.update(null, Wrappers.<TrainingPackage>lambdaUpdate()
                .set(TrainingPackage::getStatus, "OFFLINE")
                .eq(TrainingPackage::getId, pkg.getId())
                .eq(TrainingPackage::getStatus, "PUBLISHED"));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "培训包状态已被其他操作改变，请刷新后重试");
        }
        pkg.setStatus("OFFLINE");
        auditService.record(user, "PACKAGE_OFFLINE", "training_package", packageId, null, ip);
        return PackageVo.of(pkg);
    }

    /** 恢复上线：OFFLINE → PUBLISHED，要求存在已发布版本（否则没有可恢复的内容）。 */
    @Transactional
    public PackageVo online(CurrentUser user, long packageId, String ip) {
        TrainingPackage pkg = getWritable(user, packageId);
        if (!"OFFLINE".equals(pkg.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "只有已下线培训包可恢复上线");
        }
        if (pkg.getPublishedVersionNo() == null) {
            throw new BizException(ErrorCode.CONFLICT, "培训包从未发布，无法上线");
        }
        int updated = packageMapper.update(null, Wrappers.<TrainingPackage>lambdaUpdate()
                .set(TrainingPackage::getStatus, "PUBLISHED")
                .eq(TrainingPackage::getId, pkg.getId())
                .eq(TrainingPackage::getStatus, "OFFLINE"));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "培训包状态已被其他操作改变，请刷新后重试");
        }
        pkg.setStatus("PUBLISHED");
        auditService.record(user, "PACKAGE_ONLINE", "training_package", packageId, null, ip);
        return PackageVo.of(pkg);
    }

    /** 超管可显式指定任意公司；其他角色固定本公司，请求体的值一律忽略。 */
    private Long resolveCompanyId(CurrentUser user, Long requestedCompanyId) {
        if (user.isSuperAdmin()) {
            Long target = requestedCompanyId != null ? requestedCompanyId : user.companyId();
            if (target == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "超级管理员建包需指定集团（insuranceCompanyId）");
            }
            companyService.requireExists(target);
            return target;
        }
        if (user.companyId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前账号未绑定集团，无法建包");
        }
        return user.companyId();
    }

    private Long resolveOwnerEnterprise(CurrentUser user) {
        if (user.scope() == DataScopeType.ENTERPRISE) {
            if (user.enterpriseId() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "企业管理员账号未绑定企业，无法建包");
            }
            return user.enterpriseId();
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}