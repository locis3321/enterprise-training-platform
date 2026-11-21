package com.insurance.training.identity.wechat;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.BindTicket;
import com.insurance.training.common.security.JwtService;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.employee.mapper.EmployeeMapper;
import com.insurance.training.employee.vo.EmployeeVo;
import com.insurance.training.identity.entity.WechatIdentity;
import com.insurance.training.identity.entity.LearnerAccount;
import com.insurance.training.identity.mapper.WechatIdentityMapper;
import com.insurance.training.tenancy.entity.Enterprise;
import com.insurance.training.tenancy.mapper.EnterpriseMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 小程序微信登录与手机号绑定。
 *
 * <p>安全边界：
 * <ol>
 *   <li>openid 只能由服务端用 code 换取；</li>
 *   <li>手机号只能由服务端用 phoneCode 换取，客户端传入的任何手机号字段都不参与判定；</li>
 *   <li>手机号在企业范围内匹配员工：0 条或多条一律拒绝并转人工，不自动挑选；</li>
 *   <li>绑定关系由唯一键兜底，并发重复绑定会失败而不是产生双份数据。</li>
 * </ol>
 */
@Service
public class WechatAuthService {

    private final WechatClient wechatClient;
    private final WechatIdentityMapper identityMapper;
    private final EmployeeMapper employeeMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final JwtService jwtService;
    private final StringRedisTemplate redis;
    private final AuditService auditService;
    private final com.insurance.training.identity.service.LearnerAccountService learnerAccountService;
    private final com.insurance.training.system.service.SystemConfigService config;
    private final Duration ticketTtl;

    public WechatAuthService(WechatClient wechatClient, WechatIdentityMapper identityMapper,
                             EmployeeMapper employeeMapper, EnterpriseMapper enterpriseMapper,
                             JwtService jwtService, StringRedisTemplate redis, AuditService auditService,
                             com.insurance.training.identity.service.LearnerAccountService learnerAccountService,
                             com.insurance.training.system.service.SystemConfigService config,
                             @Value("${app.wechat.bind-ticket-ttl:PT5M}") Duration ticketTtl) {
        this.wechatClient = wechatClient;
        this.identityMapper = identityMapper;
        this.employeeMapper = employeeMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.jwtService = jwtService;
        this.redis = redis;
        this.auditService = auditService;
        this.learnerAccountService = learnerAccountService;
        this.config = config;
        this.ticketTtl = ticketTtl;
    }

    /** 登录结果：已绑定直接返回 token，否则返回短期票据用于手机号绑定。 */
    public record LoginResult(boolean bound, String token, String bindTicket, String displayName) {
    }

    public record BindResult(String token, String displayName, String enterpriseName) {
    }

    @Transactional
    public LoginResult login(String code) {
        // 流程内只解析一次，避免中途刷新导致前后 appid 不一致而写出错误绑定关系
        String appid = currentAppid();
        WechatClient.Session session = wechatClient.code2Session(code);
        WechatIdentity identity = identityMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<WechatIdentity>lambdaQuery()
                        .eq(WechatIdentity::getAppid, appid)
                        .eq(WechatIdentity::getOpenid, session.openid())
                        .eq(WechatIdentity::getStatus, 1));

        if (identity == null) {
            BindTicket ticket = BindTicket.random();
            redis.opsForValue().set(ticket.redisKey(), session.openid(), ticketTtl);
            return new LoginResult(false, null, ticket.value(), null);
        }

