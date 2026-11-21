package com.insurance.training.identity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.JwtService;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.employee.mapper.EmployeeMapper;
import com.insurance.training.employee.vo.EmployeeVo;
import com.insurance.training.identity.entity.LearnerAccount;
import com.insurance.training.identity.mapper.LearnerAccountMapper;
import com.insurance.training.tenancy.entity.Enterprise;
import com.insurance.training.tenancy.mapper.EnterpriseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 学员账号：手机号+密码登录的凭证主体。
 *
 * <p>安全边界：
 * <ol>
 *   <li>手机号是**账号标识**，凭证是服务端 BCrypt 校验的密码（AGENTS.md 已区分两条链路）；</li>
 *   <li>初始密码规则 {@code anzhi + 手机号后6位} 只在账号首次创建时写入，
 *       **重复导入不覆盖**已有密码（否则学员自行改的密码会被企业再次导入冲掉）；</li>
 *   <li>登录失败走与管理员一致的 {@link LoginThrottle}，不区分「账号不存在」与「密码错误」；</li>
 *   <li>密码与哈希一律不写日志、不返回响应。</li>
 * </ol>
 */
@Service
public class LearnerAccountService {

    private static final Logger log = LoggerFactory.getLogger(LearnerAccountService.class);

    /** 初始密码前缀（用户指定策略）；完整规则：前缀 + 手机号后 6 位。 */
    private static final String INITIAL_PASSWORD_PREFIX = "anzhi";
    private static final int MIN_PASSWORD_LENGTH = 6;

    private final LearnerAccountMapper accountMapper;
    private final EmployeeMapper employeeMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final JwtService jwtService;
    private final LoginThrottle throttle;
    private final AuditService auditService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    public LearnerAccountService(LearnerAccountMapper accountMapper, EmployeeMapper employeeMapper,
                                 EnterpriseMapper enterpriseMapper, JwtService jwtService,
                                 LoginThrottle throttle, AuditService auditService) {
        this.accountMapper = accountMapper;
        this.employeeMapper = employeeMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.jwtService = jwtService;
        this.throttle = throttle;
        this.auditService = auditService;
    }

    /** 登录结果：账号 + 其名下启用员工（由调用方组装 LearnerContext）。 */
    public record LoginResult(LearnerAccount account, List<Employee> employees, String token, String displayName) {
    }

