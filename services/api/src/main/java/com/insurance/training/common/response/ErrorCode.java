package com.insurance.training.common.response;

/**
 * 统一响应码。
 *
 * <p>约定：{@code code} 与 HTTP 状态码语义保持一致（200 成功、400 参数错误、401 未认证、403 无权限、404 不存在、
 * 409 状态冲突、429 频率超限、500 服务端错误），避免前端同时维护两套码表。
 * 小程序端 unibest 的 {@code isSuccessResultCode} 接受 0 与 200，本服务统一返回 200。
 */
public enum ErrorCode {

    OK(200, "success", 200),
    BAD_REQUEST(400, "请求参数不合法", 400),
    UNAUTHORIZED(401, "未认证或登录已过期", 401),
    FORBIDDEN(403, "无权访问该资源", 403),
    NOT_FOUND(404, "资源不存在", 404),
    METHOD_NOT_ALLOWED(405, "请求方法不支持", 405),
    CONFLICT(409, "资源状态冲突", 409),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试", 429),
    INTERNAL_ERROR(500, "服务器内部错误", 500);

    private final int code;
    private final String defaultMessage;
    private final int httpStatus;

    ErrorCode(int code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
