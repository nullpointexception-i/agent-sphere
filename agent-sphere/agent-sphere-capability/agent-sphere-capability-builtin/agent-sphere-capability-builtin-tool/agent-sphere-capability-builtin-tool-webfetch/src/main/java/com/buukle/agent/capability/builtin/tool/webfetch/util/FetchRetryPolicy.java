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
 * 获取重试策略工具类 - 实现指数退避的智能重试机制
 */
@Slf4j
public class FetchRetryPolicy {

    private final int maxRetries;
    private final int backoffMultiplier;
    private final long initialDelayMs;
    private final Random random;

    /**
     * 通过配置对象初始化
     */
    public FetchRetryPolicy(AgentRuntimeProperties.ToolConfig.WebFetchAdvancedConfig config) {
        this.maxRetries = Math.max(1, config.getMaxRetries());
        this.backoffMultiplier = Math.max(1, config.getRetryBackoffMultiplier());
        this.initialDelayMs = Math.max(100, config.getRetryDelayMs());
        this.random = new Random();
    }

    /**
     * 检查是否应该重试
     *
     * @param attempt 当前尝试次数（从 0 开始）
     * @param exception 发生的异常
     * @return 如果应该重试返回 true
     */
    public boolean shouldRetry(int attempt, Exception exception) {
        // 已经达到最大重试次数
        if (attempt >= maxRetries) {
            log.debug("Max retries reached: {}", maxRetries);
            return false;
        }

        // 检查异常是否可重试
        return isRetryable(exception);
    }

    /**
     * 检查异常是否可重试
     */
    public boolean isRetryable(Exception exception) {
        if (exception == null) {
            return false;
        }

        // 超时异常：可重试
        if (exception instanceof HttpTimeoutException ||
            exception instanceof SocketTimeoutException) {
            log.debug("Timeout exception detected, will retry");
            return true;
        }

        // 连接异常：可重试
        if (exception instanceof ConnectException ||
            exception instanceof SocketException) {
            log.debug("Connection exception detected, will retry");
            return true;
        }

        // 网络相关的 IOException：可重试
        if (exception instanceof IOException) {
            String message = exception.getMessage();
            if (message != null) {
                message = message.toLowerCase();
                // 检查常见的网络错误信息
                if (message.contains("connection") || 
                    message.contains("timeout") || 
                    message.contains("reset") ||
                    message.contains("broken pipe")) {
                    log.debug("Network-related IOException detected, will retry: {}", message);
                    return true;
                }
            }
        }

        // 其他异常不重试
        log.debug("Exception is not retryable: {}", exception.getClass().getName());
        return false;
    }

    /**
     * 计算退避延迟时间（毫秒）
     * 公式：initialDelayMs * (backoffMultiplier ^ attempt)
     *
     * @param attempt 当前尝试次数（从 0 开始）
     * @return 退避延迟时间（毫秒）
     */
    public long calculateBackoffDelayMs(int attempt) {
        long delay = initialDelayMs;
        for (int i = 0; i < attempt; i++) {
            delay *= backoffMultiplier;
        }
        // 加入 0~1s 的随机抖动，避免请求时序被检测
        long jitter = random.nextLong(1001);
        return delay + jitter;
    }

    /**
     * 执行退避等待
     *
     * @param attempt 当前尝试次数（从 0 开始）
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
     * 获取最大重试次数
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * 创建建议的日志消息
     */
    public String getRetryLogMessage(int attempt, Exception exception) {
        long delay = calculateBackoffDelayMs(attempt);
        return String.format("Retry attempt %d/%d after %d ms due to: %s",
                attempt + 1, maxRetries, delay, exception.getMessage());
    }

    /**
     * 创建最大重试次数超过时的日志消息
     */
    public String getMaxRetriesExceededMessage(Exception lastException) {
        return String.format("Max retries (%d) exceeded. Last error: %s",
                maxRetries, lastException.getMessage());
    }
}
