package com.buukle.agent.common.exception;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {
    private final String errorCode;
    private final String userTip;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode.getCode();
        this.userTip = errorCode.getUserTip();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode.getCode();
        this.userTip = errorCode.getUserTip();
    }

    public BizException(ErrorCode errorCode, String message, String userTip) {
        super(message);
        this.errorCode = errorCode.getCode();
        this.userTip = userTip;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode.getCode();
        this.userTip = errorCode.getUserTip();
    }
}
