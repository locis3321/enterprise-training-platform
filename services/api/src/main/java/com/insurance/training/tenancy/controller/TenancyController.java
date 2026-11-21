package com.insurance.training.tenancy.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.tenancy.dto.EnterpriseRequest;
import com.insurance.training.tenancy.dto.InsuranceCompanyRequest;
import com.insurance.training.tenancy.service.EnterpriseService;
import com.insurance.training.tenancy.service.InsuranceCompanyService;
import com.insurance.training.tenancy.vo.EnterpriseVo;
import com.insurance.training.tenancy.vo.InsuranceCompanyVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 集团与子公司（租户）管理接口。 */
@Tag(name = "管理端-租户")
@RestController
@RequestMapping("/api/v1/admin")
public class TenancyController {

    private final InsuranceCompanyService companyService;
    private final EnterpriseService enterpriseService;

    public TenancyController(InsuranceCompanyService companyService, EnterpriseService enterpriseService) {
        this.companyService = companyService;
        this.enterpriseService = enterpriseService;
    }

    @Operation(summary = "集团分页")
    @GetMapping("/insurance-companies")
    public ApiResponse<PageResult<InsuranceCompanyVo>> pageCompanies(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long pageSize) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(companyService.page(user, keyword, page, pageSize));
    }

    @Operation(summary = "新建集团（仅超级管理员）")
    @PostMapping("/insurance-companies")
    public ApiResponse<InsuranceCompanyVo> createCompany(@Valid @RequestBody InsuranceCompanyRequest request,
                                                        HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(companyService.create(user, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "修改集团（仅超级管理员）")
    @PutMapping("/insurance-companies/{id}")
    public ApiResponse<InsuranceCompanyVo> updateCompany(@PathVariable long id,
                                                        @Valid @RequestBody InsuranceCompanyRequest request,
                                                        HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(companyService.update(user, id, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "企业分页")
    @GetMapping("/enterprises")
    public ApiResponse<PageResult<EnterpriseVo>> pageEnterprises(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long pageSize) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(enterpriseService.page(user, companyId, keyword, status, page, pageSize));
    }

    @Operation(summary = "企业详情")
    @GetMapping("/enterprises/{id}")
    public ApiResponse<EnterpriseVo> getEnterprise(@PathVariable long id) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(EnterpriseVo.of(enterpriseService.getVisible(user, id)));
    }

    @Operation(summary = "新建企业")
    @PostMapping("/enterprises")
    public ApiResponse<EnterpriseVo> createEnterprise(@Valid @RequestBody EnterpriseRequest request,
                                                     HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(enterpriseService.create(user, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "修改企业")
    @PutMapping("/enterprises/{id}")
    public ApiResponse<EnterpriseVo> updateEnterprise(@PathVariable long id,
                                                     @Valid @RequestBody EnterpriseRequest request,
                                                     HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(enterpriseService.update(user, id, request, CurrentUserHolder.clientIp(http)));
    }
}
