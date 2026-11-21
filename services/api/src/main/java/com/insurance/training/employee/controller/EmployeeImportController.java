package com.insurance.training.employee.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.employee.imports.EmployeeImportService;
import com.insurance.training.employee.imports.ImportResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/** 员工 Excel 导入接口。 */
@Tag(name = "管理端-员工导入")
@RestController
@RequestMapping("/api/v1/admin/employees")
public class EmployeeImportController {

    private final EmployeeImportService importService;

    public EmployeeImportController(EmployeeImportService importService) {
        this.importService = importService;
    }

    @Operation(summary = "下载导入模板")
    @GetMapping("/import-template")
    public ResponseEntity<byte[]> template() {
        DataScopeGuard.requireAdmin();
        byte[] body = importService.template();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("employee-import-template.xlsx", StandardCharsets.UTF_8)
                                .build().toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @Operation(summary = "批量导入员工")
    @PostMapping("/import")
    public ApiResponse<ImportResult> importEmployees(@RequestParam("file") MultipartFile file,
                                                     HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(importService.importFile(user, file, CurrentUserHolder.clientIp(http)));
    }
}
