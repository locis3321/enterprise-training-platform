package com.insurance.training.identity.service;

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
import com.insurance.training.identity.dto.AdminSelfPasswordRequest;
import com.insurance.training.identity.dto.AdminSelfProfileRequest;
import com.insurance.training.identity.dto.AdminUserCreateRequest;
import com.insurance.training.identity.dto.AdminUserUpdateRequest;
import com.insurance.training.identity.entity.AdminUser;
import com.insurance.training.identity.entity.Role;
import com.insurance.training.identity.mapper.AdminUserMapper;
import com.insurance.training.identity.mapper.RoleMapper;
import com.insurance.training.identity.vo.AdminProfileResponse;
import com.insurance.training.identity.vo.AdminUserVo;
import com.insurance.training.tenancy.entity.Enterprise;
import com.insurance.training.tenancy.mapper.EnterpriseMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 管理端账号管理。
 *
 * <p>权限：逐级自治（D2）。范围判定在 {@link DataScopeGuard}，自我保护在此处显式判定。
 * 密码一律 BCrypt(10)，任何响应与审计都不回显明文或哈希。
 */
@Service
public class AdminUserService {

    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final int STATUS_ENABLED = 1;
    private static final int STATUS_DISABLED = 0;

    private final AdminUserMapper adminUserMapper;
    private final RoleMapper roleMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final AuditService auditService;
    private final AdminAccountThrottle throttle;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    public AdminUserService(AdminUserMapper adminUserMapper, RoleMapper roleMapper,
                            EnterpriseMapper enterpriseMapper, AuditService auditService,
                            AdminAccountThrottle throttle) {
        this.adminUserMapper = adminUserMapper;
        this.roleMapper = roleMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.auditService = auditService;
        this.throttle = throttle;
    }