        Employee employee = employeeMapper.selectById(identity.getEmployeeId());
        assertEmployeeActive(employee);
        String token = issueToken(employee);
        return new LoginResult(true, token, null, employee.getName());
    }

    @Transactional
    public BindResult bind(String bindTicketValue, String phoneCode, String ip) {
        String appid = currentAppid();
        String ticketKey = BindTicket.redisKeyOf(bindTicketValue);
        String openid = redis.opsForValue().get(ticketKey);
        if (openid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "绑定会话已过期，请重新登录");
        }

        String phone = wechatClient.getPhoneNumber(phoneCode);

        List<Employee> matched = employeeMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Employee>lambdaQuery()
                        .eq(Employee::getPhone, phone));
        if (matched.isEmpty()) {
            auditService.record(null, "WECHAT_BIND_NO_EMPLOYEE", "employee", null,
                    "phone=" + EmployeeVo.maskPhone(phone), ip);
            throw new BizException(ErrorCode.CONFLICT, "该手机号未导入员工档案，请联系所属企业管理员");
        }
        if (matched.size() > 1) {
            auditService.record(null, "WECHAT_BIND_MULTI_MATCH", "employee", null,
                    "phone=" + EmployeeVo.maskPhone(phone) + ",count=" + matched.size(), ip);
            throw new BizException(ErrorCode.CONFLICT, "该手机号在多个企业存在档案，已转人工核查，请联系管理员");
        }

        Employee employee = matched.get(0);
        assertEmployeeActive(employee);
        Enterprise enterprise = enterpriseMapper.selectById(employee.getEnterpriseId());
        if (enterprise == null || !Integer.valueOf(1).equals(enterprise.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "所属企业已停用，请联系管理员");
        }

        WechatIdentity boundToOther = identityMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<WechatIdentity>lambdaQuery()
                        .eq(WechatIdentity::getAppid, appid)
                        .eq(WechatIdentity::getOpenid, openid)
                        .ne(WechatIdentity::getEmployeeId, employee.getId()));
        if (boundToOther != null) {
            throw new BizException(ErrorCode.CONFLICT, "当前微信已绑定其他员工，请联系管理员处理");
        }

        WechatIdentity boundOtherOpenid = identityMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<WechatIdentity>lambdaQuery()
                        .eq(WechatIdentity::getAppid, appid)
                        .eq(WechatIdentity::getEmployeeId, employee.getId())
                        .ne(WechatIdentity::getOpenid, openid));
        if (boundOtherOpenid != null) {
            throw new BizException(ErrorCode.CONFLICT, "该员工已绑定其他微信，请联系管理员处理");
        }

        WechatIdentity identity = new WechatIdentity();
        identity.setAppid(appid);
        identity.setOpenid(openid);
        identity.setEmployeeId(employee.getId());
        identity.setPhone(phone);
        identity.setStatus(1);
        identity.setBoundAt(LocalDateTime.now());
        try {
            identityMapper.insert(identity);
        }
        catch (DuplicateKeyException ex) {
            // 并发绑定：唯一键兜底，返回冲突而不是产生第二条绑定
            throw new BizException(ErrorCode.CONFLICT, "绑定冲突，请重试或联系管理员");
        }

        redis.delete(ticketKey);
        auditService.record(null, "WECHAT_BOUND", "employee", employee.getId(),
                "enterpriseId=" + employee.getEnterpriseId(), ip);

        return new BindResult(issueToken(employee), employee.getName(), enterprise.getName());
    }

    /** 当前小程序 AppID。每次流程开头解析一次，未配置时显式失败。 */
    private String currentAppid() {
        String appid = config.getString(com.insurance.training.system.config.SystemConfigKey.WECHAT_APPID);
        if (appid == null || appid.isBlank()) {
            throw new com.insurance.training.common.exception.BizException(
                    com.insurance.training.common.response.ErrorCode.CONFLICT,
                    "小程序 AppID 未配置，请先在系统设置中设置");
        }
        return appid;
    }

    private void assertEmployeeActive(Employee employee) {
        if (employee == null || !Integer.valueOf(1).equals(employee.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "员工档案已停用，无法参加培训");
        }
    }

    /**
     * 签发学员令牌。
     *
     * <p>账号化后令牌主体是**学员账号**（手机号唯一），不是某条员工档案。
     * 微信绑定仍要求手机号唯一命中员工（AGENTS.md 既有规则），绑定成功后
     * 按该员工手机号确保账号存在并签发账号令牌。
     */
    private String issueToken(Employee employee) {
        learnerAccountService.ensureAccount(employee.getPhone());
        LearnerAccount account = learnerAccountService.findByPhone(employee.getPhone());
        if (account == null) {
            // ensureAccount 刚执行过，仍取不到说明并发或数据异常，显式失败而不是签发无效令牌
            throw new BizException(ErrorCode.INTERNAL_ERROR, "学员账号初始化失败，请稍后重试");
        }
        return jwtService.issueLearnerToken(account.getId(), employee.getName());
    }
}
