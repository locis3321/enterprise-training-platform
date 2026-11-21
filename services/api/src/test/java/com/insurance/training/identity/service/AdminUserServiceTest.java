package com.insurance.training.identity.service;

import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.identity.dto.AdminSelfPasswordRequest;
import com.insurance.training.identity.dto.AdminUserCreateRequest;
import com.insurance.training.identity.dto.AdminUserUpdateRequest;
import com.insurance.training.identity.entity.AdminUser;
import com.insurance.training.identity.entity.Role;
import com.insurance.training.identity.mapper.AdminUserMapper;
import com.insurance.training.identity.mapper.RoleMapper;
import com.insurance.training.tenancy.entity.Enterprise;
import com.insurance.training.tenancy.mapper.EnterpriseMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 账号管理的权限、自我保护与限流隔离。 */
class AdminUserServiceTest {

    private final AdminUserMapper adminUserMapper = mock(AdminUserMapper.class);
    private final RoleMapper roleMapper = mock(RoleMapper.class);
    private final EnterpriseMapper enterpriseMapper = mock(EnterpriseMapper.class);
    private final AuditService auditService = mock(AuditService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    private final AdminUserService service = new AdminUserService(
            adminUserMapper, roleMapper, enterpriseMapper, auditService,
            new AdminAccountThrottle(redis));

    private final CurrentUser platform = new CurrentUser(1L, PrincipalType.ADMIN, "SUPER_ADMIN",
            DataScopeType.ALL, null, null, "平台");
    private final CurrentUser groupAdmin = new CurrentUser(2L, PrincipalType.ADMIN, "INSURANCE_ADMIN",
            DataScopeType.INSURANCE_COMPANY, 10L, null, "集团");
    private final CurrentUser unitAdmin = new CurrentUser(3L, PrincipalType.ADMIN, "ENTERPRISE_ADMIN",
            DataScopeType.ENTERPRISE, 10L, 100L, "子公司");

    private AdminUser target(String role, Long company, Long enterprise) {
        AdminUser u = new AdminUser();
        u.setId(50L);
        u.setUsername("someone");
        u.setDisplayName("某人");
        u.setRoleCode(role);
        u.setInsuranceCompanyId(company);
        u.setEnterpriseId(enterprise);
        u.setStatus(1);
        u.setDeleted(0);
        return u;
    }

    /** 操作者自己的行：id 必须与 actor.id() 一致。 */
    private AdminUser selfRow(String role, Long company, Long enterprise) {
        AdminUser u = target(role, company, enterprise);
        u.setId(2L);
        return u;
    }

    private void roleExists(String code, String scope) {
        Role r = new Role();
        r.setCode(code);
        r.setDataScopeType(scope);
        when(roleMapper.selectOne(any())).thenReturn(r);
    }

    @Test
    @DisplayName("集团管理员不能管理他集团账号")
    void groupAdminCannotTouchOtherGroup() {
        when(adminUserMapper.selectById(50L)).thenReturn(target("ENTERPRISE_ADMIN", 20L, 200L));

        assertThatThrownBy(() -> service.detail(groupAdmin, 50L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("其他集团");
    }

    @Test
    @DisplayName("子公司管理员不能管理同集团其他子公司账号")
    void unitAdminCannotTouchSiblingUnit() {
        when(adminUserMapper.selectById(50L)).thenReturn(target("ENTERPRISE_ADMIN", 10L, 101L));

        assertThatThrownBy(() -> service.detail(unitAdmin, 50L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("本子公司");
    }

    @Test
    @DisplayName("不能删除自己")
    void cannotDeleteSelf() {
        when(adminUserMapper.selectById(2L)).thenReturn(selfRow("INSURANCE_ADMIN", 10L, null));

        assertThatThrownBy(() -> service.delete(groupAdmin, 2L, "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能删除自己");
        verify(adminUserMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("不能修改自己的角色与范围")
    void cannotChangeOwnRole() {
        when(adminUserMapper.selectById(2L)).thenReturn(selfRow("INSURANCE_ADMIN", 10L, null));

        assertThatThrownBy(() -> service.update(groupAdmin, 2L,
                new AdminUserUpdateRequest("改名", "SUPER_ADMIN", null, null, 1), "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能修改自己");
    }

    @Test
    @DisplayName("集团管理员不能创建平台级账号（范围提升）")
    void groupAdminCannotCreatePlatformAdmin() {
        roleExists("SUPER_ADMIN", "ALL");

        assertThatThrownBy(() -> service.create(groupAdmin,
                new AdminUserCreateRequest("newbie", "secret1", "新人", "SUPER_ADMIN", null, null), "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权创建该角色");
    }

    @Test
    @DisplayName("子公司管理员不能创建集团级账号")
    void unitAdminCannotCreateGroupAdmin() {
        roleExists("INSURANCE_ADMIN", "INSURANCE_COMPANY");

        assertThatThrownBy(() -> service.create(unitAdmin,
                new AdminUserCreateRequest("newbie", "secret1", "新人", "INSURANCE_ADMIN", 10L, null), "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能创建子公司管理员");
    }

    @Test
    @DisplayName("子公司归属与集团不匹配时 400，不产生跨集团孤儿账号")
    void enterpriseMustBelongToCompany() {
        roleExists("ENTERPRISE_ADMIN", "ENTERPRISE");
        Enterprise e = new Enterprise();
        e.setId(200L);
        e.setInsuranceCompanyId(20L);
        when(enterpriseMapper.selectById(200L)).thenReturn(e);

        assertThatThrownBy(() -> service.create(platform,
                new AdminUserCreateRequest("newbie", "secret1", "新人", "ENTERPRISE_ADMIN", 10L, 200L), "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于该集团");
        verify(adminUserMapper, never()).insert(any(AdminUser.class));
    }

    @Test
    @DisplayName("用户名重复时 409，含已删除账号")
    void duplicateUsername() {
        roleExists("INSURANCE_ADMIN", "INSURANCE_COMPANY");
        when(adminUserMapper.selectOne(any())).thenReturn(target("INSURANCE_ADMIN", 10L, null));

        assertThatThrownBy(() -> service.create(platform,
                new AdminUserCreateRequest("dup", "secret1", "重名", "INSURANCE_ADMIN", 10L, null), "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名已存在");
    }

    @Test
    @DisplayName("平台级账号不应绑定归属")
    void platformAdminMustNotHaveOwnership() {
        roleExists("SUPER_ADMIN", "ALL");

        assertThatThrownBy(() -> service.create(platform,
                new AdminUserCreateRequest("newbie", "secret1", "新人", "SUPER_ADMIN", 10L, null), "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不应绑定");
    }

    @Test
    @DisplayName("改密失败不污染登录失败计数（独立 key 空间）")
    void selfPasswordFailureDoesNotTouchLoginCounter() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(1L);

        AdminUser me = target("INSURANCE_ADMIN", 10L, null);
        me.setId(2L);
        me.setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10)
                .encode("correct-pwd"));
        when(adminUserMapper.selectById(2L)).thenReturn(me);

        assertThatThrownBy(() -> service.changeSelfPassword(groupAdmin,
                new AdminSelfPasswordRequest("wrong-pwd", "new-pwd-1"), "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("旧密码不正确");

        verify(ops).increment(org.mockito.ArgumentMatchers.startsWith("admin:selfpwd:"));
        verify(ops, never()).increment(org.mockito.ArgumentMatchers.startsWith("auth:fail:"));
    }

    @Test
    @DisplayName("改密成功清零自身失败计数")
    void selfPasswordSuccessResetsCounter() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);

        AdminUser me = target("INSURANCE_ADMIN", 10L, null);
        me.setId(2L);
        me.setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10)
                .encode("old-pwd-1"));
        when(adminUserMapper.selectById(2L)).thenReturn(me);
        Role r = new Role();
        r.setCode("INSURANCE_ADMIN");
        r.setDataScopeType("INSURANCE_COMPANY");
        when(roleMapper.selectOne(any())).thenReturn(r);

        service.changeSelfPassword(groupAdmin, new AdminSelfPasswordRequest("old-pwd-1", "new-pwd-1"), "1.1.1.1");

        verify(redis).delete(org.mockito.ArgumentMatchers.startsWith("admin:selfpwd:"));
        assertThat(me.getDisplayName()).isEqualTo("某人");
    }
}
