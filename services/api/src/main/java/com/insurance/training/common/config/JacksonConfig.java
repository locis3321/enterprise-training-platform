package com.insurance.training.common.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * 序列化统一配置。
 *
 * <p>时区固定 Asia/Shanghai（见 docs/DECISIONS D-04）：
 * 数据库 DATETIME 存本地时间、应用与序列化统一 +08:00，避免截止时间/考试超时判定出现 8 小时偏差。
 * 时间字段禁止输出时间戳，统一 ISO-8601。
 */
@Configuration
public class JacksonConfig {

    public static final String BUSINESS_TIME_ZONE = "Asia/Shanghai";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .timeZone(TimeZone.getTimeZone(BUSINESS_TIME_ZONE))
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
