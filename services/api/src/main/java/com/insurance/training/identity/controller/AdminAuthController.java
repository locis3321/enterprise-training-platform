package com.insurance.training.identity.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.identity.dto.AdminLoginRequest;
import com.insurance.training.identity.service.AdminAuthService;
import com.insurance.training.identity.vo.AdminLoginResponse;
import com.insurance.training.identity.vo.AdminProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端认证接口。 */
@Tag(name = "管理端-认证")
@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthController {

    private final AdminAuthService authService;

    public AdminAuthController(AdminAuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "账号密码登录")
    @PostMapping("/login")
    public ApiResponse<AdminLoginResponse> login(@Valid @RequestBody AdminLoginRequest request,
                                                 HttpServletRequest httpRequest) {
        String ip = CurrentUserHolder.clientIp(httpRequest);
        return ApiResponse.ok(authService.login(request, ip));
    }

    @Operation(summary = "当前登录管理员信息")
    @GetMapping("/me")
    public ApiResponse<AdminProfileResponse> me() {
        return ApiResponse.ok(authService.profile(CurrentUserHolder.require()));
    }
}
