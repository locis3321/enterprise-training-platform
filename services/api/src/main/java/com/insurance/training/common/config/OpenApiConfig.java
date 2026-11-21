package com.insurance.training.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 文档配置。
 *
 * <p>前端（管理端与小程序）通过 {@code /v3/api-docs} 生成 TypeScript 类型，
 * 接口变更必须同步文档，禁止"文档一套、实现一套"。
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI insuranceTrainingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("企业内训平台 API")
                        .version("v1")
                        .description("""
                                统一前缀 `/api/v1`。
                                - 管理端：`/api/v1/admin/**`
                                - 小程序：`/api/v1/miniapp/**`
                                - 响应体：`{code, message, data, traceId}`，code 与 HTTP 状态码语义一致
                                - 时间：ISO-8601，时区 Asia/Shanghai
                                """))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("管理端与小程序统一使用 Bearer JWT（Task 02 起生效）")));
    }
}
