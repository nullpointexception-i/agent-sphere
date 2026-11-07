package com.buukle.agent.runtime.kernel.config;

/**
 * A single route attempt failed.
 *
 * <p>Carries the HTTP status and a retryable flag so {@link FallbackRouteExecutor} can decide whether switching to a fallback route is worthwhile:
 * 4xx (except 408/429) usually cannot be fixed by switching models, so fail directly and keep the original error instead of letting the
 * next route's secondary error mask it.
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

    /** Whether a fallback route should still be tried. */
    public boolean shouldFallback() {
        if (!retryable) {
            return false;
        }
        if (httpStatus == null) {
            return true; // timeout / connection failure
        }
        if (httpStatus == 408 || httpStatus == 429) {
            return true;
        }
        return httpStatus >= 500;
    }
}
