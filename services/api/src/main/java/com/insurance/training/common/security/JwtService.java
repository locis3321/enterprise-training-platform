package com.insurance.training.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 签发与校验。
 *
 * <p>密钥只来自环境变量 {@code APP_JWT_SECRET}，缺失或长度不足直接启动失败（原则：显式失败）。
 * claims 采用平铺字符串，避免不同端自行解析嵌套结构。
 */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    static final String CLAIM_TYPE = "typ";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_SCOPE = "scope";
    static final String CLAIM_COMPANY = "companyId";
    static final String CLAIM_ENTERPRISE = "enterpriseId";
    static final String CLAIM_NAME = "name";

    private final String secret;
    private final Duration ttl;
    private SecretKey key;

    public JwtService(
            @Value("${app.jwt.secret:}") String secret,
            @Value("${app.jwt.ttl:PT168H}") Duration ttl) {
        this.secret = secret;
        this.ttl = ttl;
    }

    @PostConstruct
    void init() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "APP_JWT_SECRET 未配置或长度不足 32 字节，拒绝启动。请在环境变量中提供随机密钥（不要提交到仓库）。");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        log.info("JWT 配置就绪，token 有效期 {}", ttl);
    }

    public String issueAdminToken(long adminId, String roleCode, DataScopeType scope,
                                  Long companyId, Long enterpriseId, String displayName) {
        return issue(adminId, PrincipalType.ADMIN, roleCode, scope, companyId, enterpriseId, displayName);
    }

    public String issueEmployeeToken(long employeeId, Long companyId, Long enterpriseId, String displayName) {
        return issue(employeeId, PrincipalType.EMPLOYEE, null, DataScopeType.ENTERPRISE,
                companyId, enterpriseId, displayName);
    }

    /**
     * 学员账号令牌。
     *
     * <p>主体 id 是**学员账号**（手机号唯一），不是某条员工档案：一个账号可对应多条
     * 企业员工记录。{@code companyId}/{@code enterpriseId} 不再写入——学员的数据范围
     * 由其名下员工集合决定，写入单个企业会误导调用方。
     */
    public String issueLearnerToken(long accountId, String displayName) {
        return issue(accountId, PrincipalType.EMPLOYEE, null, DataScopeType.ENTERPRISE,
                null, null, displayName);
    }

    private String issue(long id, PrincipalType type, String roleCode, DataScopeType scope,
                         Long companyId, Long enterpriseId, String displayName) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(Long.toString(id))
                .claim(CLAIM_TYPE, type.name())
                .claim(CLAIM_SCOPE, scope.name())
                .claim(CLAIM_NAME, displayName)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key);
        if (roleCode != null) {
            builder.claim(CLAIM_ROLE, roleCode);
        }
        if (companyId != null) {
            builder.claim(CLAIM_COMPANY, companyId);
        }
        if (enterpriseId != null) {
            builder.claim(CLAIM_ENTERPRISE, enterpriseId);
        }
        return builder.compact();
    }

    /** 校验并解析 token；任何非法、过期、签名不符都返回 empty，交由调用方返回 401。 */
    public Optional<CurrentUser> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            PrincipalType type = PrincipalType.valueOf(claims.get(CLAIM_TYPE, String.class));
            DataScopeType scope = DataScopeType.valueOf(claims.get(CLAIM_SCOPE, String.class));
            Number company = claims.get(CLAIM_COMPANY, Number.class);
            Number enterprise = claims.get(CLAIM_ENTERPRISE, Number.class);
            return Optional.of(new CurrentUser(
                    Long.parseLong(claims.getSubject()),
                    type,
                    claims.get(CLAIM_ROLE, String.class),
                    scope,
                    company == null ? null : company.longValue(),
                    enterprise == null ? null : enterprise.longValue(),
                    claims.get(CLAIM_NAME, String.class)));
        }
        catch (JwtException | IllegalArgumentException ex) {
            log.debug("token 校验失败: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public Duration ttl() {
        return ttl;
    }
}
