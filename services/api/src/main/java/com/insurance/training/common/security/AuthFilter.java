package com.insurance.training.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.identity.entity.AdminUser;
import com.insurance.training.identity.mapper.AdminUserMapper;
import com.insurance.training.identity.mapper.LearnerAccountMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bearer token 认证过滤器。
 *
 * <p>职责边界：只做「解析 → 写入请求上下文 → 拦截未认证请求」。
 * 业务授权（角色、数据范围）由 Service 层的 {@link DataScopeGuard} 判定，二者不混用。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    /** 无需认证即可访问的路径（前缀匹配）。 */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/v1/ping",
            "/actuator/",
            "/v3/api-docs",
            "/swagger-ui",
            "/error"
    );

    /** 管理端白名单（登录接口本身不能要求 token）。 */
    private static final List<String> ADMIN_PUBLIC = List.of("/api/v1/admin/auth/login");

    /** 小程序白名单（未绑定用户需要用 bindTicket 完成绑定；密码登录本身不需要 token）。 */
    private static final List<String> MINIAPP_PUBLIC = List.of(
            "/api/v1/miniapp/auth/wechat-login",
            "/api/v1/miniapp/auth/phone-bind",
            "/api/v1/miniapp/auth/password-login",
            // 隐私协议在登录前也必须可查看，否则用户无法在同意前了解条款
            "/api/v1/miniapp/legal/privacy-policy"
    );

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final AdminUserMapper adminUserMapper;
    private final LearnerAccountMapper learnerAccountMapper;

    /**
     * 管理员状态短缓存（最长 30 秒）。
     *
     * <p>为什么需要回查：JWT 有效期 168 小时。若管理员被停用或降权，旧 token
     * 在到期前仍全权有效——这是真实的越权窗口。每请求查库代价可接受但浪费，
     * 因此加 30 秒缓存：停用后最迟 30 秒生效，且不引入 Redis 依赖。
     */
    private final ConcurrentHashMap<Long, CacheEntry> adminStatusCache = new ConcurrentHashMap<>();
    private static final long ADMIN_STATUS_CACHE_MS = 30_000L;

    private record CacheEntry(boolean active, long checkedAt) {
    }

    public AuthFilter(JwtService jwtService, ObjectMapper objectMapper, AdminUserMapper adminUserMapper,
                      LearnerAccountMapper learnerAccountMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.adminUserMapper = adminUserMapper;
        this.learnerAccountMapper = learnerAccountMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }

        boolean adminSurface = path.startsWith("/api/v1/admin/");
        boolean miniappSurface = path.startsWith("/api/v1/miniapp/");
        if (!adminSurface && !miniappSurface) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeError(response, ErrorCode.UNAUTHORIZED);
            return;
        }

        var user = jwtService.parse(header.substring(7).trim()).orElse(null);
        if (user == null) {
            writeError(response, ErrorCode.UNAUTHORIZED);
            return;
        }
        // 管理端只接受管理主体，小程序只接受员工主体，避免跨端复用 token
        if ((adminSurface && !user.isAdmin()) || (miniappSurface && !user.isEmployee())) {
            writeError(response, ErrorCode.FORBIDDEN);
            return;
        }
        // 管理端额外校验账号仍然启用：token 有效期长，停用/降权必须尽快生效
        if (adminSurface && !isAdminStillActive(user.id())) {
            writeError(response, ErrorCode.UNAUTHORIZED);
            return;
        }
        // 学员必须先改掉初始密码，才能使用其他功能（P2-6）
        if (miniappSurface && isPasswordChangeRequired(user.id()) && !isPasswordChangeAllowed(path)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "请先修改初始密码后再使用其他功能");
            return;
        }

        CurrentUserHolder.set(user);
        chain.doFilter(request, response);
    }

    /** 强制改密期间仍可访问的接口（否则学员无法完成改密）。 */
    private static final List<String> PASSWORD_CHANGE_ALLOWED = List.of(
            "/api/v1/miniapp/auth/password",
            "/api/v1/miniapp/user/profile"
    );

    private boolean isPasswordChangeAllowed(String path) {
        return PASSWORD_CHANGE_ALLOWED.contains(path);
    }

    /**
     * 该学员是否处于「必须先改密」状态。
     *
     * <p>集中在此判定而不是各接口内部：默认拒绝，新增接口天然受保护，
     * 不会因为忘记加校验而被绕过。
     */
    private boolean isPasswordChangeRequired(long accountId) {
        var account = learnerAccountMapper.selectById(accountId);
        return account != null && Integer.valueOf(1).equals(account.getMustChangePassword());
    }

    /** 管理员是否仍然启用（30 秒缓存，避免每请求查库）。 */
    private boolean isAdminStillActive(long adminId) {
        long now = System.currentTimeMillis();
        CacheEntry cached = adminStatusCache.get(adminId);
        if (cached != null && now - cached.checkedAt() < ADMIN_STATUS_CACHE_MS) {
            return cached.active();
        }
        AdminUser admin = adminUserMapper.selectById(adminId);
        boolean active = admin != null && Integer.valueOf(1).equals(admin.getStatus());
        adminStatusCache.put(adminId, new CacheEntry(active, now));
        return active;
    }

    private boolean isPublic(String path) {
        return PUBLIC_PREFIXES.stream().anyMatch(path::startsWith)
                || ADMIN_PUBLIC.contains(path)
                || MINIAPP_PUBLIC.contains(path);
    }

    private void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
        writeError(response, code, code.getDefaultMessage());
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(code.getCode(), message)));
    }
}
