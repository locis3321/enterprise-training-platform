package com.insurance.training.identity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.JwtService;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.identity.dto.AdminLoginRequest;
import com.insurance.training.identity.entity.AdminUser;
import com.insurance.training.identity.entity.Role;
import com.insurance.training.identity.mapper.AdminUserMapper;
import com.insurance.training.identity.mapper.RoleMapper;
import com.insurance.training.identity.vo.AdminLoginResponse;
import com.insurance.training.identity.vo.AdminProfileResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 管理端认证。
 *
 * <p>安全约定：
 * <ul>
 *   <li>账号不存在与密码错误返回同一提示，防止账号枚举；</li>
 *   <li>失败计数按「账号 + 来源 IP」维度限制，避免他人恶意锁定单个账号；</li>
 *   <li>口令只以 BCrypt 哈希存在，任何日志与响应都不回显。</li>
 * </ul>
 */
@Service
public class AdminAuthService {

    private final AdminUserMapper adminUserMapper;
    private final RoleMapper roleMapper;
    private final JwtService jwtService;
    private final LoginThrottle throttle;
    private final AuditService auditService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    private final AdminIpWhitelist whitelist;

    public AdminAuthService(AdminUserMapper adminUserMapper, RoleMapper roleMapper,
                            JwtService jwtService, LoginThrottle throttle, AuditService auditService,
                            AdminIpWhitelist whitelist) {
        this.adminUserMapper = adminUserMapper;
        this.roleMapper = roleMapper;
        this.jwtService = jwtService;
        this.throttle = throttle;
        this.auditService = auditService;
        this.whitelist = whitelist;
    }

    public AdminLoginResponse login(AdminLoginRequest request, String ip) {
        String username = request.username().trim();
        // 白名单判定放在最前置：早于限流与查库，不满足条件不进入任何后续分支
        if (whitelist != null) {
            try {
                whitelist.assertAllowed(ip);
            }
            catch (com.insurance.training.common.exception.BizException ex) {
                auditService.record(null, "ADMIN_LOGIN_IP_BLOCKED", "admin_user", null,
                        "username=" + username, ip);
                throw ex;
            }
        }
        throttle.assertAllowed(username, ip);

        AdminUser user = adminUserMapper.selectOne(
                Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getUsername, username));

        if (user == null || !encoder.matches(request.password(), user.getPasswordHash())) {
            throttle.onFailure(username, ip);
            auditService.record(null, "ADMIN_LOGIN_FAILED", "admin_user", user == null ? null : user.getId(),
                    "username=" + username, ip);
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号或密码不正确");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            auditService.record(null, "ADMIN_LOGIN_DISABLED", "admin_user", user.getId(),
                    "username=" + username, ip);
            throw new BizException(ErrorCode.FORBIDDEN, "账号已停用，请联系平台管理员");
        }

        Role role = roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getCode, user.getRoleCode()));
        if (role == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "账号角色配置异常，请联系平台管理员");
        }
        DataScopeType scope = DataScopeType.valueOf(role.getDataScopeType());
        if (scope != DataScopeType.ALL
                && (scope == DataScopeType.INSURANCE_COMPANY && user.getInsuranceCompanyId() == null
                || scope == DataScopeType.ENTERPRISE && user.getEnterpriseId() == null)) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "账号数据范围配置不完整，请联系平台管理员");
        }

        throttle.onSuccess(username, ip);
        AdminUser update = new AdminUser();
        update.setId(user.getId());
        update.setLastLoginAt(LocalDateTime.now());
        adminUserMapper.updateById(update);

        String token = jwtService.issueAdminToken(user.getId(), user.getRoleCode(), scope,
                user.getInsuranceCompanyId(), user.getEnterpriseId(), user.getDisplayName());
        auditService.record(new CurrentUser(user.getId(), PrincipalType.ADMIN, user.getRoleCode(), scope,
                        user.getInsuranceCompanyId(), user.getEnterpriseId(), user.getDisplayName()),
                "ADMIN_LOGIN_SUCCESS", "admin_user", user.getId(), null, ip);

        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"))
                .plus(jwtService.ttl());
        return new AdminLoginResponse(token, expiresAt, user.getDisplayName(), user.getRoleCode(),
                scope.name(), user.getInsuranceCompanyId(), user.getEnterpriseId());
    }

    public AdminProfileResponse profile(CurrentUser current) {
        AdminUser user = adminUserMapper.selectById(current.id());
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号不存在或已停用");
        }
        return new AdminProfileResponse(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRoleCode(), current.scope().name(), user.getInsuranceCompanyId(), user.getEnterpriseId());
    }
}
