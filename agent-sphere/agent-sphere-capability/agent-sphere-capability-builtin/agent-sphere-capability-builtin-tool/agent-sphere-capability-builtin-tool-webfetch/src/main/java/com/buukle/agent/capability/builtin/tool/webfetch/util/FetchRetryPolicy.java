package com.buukle.agent.capability.builtin.tool.webfetch.util;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Random;

/**
 * Fetch retry policy utility - implements an intelligent exponential-backoff retry mechanism
 */
@Slf4j
public class FetchRetryPolicy {

    private final int maxRetries;
    private final int backoffMultiplier;
    private final long initialDelayMs;
    private final Random random;

    /**
     * Initializes from the configuration object
     */
    public FetchRetryPolicy(AgentRuntimeProperties.ToolConfig.WebFetchAdvancedConfig config) {
        this.maxRetries = Math.max(1, config.getMaxRetries());
        this.backoffMultiplier = Math.max(1, config.getRetryBackoffMultiplier());
        this.initialDelayMs = Math.max(100, config.getRetryDelayMs());
        this.random = new Random();
    }

    /**
     * Checks whether a retry should happen
     *
     * @param attempt   current attempt number (starting at 0)
     * @param exception the exception that occurred
     * @return true when a retry should happen
     */
    public boolean shouldRetry(int attempt, Exception exception) {
        // Maximum retry count already reached
        if (attempt >= maxRetries) {
            log.debug("Max retries reached: {}", maxRetries);
            return false;
        }

        // Check whether the exception is retryable
        return isRetryable(exception);
    }

    /**
     * Checks whether the exception is retryable
     */
    public boolean isRetryable(Exception exception) {
        if (exception == null) {
            return false;
        }

        // Timeout exceptions: retryable
        if (exception instanceof HttpTimeoutException ||
                exception instanceof SocketTimeoutException) {
            log.debug("Timeout exception detected, will retry");
            return true;
        }

        // Connection exceptions: retryable
        if (exception instanceof ConnectException ||
                exception instanceof SocketException) {
            log.debug("Connection exception detected, will retry");
            return true;
        }

        // Network-related IOException: retryable
        if (exception instanceof IOException) {
            String message = exception.getMessage();
            if (message != null) {
                message = message.toLowerCase();
                // Check for common network error messages
                if (message.contains("connection") ||
                        message.contains("timeout") ||
                        message.contains("reset") ||
                        message.contains("broken pipe")) {
                    log.debug("Network-related IOException detected, will retry: {}", message);
                    return true;
                }
            }
        }

        // Other exceptions are not retried
        log.debug("Exception is not retryable: {}", exception.getClass().getName());
        return false;
    }

    /**
     * Computes the backoff delay in milliseconds
     * Formula: initialDelayMs * (backoffMultiplier ^ attempt)
     *
     * @param attempt current attempt number (starting at 0)
     * @return backoff delay in milliseconds
     */
    public long calculateBackoffDelayMs(int attempt) {
        long delay = initialDelayMs;
        for (int i = 0; i < attempt; i++) {
            delay *= backoffMultiplier;
        }
        // Add 0~1s of random jitter so request timing cannot be fingerprinted
        long jitter = random.nextLong(1001);
        return delay + jitter;
    }

    /**
     * Performs the backoff wait
     *
     * @param attempt current attempt number (starting at 0)
     */
    public void waitBeforeRetry(int attempt) {
        long delayMs = calculateBackoffDelayMs(attempt);
        log.debug("Retry {} after {} ms", attempt + 1, delayMs);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            log.warn("Retry wait interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the maximum retry count
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Builds the suggested log message
     */
    public String getRetryLogMessage(int attempt, Exception exception) {
        long delay = calculateBackoffDelayMs(attempt);
        return String.format("Retry attempt %d/%d after %d ms due to: %s",
                attempt + 1, maxRetries, delay, exception.getMessage());
    }

    /**
     * Builds the log message used when the maximum retry count is exceeded
     */
    public String getMaxRetriesExceededMessage(Exception lastException) {
        return String.format("Max retries (%d) exceeded. Last error: %s",
                maxRetries, lastException.getMessage());
    }
}
