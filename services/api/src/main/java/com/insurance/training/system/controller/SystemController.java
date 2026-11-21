package com.insurance.training.system.controller;

import com.insurance.training.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统自检接口（非业务接口）。
 *
 * <p>用途：验证服务、时区、统一响应与 traceId 链路是否正常；
 * 部署验收与前后端联调的锚点。不包含任何业务数据。
 */
@Tag(name = "系统", description = "健康自检与运行环境信息")
@RestController
@RequestMapping("/api/v1")
public class SystemController {

    private final String applicationName;
    private final String timeZone;

    public SystemController(
            @Value("${spring.application.name:insurance-training-api}") String applicationName,
            @Value("${app.timezone:Asia/Shanghai}") String timeZone) {
        this.applicationName = applicationName;
        this.timeZone = timeZone;
    }

    @Operation(summary = "服务自检", description = "返回服务名、构建版本与服务器当前时间（带 +08:00 偏移）")
    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", applicationName);
        body.put("version", getClass().getPackage().getImplementationVersion());
        body.put("serverTime", OffsetDateTime.now(ZoneId.of(timeZone)).toString());
        body.put("timeZone", timeZone);
        return ApiResponse.ok(body);
    }
}
