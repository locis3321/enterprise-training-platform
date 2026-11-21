package com.insurance.training.identity.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.employee.mapper.EmployeeMapper;
import com.insurance.training.employee.vo.EmployeeVo;
import com.insurance.training.identity.service.LearnerAccountService;
import com.insurance.training.identity.wechat.WechatAuthService;
import com.insurance.training.identity.learner.LearnerContext;
import com.insurance.training.identity.learner.LearnerGuard;
import com.insurance.training.tenancy.entity.Enterprise;
import com.insurance.training.tenancy.mapper.EnterpriseMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 小程序认证与个人档案接口。 */
@Tag(name = "小程序-认证")
@RestController
@RequestMapping("/api/v1/miniapp")
public class MiniappAuthController {

    private final WechatAuthService wechatAuthService;
    private final LearnerAccountService accountService;
    private final LearnerGuard learnerGuard;
    private final EmployeeMapper employeeMapper;
    private final EnterpriseMapper enterpriseMapper;

    public MiniappAuthController(WechatAuthService wechatAuthService, LearnerAccountService accountService,
                                 LearnerGuard learnerGuard, EmployeeMapper employeeMapper,
                                 EnterpriseMapper enterpriseMapper) {
        this.wechatAuthService = wechatAuthService;
        this.accountService = accountService;
        this.learnerGuard = learnerGuard;
        this.employeeMapper = employeeMapper;
        this.enterpriseMapper = enterpriseMapper;
    }

    public record WechatLoginRequest(@NotBlank(message = "缺少登录 code") String code) {
    }

    public record PhoneBindRequest(
            @NotBlank(message = "缺少绑定票据") String bindTicket,
            @NotBlank(message = "缺少手机号授权 code") String phoneCode) {
    }

    /**
     * 手机号+密码登录。
     *
     * <p>手机号是账号标识，凭证是服务端 BCrypt 校验的密码。
     * 该链路与微信链路并存，由用户在登录页自选（不判定小程序主体类型）。
     */
    public record PasswordLoginRequest(
            @NotBlank(message = "请输入手机号")
            @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,
            @NotBlank(message = "请输入密码") String password) {
    }

    public record PasswordChangeRequest(
            @NotBlank(message = "请输入原密码") String oldPassword,
            @NotBlank(message = "请输入新密码")
            @Size(min = 6, max = 64, message = "新密码长度需在 6-64 位之间") String newPassword) {
    }

    /** 登录响应：与微信链路保持同名 token 字段，前端可复用同一套 token 写入逻辑。 */
    public record PasswordLoginResponse(String token, String displayName, long enterpriseCount) {
    }

    @Operation(summary = "微信登录（code 换 openid）")
    @PostMapping("/auth/wechat-login")
    public ApiResponse<WechatAuthService.LoginResult> login(@Valid @RequestBody WechatLoginRequest request) {
        return ApiResponse.ok(wechatAuthService.login(request.code()));
    }

    @Operation(summary = "手机号绑定员工档案")
    @PostMapping("/auth/phone-bind")
    public ApiResponse<WechatAuthService.BindResult> bind(@Valid @RequestBody PhoneBindRequest request,
                                                          HttpServletRequest http) {
        return ApiResponse.ok(wechatAuthService.bind(request.bindTicket(), request.phoneCode(),
                CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "手机号+密码登录")
    @PostMapping("/auth/password-login")
    public ApiResponse<PasswordLoginResponse> passwordLogin(@Valid @RequestBody PasswordLoginRequest request,
                                                            HttpServletRequest http) {
        LearnerAccountService.LoginResult result = accountService.login(request.phone(), request.password(),
                CurrentUserHolder.clientIp(http));
        long enterpriseCount = result.employees().stream().map(Employee::getEnterpriseId).distinct().count();
        return ApiResponse.ok(new PasswordLoginResponse(result.token(), result.displayName(), enterpriseCount));
    }

    @Operation(summary = "修改密码（需登录）")
    @PostMapping("/auth/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request,
                                            HttpServletRequest http) {
        CurrentUser current = CurrentUserHolder.require();
        accountService.changePassword(current.id(), request.oldPassword(), request.newPassword(),
                CurrentUserHolder.clientIp(http));
        return ApiResponse.ok(null);
    }

    /**
     * 个人档案。
     *
     * <p>账号化后一个学员可能属于多个企业：{@code primary} 是主展示档案（企业名、脱敏手机号），
     * {@code enterpriseCount} 供前端说明「你属于 N 家企业」。
     */
    public record ProfileResponse(long accountId, String name, String phoneMasked,
                                  long enterpriseId, String enterpriseName, int status,
                                  long enterpriseCount, List<EnterpriseBrief> enterprises,
                                  boolean mustChangePassword) {
    }

    public record EnterpriseBrief(long enterpriseId, String enterpriseName) {
    }

    @Operation(summary = "当前学员档案")
    @GetMapping("/user/profile")
    public ApiResponse<ProfileResponse> profile() {
        CurrentUser current = CurrentUserHolder.require();
        LearnerContext learner = learnerGuard.requireLearner(current);
        Employee primary = learner.primary();

        List<Long> enterpriseIds = learner.employees().stream().map(Employee::getEnterpriseId)
                .distinct().toList();
        Map<Long, Enterprise> byId = enterpriseMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<Enterprise>lambdaQuery()
                                .in(Enterprise::getId, enterpriseIds))
                .stream().collect(Collectors.toMap(Enterprise::getId, Function.identity()));

        Enterprise primaryEnterprise = byId.get(primary.getEnterpriseId());
        List<EnterpriseBrief> briefs = enterpriseIds.stream()
                .map(byId::get).filter(java.util.Objects::nonNull)
                .map(e -> new EnterpriseBrief(e.getId(), e.getName()))
                .toList();

        return ApiResponse.ok(new ProfileResponse(learner.accountId(), primary.getName(),
                EmployeeVo.maskPhone(learner.phone()),
                primary.getEnterpriseId(),
                primaryEnterprise == null ? "" : primaryEnterprise.getName(),
                primary.getStatus(), briefs.size(), briefs,
                accountService.mustChangePassword(learner.accountId())));
    }
}
