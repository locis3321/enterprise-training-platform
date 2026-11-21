package com.insurance.training.common.response;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("成功响应使用 code=200，并携带 traceId")
    void okCarriesTraceId() {
        MDC.put("traceId", "trace-abc");

        ApiResponse<String> response = ApiResponse.ok("hello");

        assertThat(response.code()).isEqualTo(ErrorCode.OK.getCode());
        assertThat(response.message()).isEqualTo("success");
        assertThat(response.data()).isEqualTo("hello");
        assertThat(response.traceId()).isEqualTo("trace-abc");
    }

    @Test
    @DisplayName("业务异常响应不携带 data，错误码与枚举一致")
    void errorUsesErrorCode() {
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.FORBIDDEN);

        assertThat(response.code()).isEqualTo(403);
        assertThat(response.message()).isEqualTo("无权访问该资源");
        assertThat(response.data()).isNull();
    }

    @Test
    @DisplayName("自定义消息覆盖默认消息")
    void errorAllowsCustomMessage() {
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.CONFLICT, "该手机号已匹配到多个员工");

        assertThat(response.code()).isEqualTo(409);
        assertThat(response.message()).isEqualTo("该手机号已匹配到多个员工");
    }
}