    /** 初始密码：{@code anzhi} + 手机号后 6 位。手机号不足 6 位时用整串（导入已校验 11 位，正常不会触发）。 */
    public static String initialPassword(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "手机号为空，无法生成初始密码");
        }
        String tail = phone.length() <= 6 ? phone : phone.substring(phone.length() - 6);
        return INITIAL_PASSWORD_PREFIX + tail;
    }

    /**
     * 确保该手机号存在账号；仅在不存在时创建并写入初始密码。
     *
     * <p>并发导入同一手机号到两个企业时，唯一键冲突视为「另一请求已创建」，直接复用，
     * 不覆盖密码、不报错。
     *
     * @return true 表示本次创建了账号（供审计与调用方判断），false 表示已存在
     */
    @Transactional
    public boolean ensureAccount(String phone) {
        LearnerAccount existing = findByPhone(phone);
        if (existing != null) {
            return false;
        }
        LearnerAccount entity = new LearnerAccount();
        entity.setPhone(phone);
        entity.setPasswordHash(encoder.encode(initialPassword(phone)));
        entity.setStatus(1);
        // 初始密码可由手机号推导，必须强制首次登录改密
        entity.setMustChangePassword(1);
        try {
            accountMapper.insert(entity);
            log.info("已创建学员账号 accountId={}（手机号掩码 {}）", entity.getId(), EmployeeVo.maskPhone(phone));
            auditService.record(null, "LEARNER_ACCOUNT_CREATED", "learner_account", entity.getId(),
                    "phone=" + EmployeeVo.maskPhone(phone) + ",mustChangePassword=1", null);
            return true;
        }
        catch (DuplicateKeyException ex) {
            // 并发创建：唯一键兜底，保留先写入的那条（含其密码），不覆盖
            log.info("学员账号已由并发请求创建，保留既有密码（手机号掩码 {}）", EmployeeVo.maskPhone(phone));
            return false;
        }
    }

    /**
     * 重置为初始密码（管理端动作）。
     *
     * <p>作用于按手机号唯一的账号，会影响该学员在**所有企业**的登录；调用方必须把
     * {@link #affectedEnterpriseCount(String)} 一并告知操作者。
     */
    @Transactional
    public void resetToInitial(String phone) {
        LearnerAccount account = findByPhone(phone);
        if (account == null) {
            // 账号缺失但员工存在属数据不一致：显式失败，不静默补建（补建会掩盖缺失原因）
            throw new BizException(ErrorCode.CONFLICT,
                    "该员工手机号尚未开通学员账号，请先通过导入或重建员工档案开通");
        }
        accountMapper.update(null, Wrappers.<LearnerAccount>lambdaUpdate()
                .set(LearnerAccount::getPasswordHash, encoder.encode(initialPassword(phone)))
                // 重置回初始密码 → 重新要求强制改密
                .set(LearnerAccount::getMustChangePassword, 1)
                .set(LearnerAccount::getUpdatedAt, LocalDateTime.now())
                .eq(LearnerAccount::getId, account.getId()));
        auditService.record(null, "LEARNER_PASSWORD_RESET", "learner_account", account.getId(),
                "phone=" + EmployeeVo.maskPhone(phone) + ",mustChangePassword=1", null);
    }

    /**
     * 员工手机号变更后迁移其账号。
     *
     * <p>为什么必须迁移：账号按手机号唯一。若只改 employee.phone，
     * 旧账号仍绑旧号码、新号码又建一个账号，结果是两个人分别登录同一员工档案。
     *
     * <p>迁移规则（保守，避免误并两个真人）：
     * <ul>
     *   <li>新号码**已有账号** → 不迁移，直接返回该账号（视为该真人已用新号登录过）；</li>
     *   <li>旧号码账号**还有其他员工共享** → 说明该账号代表多个企业档案，
     *       不能整体改号（会破坏其他人的登录），改为给新号码建独立账号；</li>
     *   <li>否则 → 把旧账号的手机号改为新号码，保留原密码与 must_change_password。</li>
     * </ul>
     *
     * @return 迁移或新建后的目标账号
     */
    @Transactional
    public LearnerAccount migratePhone(String oldPhone, String newPhone) {
        if (oldPhone == null || oldPhone.equals(newPhone)) {
            return findByPhone(newPhone);
        }
        LearnerAccount target = findByPhone(newPhone);
        if (target != null) {
            // 新号已有账号：不改动，避免覆盖其密码
            return target;
        }
        LearnerAccount source = findByPhone(oldPhone);
        if (source == null) {
            ensureAccount(newPhone);
            return findByPhone(newPhone);
        }
        long sharedEmployees = employeeMapper.selectList(Wrappers.<Employee>lambdaQuery()
                        .eq(Employee::getPhone, oldPhone)).size();
        if (sharedEmployees > 1) {
            // 旧号被多个企业档案共用：不能整体改号，给新号单独建账号
            ensureAccount(newPhone);
            return findByPhone(newPhone);
        }
        accountMapper.update(null, Wrappers.<LearnerAccount>lambdaUpdate()
                .set(LearnerAccount::getPhone, newPhone)
                .set(LearnerAccount::getUpdatedAt, LocalDateTime.now())
                .eq(LearnerAccount::getId, source.getId()));
        source.setPhone(newPhone);
        log.info("学员账号手机号已迁移 accountId={} from={} to={}",
                source.getId(), EmployeeVo.maskPhone(oldPhone), EmployeeVo.maskPhone(newPhone));
        auditService.record(null, "LEARNER_ACCOUNT_PHONE_MIGRATED", "learner_account", source.getId(),
                "from=" + EmployeeVo.maskPhone(oldPhone) + ",to=" + EmployeeVo.maskPhone(newPhone), null);
        return source;
    }

    /** 该手机号关联的**启用**员工所属企业数：重置密码的影响面。 */
    public long affectedEnterpriseCount(String phone) {
        return employeeMapper.selectList(Wrappers.<Employee>lambdaQuery()
                        .eq(Employee::getPhone, phone)
                        .eq(Employee::getStatus, 1))
                .stream().map(Employee::getEnterpriseId).distinct().count();
    }

    /**
     * 手机号+密码登录。
     *
     * <p>账号不存在与密码错误返回同一提示，防账号枚举；成功后校验该账号名下是否还有
     * 可用员工（员工与企业均启用），全部不可用则 403（账号本身可用但无培训权限）。
     */
    @Transactional
    public LoginResult login(String phone, String password, String ip) {
        String normalized = phone == null ? "" : phone.trim();
        throttle.assertAllowed(normalized, ip);

        LearnerAccount account = findByPhone(normalized);
        boolean matched = account != null && encoder.matches(password == null ? "" : password,
                account.getPasswordHash());
        if (!matched) {
            throttle.onFailure(normalized, ip);
            auditService.record(null, "LEARNER_LOGIN_FAILED", "learner_account",
                    account == null ? null : account.getId(),
                    "phone=" + EmployeeVo.maskPhone(normalized), ip);
            throw new BizException(ErrorCode.UNAUTHORIZED, "手机号或密码不正确");
        }
        if (!Integer.valueOf(1).equals(account.getStatus())) {
            auditService.record(null, "LEARNER_LOGIN_DISABLED", "learner_account", account.getId(),
                    "phone=" + EmployeeVo.maskPhone(normalized), ip);
            throw new BizException(ErrorCode.FORBIDDEN, "账号已停用，请联系管理员");
        }

        List<Employee> employees = usableEmployees(normalized);
        if (employees.isEmpty()) {
            throw new BizException(ErrorCode.FORBIDDEN, "该手机号下没有可用的员工档案，请联系企业管理员");
        }

        throttle.onSuccess(normalized, ip);
        accountMapper.update(null, Wrappers.<LearnerAccount>lambdaUpdate()
                .set(LearnerAccount::getLastLoginAt, LocalDateTime.now())
                .eq(LearnerAccount::getId, account.getId()));

        Employee primary = employees.get(0);
        String token = jwtService.issueLearnerToken(account.getId(), primary.getName());
        auditService.record(null, "LEARNER_LOGIN", "learner_account", account.getId(),
                "enterprises=" + employees.stream().map(Employee::getEnterpriseId).distinct().count(), ip);
        return new LoginResult(account, employees, token, primary.getName());
    }

    /** 学员自行修改密码：必须校验旧密码；新密码长度与旧密码不同才接受由调用方保证。 */
    @Transactional
    public void changePassword(long accountId, String oldPassword, String newPassword, String ip) {
        LearnerAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "账号不存在");
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (!encoder.matches(oldPassword == null ? "" : oldPassword, account.getPasswordHash())) {
            auditService.record(null, "LEARNER_PASSWORD_CHANGE_FAILED", "learner_account", accountId,
                    "reason=old-password-mismatch", ip);
            throw new BizException(ErrorCode.BAD_REQUEST, "原密码不正确");
        }
        accountMapper.update(null, Wrappers.<LearnerAccount>lambdaUpdate()
                .set(LearnerAccount::getPasswordHash, encoder.encode(newPassword))
                .set(LearnerAccount::getMustChangePassword, 0)
                .set(LearnerAccount::getUpdatedAt, LocalDateTime.now())
                .eq(LearnerAccount::getId, accountId));
        auditService.record(null, "LEARNER_PASSWORD_CHANGED", "learner_account", accountId, null, ip);
    }

    /** 是否需要强制改密（首次登录/被重置后）。供 profile 下发，前端据此强制弹改密。 */
    public boolean mustChangePassword(long accountId) {
        LearnerAccount account = accountMapper.selectById(accountId);
        return account != null && Integer.valueOf(1).equals(account.getMustChangePassword());
    }

    public LearnerAccount findByPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        return accountMapper.selectOne(Wrappers.<LearnerAccount>lambdaQuery()
                .eq(LearnerAccount::getPhone, phone));
    }

    /**
     * 该手机号下「员工启用 + 企业启用」的档案，按 id 升序（primary 取第一条，保证稳定）。
     *
     * <p>企业在停用时，其员工即使 status=1 也不可用——与 {@code LearnerGuard} 既有语义一致。
     */
    public List<Employee> usableEmployees(String phone) {
        List<Employee> all = employeeMapper.selectList(Wrappers.<Employee>lambdaQuery()
                .eq(Employee::getPhone, phone)
                .eq(Employee::getStatus, 1));
        if (all.isEmpty()) {
            return List.of();
        }
        List<Long> enterpriseIds = all.stream().map(Employee::getEnterpriseId).distinct().toList();
        java.util.Set<Long> activeEnterprises = enterpriseMapper.selectList(
                        Wrappers.<Enterprise>lambdaQuery().in(Enterprise::getId, enterpriseIds))
                .stream().filter(e -> Integer.valueOf(1).equals(e.getStatus()))
                .map(Enterprise::getId).collect(java.util.stream.Collectors.toSet());
        return all.stream()
                .filter(e -> activeEnterprises.contains(e.getEnterpriseId()))
                .sorted(Comparator.comparing(Employee::getId))
                .toList();
    }
}
