package com.buukle.agent.runtime.kernel.config;

/**
 * 单条路由尝试失败。
 *
 * <p>携带 HTTP 状态与可重试标记，供 {@link FallbackRouteExecutor} 判断是否值得切到备用路由：
 * 4xx（408/429 除外）通常换模型也无济于事，直接失败并保留原始错误，避免被下一条路由的
 * 二次错误掩盖。
 */
public class RouteAttemptException extends RuntimeException {

    private final Integer httpStatus;
    private final boolean retryable;

    public RouteAttemptException(String message, Integer httpStatus, boolean retryable) {
        super(message);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** 是否应该继续尝试备用路由。 */
    public boolean shouldFallback() {
        if (!retryable) {
            return false;
        }
        if (httpStatus == null) {
            return true; // 超时 / 连接失败
        }
        if (httpStatus == 408 || httpStatus == 429) {
            return true;
        }
        return httpStatus >= 500;
    }
}
