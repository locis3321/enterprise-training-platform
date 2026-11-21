package com.insurance.training.common.response;

import org.slf4j.MDC;

/**
 * 统一响应体：{@code {code, message, data, traceId}}。
 *
 * <p>{@code traceId} 取自 {@link com.insurance.training.common.web.TraceIdFilter} 写入的 MDC，
 * 便于日志与前端报错对账；同时通过响应头 {@code X-Trace-Id} 返回。
 */
public record ApiResponse<T>(int code, String message, T data, String traceId) {

    private static final String TRACE_ID_KEY = "traceId";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.getCode(), ErrorCode.OK.getDefaultMessage(), data, currentTraceId());
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return error(errorCode.getCode(), errorCode.getDefaultMessage());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return error(errorCode.getCode(), message);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, currentTraceId());
    }

    private static String currentTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }
}
