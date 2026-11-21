package com.insurance.training.identity.service;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.employee.mapper.EmployeeMapper;
import com.insurance.training.identity.entity.LearnerAccount;
import com.insurance.training.identity.mapper.LearnerAccountMapper;
import com.insurance.training.tenancy.mapper.EnterpriseMapper;
import com.insurance.training.common.security.JwtService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 学员账号：初始密码策略与手机号迁移。
 *
 * <p>覆盖代码审查 P2-5（改手机号未迁移账号）与 P2-6（首登强制改密）。
 */
class LearnerAccountServicePolicyTest {

    private final LearnerAccountMapper accountMapper = Mockito.mock(LearnerAccountMapper.class);
    private final EmployeeMapper employeeMapper = Mockito.mock(EmployeeMapper.class);
    private final EnterpriseMapper enterpriseMapper = Mockito.mock(EnterpriseMapper.class);
    private final JwtService jwtService = Mockito.mock(JwtService.class);
    private final LoginThrottle throttle = Mockito.mock(LoginThrottle.class);
    private final com.insurance.training.audit.service.AuditService auditService =
            Mockito.mock(com.insurance.training.audit.service.AuditService.class);

    private final LearnerAccountService service = new LearnerAccountService(
            accountMapper, employeeMapper, enterpriseMapper, jwtService, throttle, auditService);

    @BeforeAll
    static void initLambdaCache() {
        // 无 Spring 容器时，Lambda wrapper 需要手动初始化表信息缓存
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, LearnerAccount.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Employee.class);
    }

    @BeforeEach
    void resetMocks() {
        Mockito.reset(accountMapper, employeeMapper, enterpriseMapper);
        // findByPhone 默认查不到
        Mockito.when(accountMapper.selectOne(Mockito.any())).thenReturn(null);
    }

    @Test
    @DisplayName("初始密码 = anzhi + 手机号后 6 位")
    void initialPasswordRule() {
        assertThat(LearnerAccountService.initialPassword("13800138000")).isEqualTo("anzhi138000");
        assertThat(LearnerAccountService.initialPassword("13912345678")).isEqualTo("anzhi345678");
    }

    @Test
    @DisplayName("新建账号置 must_change_password=1（强制首登改密）")
    void newAccountRequiresPasswordChange() {
        service.ensureAccount("13800138000");

        ArgumentCaptor<LearnerAccount> captor = ArgumentCaptor.forClass(LearnerAccount.class);
        Mockito.verify(accountMapper).insert(captor.capture());
        assertThat(captor.getValue().getMustChangePassword()).isEqualTo(1);
    }

    @Test
    @DisplayName("改手机号且旧号仅此一个档案 → 账号手机号迁移，不新建")
    void migratesAccountWhenPhoneChanged() {
        LearnerAccount source = account(1L, "13800138000");
        Mockito.when(accountMapper.selectOne(Mockito.any()))
                .thenReturn(null)      // 新号无账号
                .thenReturn(source);   // 旧号查到
        Mockito.when(employeeMapper.selectList(Mockito.any())).thenReturn(List.of(emp("13800138000")));

        LearnerAccount result = service.migratePhone("13800138000", "13900139000");

        assertThat(result.getPhone()).isEqualTo("13900139000");
        // 不得新建账号（那样会出现两个账号登录同一档案）
        Mockito.verify(accountMapper, Mockito.never()).insert(Mockito.any(LearnerAccount.class));
    }

    @Test
    @DisplayName("旧号被多个企业档案共用 → 不整体改号，给新号建独立账号")
    void createsNewAccountWhenOldPhoneShared() {
        LearnerAccount source = account(1L, "13800138000");
        // 用 Answer 区分新旧号：ensureAccount 内部也会查账号，thenReturn 队列会被打乱
        Mockito.when(employeeMapper.selectList(Mockito.any()))
                .thenReturn(List.of(emp("13800138000"), emp("13800138000")));
        Mockito.when(accountMapper.selectOne(Mockito.any())).thenAnswer(inv -> {
            String sql = inv.getArgument(0).toString();
            // 旧号查询命中 source
            return sql.contains("13800138000") ? source : null;
        });

        service.migratePhone("13800138000", "13900139000");

        // 共享账号不能被整体改号，否则另一企业员工无法登录
        Mockito.verify(accountMapper, Mockito.never()).update(Mockito.any(), Mockito.any());
        Mockito.verify(accountMapper).insert(Mockito.any(LearnerAccount.class));
    }

    @Test
    @DisplayName("新号已有账号 → 不迁移、不覆盖其密码")
    void doesNotTouchExistingTargetAccount() {
        LearnerAccount target = account(2L, "13900139000");
        Mockito.when(accountMapper.selectOne(Mockito.any())).thenReturn(target);

        LearnerAccount result = service.migratePhone("13800138000", "13900139000");

        assertThat(result.getId()).isEqualTo(2L);
        Mockito.verify(accountMapper, Mockito.never()).update(Mockito.any(), Mockito.any());
        Mockito.verify(accountMapper, Mockito.never()).insert(Mockito.any(LearnerAccount.class));
    }

    @Test
    @DisplayName("重置密码后重新置 must_change_password=1")
    void resetSetsMustChangeAgain() {
        Mockito.when(accountMapper.selectOne(Mockito.any())).thenReturn(account(1L, "13800138000"));

        service.resetToInitial("13800138000");

        // 更新语句里必须带 must_change_password=1（用 update(null, wrapper) 形式）
        Mockito.verify(accountMapper).update(Mockito.isNull(), Mockito.any());
    }

    @Test
    @DisplayName("重置不存在的账号 → 409（不静默补建）")
    void resetMissingAccountFails() {
        assertThatThrownBy(() -> service.resetToInitial("13800138000"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ---- fixtures ----

    private static LearnerAccount account(long id, String phone) {
        LearnerAccount a = new LearnerAccount();
        a.setId(id);
        a.setPhone(phone);
        a.setStatus(1);
        a.setMustChangePassword(1);
        return a;
    }

    private static Employee emp(String phone) {
        Employee e = new Employee();
        e.setId(1L);
        e.setPhone(phone);
        e.setStatus(1);
        return e;
    }
}
