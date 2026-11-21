package com.insurance.training.common.exception;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.web.TraceIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理的契约测试。
 *
 * <p>用 standalone MockMvc 而非 {@code @SpringBootTest}：不依赖数据库与 Redis，
 * 保证 CI 在无中间件环境下也能验证「异常 -> 统一响应体」这一契约。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    @DisplayName("参数校验失败返回 400，并指出具体字段")
    void validationFailureReturns400() throws Exception {
        mockMvc.perform(post("/probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")))
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER));
    }

    @Test
    @DisplayName("业务异常按其错误码映射 HTTP 状态")
    void bizExceptionMapsStatus() throws Exception {
        mockMvc.perform(get("/probe/biz"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该手机号已匹配到多个员工"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("未预期异常返回 500，且不泄露内部异常信息")
    void unexpectedExceptionHidesDetails() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.getDefaultMessage()));
    }

    @Test
    @DisplayName("响应体 traceId 与响应头一致")
    void traceIdIsConsistent() throws Exception {
        mockMvc.perform(get("/probe/biz").header(TraceIdFilter.TRACE_ID_HEADER, "client-trace-1"))
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "client-trace-1"))
                .andExpect(jsonPath("$.traceId").value("client-trace-1"));
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @PostMapping("/validate")
        ApiResponse<Void> validate(@Valid @RequestBody ProbeRequest request) {
            return ApiResponse.ok();
        }

        @GetMapping("/biz")
        ApiResponse<Void> biz() {
            throw new BizException(ErrorCode.CONFLICT, "该手机号已匹配到多个员工");
        }

        @GetMapping("/boom")
        ApiResponse<Void> boom() {
            throw new IllegalStateException("connection refused to 10.0.0.1:3306");
        }
    }

    record ProbeRequest(@NotBlank String name) {
    }
}
