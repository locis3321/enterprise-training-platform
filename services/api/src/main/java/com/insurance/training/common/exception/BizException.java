package com.insurance.training.common.exception;

import com.insurance.training.common.response.ErrorCode;

/**
 * 业务异常。抛出后由 {@link GlobalExceptionHandler} 统一转换为响应体，不返回堆栈给客户端。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