    public PageResult<AdminUserVo> page(CurrentUser actor, String keyword, Long page, Long pageSize,
                                        int maxPageSize) {
        PageQuery q = PageQuery.of(page, pageSize, maxPageSize);
        var wrapper = Wrappers.<AdminUser>lambdaQuery();
        applyScope(actor, wrapper);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(AdminUser::getUsername, kw).or().like(AdminUser::getDisplayName, kw));
        }
        wrapper.orderByAsc(AdminUser::getId);
        Page<AdminUser> result = adminUserMapper.selectPage(new Page<>(q.page(), q.pageSize()), wrapper);
        List<AdminUserVo> items = result.getRecords().stream().map(AdminUserVo::of).toList();
        return PageResult.of(items, result.getTotal(), q.page(), q.pageSize());
    }

    public AdminUserVo detail(CurrentUser actor, long id) {
        AdminUser target = getWritable(actor, id);
        return AdminUserVo.of(target);
    }

    @Transactional
    public AdminUserVo create(CurrentUser actor, AdminUserCreateRequest req, String ip) {
        requirePasswordPolicy(req.password());
        String roleCode = req.roleCode().trim();
        Role role = roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getCode, roleCode));
        if (role == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色不存在：" + roleCode);
        }
        resolveOwnership(roleCode, req.insuranceCompanyId(), req.enterpriseId());
        Long companyId = req.insuranceCompanyId();
        Long enterpriseId = req.enterpriseId();
        DataScopeGuard.checkAdminUserCreate(actor, roleCode, companyId, enterpriseId);

        AdminUser existing = adminUserMapper.selectOne(
                Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getUsername, req.username().trim()));
        if (existing != null) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在（含已删除账号）");
        }

        AdminUser u = new AdminUser();
        u.setUsername(req.username().trim());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setDisplayName(req.displayName().trim());
        u.setRoleCode(roleCode);
        u.setInsuranceCompanyId(companyId);
        u.setEnterpriseId(enterpriseId);
        u.setStatus(STATUS_ENABLED);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        adminUserMapper.insert(u);
        auditService.record(actor, "ADMIN_USER_CREATED", "admin_user", u.getId(),
                "username=" + u.getUsername() + ", role=" + roleCode, ip);
        return AdminUserVo.of(u);
    }

    @Transactional
    public AdminUserVo update(CurrentUser actor, long id, AdminUserUpdateRequest req, String ip) {
        AdminUser target = getWritable(actor, id);
        if (target.getId() == actor.id()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能修改自己的角色与数据范围，请使用个人设置");
        }
        String roleCode = req.roleCode().trim();
        Role role = roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getCode, roleCode));
        if (role == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色不存在：" + roleCode);
        }
        resolveOwnership(roleCode, req.insuranceCompanyId(), req.enterpriseId());
        DataScopeGuard.checkAdminUserCreate(actor, roleCode, req.insuranceCompanyId(), req.enterpriseId());

        Integer status = req.status() == null ? target.getStatus() : req.status();
        if (status != STATUS_ENABLED && status != STATUS_DISABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能为 0（停用）或 1（启用）");
        }
        target.setDisplayName(req.displayName().trim());
        target.setRoleCode(roleCode);
        target.setInsuranceCompanyId(req.insuranceCompanyId());
        target.setEnterpriseId(req.enterpriseId());
        target.setStatus(status);
        target.setUpdatedAt(LocalDateTime.now());
        adminUserMapper.updateById(target);
        auditService.record(actor, "ADMIN_USER_UPDATED", "admin_user", target.getId(),
                "username=" + target.getUsername() + ", role=" + roleCode + ", status=" + status, ip);
        return AdminUserVo.of(target);
    }

    /** 重置他人密码：返回操作者指定的新密码，仅此一次，不落日志与审计。 */
    @Transactional
    public void resetPassword(CurrentUser actor, long id, String newPassword, String ip) {
        requirePasswordPolicy(newPassword);
        AdminUser target = getWritable(actor, id);
        if (target.getId() == actor.id()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请使用个人设置修改自己的密码");
        }
        throttle.assertResetAllowed(actor.id());
        target.setPasswordHash(encoder.encode(newPassword));
        target.setUpdatedAt(LocalDateTime.now());
        adminUserMapper.updateById(target);
        throttle.onReset(actor.id());
        auditService.record(actor, "ADMIN_USER_PASSWORD_RESET", "admin_user", target.getId(),
                "username=" + target.getUsername(), ip);
    }

    @Transactional
    public void delete(CurrentUser actor, long id, String ip) {
        AdminUser target = getWritable(actor, id);
        if (target.getId() == actor.id()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能删除自己的账号");
        }
        adminUserMapper.deleteById(target.getId());
        auditService.record(actor, "ADMIN_USER_DELETED", "admin_user", target.getId(),
                "username=" + target.getUsername(), ip);
    }

    @Transactional
    public AdminProfileResponse changeSelfPassword(CurrentUser actor, AdminSelfPasswordRequest req, String ip) {
        if (req.newPassword().equals(req.oldPassword())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码不能与旧密码相同");
        }
        requirePasswordPolicy(req.newPassword());
        throttle.assertSelfPasswordAllowed(actor.id());
        AdminUser me = adminUserMapper.selectById(actor.id());
        if (me == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "账号不存在");
        }
        if (!encoder.matches(req.oldPassword(), me.getPasswordHash())) {
            throttle.onSelfPasswordFailure(actor.id());
            throw new BizException(ErrorCode.BAD_REQUEST, "旧密码不正确");
        }
        me.setPasswordHash(encoder.encode(req.newPassword()));
        me.setUpdatedAt(LocalDateTime.now());
        adminUserMapper.updateById(me);
        throttle.onSelfPasswordSuccess(actor.id());
        auditService.record(actor, "ADMIN_USER_SELF_PASSWORD_CHANGED", "admin_user", me.getId(),
                "username=" + me.getUsername(), ip);
        return profileOf(me);
    }

    @Transactional
    public AdminProfileResponse updateSelfProfile(CurrentUser actor, AdminSelfProfileRequest req, String ip) {
        AdminUser me = adminUserMapper.selectById(actor.id());
        if (me == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "账号不存在");
        }
        me.setDisplayName(req.displayName().trim());
        me.setUpdatedAt(LocalDateTime.now());
        adminUserMapper.updateById(me);
        auditService.record(actor, "ADMIN_USER_UPDATED", "admin_user", me.getId(),
                "username=" + me.getUsername() + ", self=true", ip);
        return profileOf(me);
    }

    // ---------- 内部 ----------

    private AdminProfileResponse profileOf(AdminUser me) {
        Role role = roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getCode, me.getRoleCode()));
        return new AdminProfileResponse(me.getId(), me.getUsername(), me.getDisplayName(), me.getRoleCode(),
                role == null ? null : role.getDataScopeType(),
                me.getInsuranceCompanyId(), me.getEnterpriseId());
    }

    /** 目标账号必须存在且在当前操作者范围内。 */
    private AdminUser getWritable(CurrentUser actor, long id) {
        AdminUser target = adminUserMapper.selectById(id);
        if (target == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "账号不存在");
        }
        DataScopeGuard.checkAdminUserWrite(actor, target.getInsuranceCompanyId(), target.getEnterpriseId());
        return target;
    }

    /**
     * 归属与角色必须自洽：平台级不绑归属，集团级必须绑集团且不绑子公司，
     * 子公司级必须同时绑集团与子公司，且子公司必须属于该集团。
     * 缺失或矛盾一律 400，不静默补默认值。
     */
    private void resolveOwnership(String roleCode, Long companyId, Long enterpriseId) {
        switch (roleCode) {
            case "SUPER_ADMIN" -> {
                if (companyId != null || enterpriseId != null) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "平台管理员不应绑定集团或子公司");
                }
            }
            case "INSURANCE_ADMIN" -> {
                if (companyId == null) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "集团管理员必须指定所属集团");
                }
                if (enterpriseId != null) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "集团管理员不应绑定具体子公司");
                }
            }
            case "ENTERPRISE_ADMIN" -> {
                if (companyId == null || enterpriseId == null) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "子公司管理员必须指定所属集团与子公司");
                }
                Enterprise enterprise = enterpriseMapper.selectById(enterpriseId);
                if (enterprise == null) {
                    throw new BizException(ErrorCode.NOT_FOUND, "子公司不存在");
                }
                if (!Objects.equals(enterprise.getInsuranceCompanyId(), companyId)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "所选子公司不属于该集团");
                }
            }
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "角色不合法：" + roleCode);
        }
    }

    /** 范围过滤：平台全见；集团见本集团；子公司只见本子公司。 */
    private void applyScope(CurrentUser actor,
                            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AdminUser> wrapper) {
        if (actor.isSuperAdmin()) {
            return;
        }
        if (actor.companyId() == null) {
            // 未绑定集团的账号不应看到任何他租户数据：用不可能成立的条件返回真实空页
            wrapper.eq(AdminUser::getId, -1L);
            return;
        }
        wrapper.eq(AdminUser::getInsuranceCompanyId, actor.companyId());
        if (actor.scope() == DataScopeType.ENTERPRISE) {
            wrapper.eq(AdminUser::getEnterpriseId, actor.enterpriseId());
        }
    }

    private void requirePasswordPolicy(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }
}
