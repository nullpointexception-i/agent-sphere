package com.buukle.agent.runtime.kernel.config;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class LlmContextValidator {

    private static final List<String> SENSITIVE_PREFIXES = Arrays.asList(
            "key", "password", "secret", "token", "auth"
    );

    private static final List<String> VALID_ENGINES = Arrays.asList("direct", "browser");

    private final AgentRuntimeProperties properties;

    public LlmContextValidator(AgentRuntimeProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validate() {
        log.info("=== buukle.agent configuration dump ===");
        dumpConfig("", properties);
        log.info("=== end of configuration dump ===");

        // === 严重校验（阻止启动） ===
        var t = properties.getTool();
        var session = properties.getSession();
        var runner = properties.getRunner();
        var step = properties.getStep();
        var sse = properties.getSse();
        var llm = properties.getLlm();
        var mcp = properties.getMcp();
        var lock = properties.getLock();
        var async = properties.getAsync();

        require(session.getMaxConcurrentRuns() >= 1, "session.max-concurrent-runs must be >= 1, got: " + session.getMaxConcurrentRuns());
        require(session.getIdleTimeout() != null && session.getIdleTimeout().getSeconds() >= 30,
                "session.idle-timeout must be >= 30s, got: " + session.getIdleTimeout());
        require(session.getCacheTtl() != null && session.getCacheTtl().getSeconds() >= 10,
                "session.cache-ttl must be >= 10s, got: " + session.getCacheTtl());
        require(step.getExecutionTimeout() != null && step.getExecutionTimeout().getSeconds() >= 10,
                "step.execution-timeout must be >= 10s, got: " + step.getExecutionTimeout());
        require(step.getMaxRetries() >= 0, "step.max-retries must be >= 0, got: " + step.getMaxRetries());
        require(sse.getBufferSize() >= 256, "sse.buffer-size must be >= 256, got: " + sse.getBufferSize());
        require(sse.getHeartbeatInterval() != null && sse.getHeartbeatInterval().getSeconds() >= 5,
                "sse.heartbeat-interval must be >= 5s, got: " + sse.getHeartbeatInterval());
        require(sse.getCacheTtl() != null && sse.getCacheTtl().getSeconds() >= 5,
                "sse.cache-ttl must be >= 5s, got: " + sse.getCacheTtl());
        require(runner.getMaxLoopCount() >= 1, "runner.max-loop-count must be >= 1, got: " + runner.getMaxLoopCount());
        require(runner.getTurnTimeout() != null && runner.getTurnTimeout().getSeconds() >= 10,
                "runner.turn-timeout must be >= 10s, got: " + runner.getTurnTimeout());
        require(runner.getCompaction().getBudgetRatio() > 0 && runner.getCompaction().getBudgetRatio() < 1,
                "runner.compaction.budget-ratio must be between 0 and 1, got: " + runner.getCompaction().getBudgetRatio());
        require(t.getMaxParallel() >= 1, "tool.max-parallel must be >= 1, got: " + t.getMaxParallel());
        require(t.getSubmitTimeout() != null && t.getSubmitTimeout().getSeconds() >= 5,
                "tool.submit-timeout must be >= 5s, got: " + t.getSubmitTimeout());
        require(t.getExecutionTimeout() != null && t.getExecutionTimeout().getSeconds() >= 5,
                "tool.execution-timeout must be >= 5s, got: " + t.getExecutionTimeout());
        require(t.getCliTimeout() != null && t.getCliTimeout().getSeconds() >= 5,
                "tool.cli-timeout must be >= 5s, got: " + t.getCliTimeout());
        require(t.getExecutionTimeout() == null || t.getSubmitTimeout() == null
                        || t.getExecutionTimeout().getSeconds() >= t.getSubmitTimeout().getSeconds(),
                "tool.execution-timeout (" + t.getExecutionTimeout() + ") must be >= tool.submit-timeout ("
                        + t.getSubmitTimeout() + ")");
        require(t.getWebRead().getConnectTimeout() != null && t.getWebRead().getConnectTimeout().getSeconds() >= 1,
                "tool.web-read.connect-timeout must be >= 1s, got: " + t.getWebRead().getConnectTimeout());
        require(t.getWebRead().getReadTimeout() != null && t.getWebRead().getReadTimeout().getSeconds() >= 5,
                "tool.web-read.read-timeout must be >= 5s, got: " + t.getWebRead().getReadTimeout());
        require(t.getWebFetch().getConnectTimeout() != null && t.getWebFetch().getConnectTimeout().getSeconds() >= 1,
                "tool.web-fetch.connect-timeout must be >= 1s, got: " + t.getWebFetch().getConnectTimeout());
        require(t.getWebFetch().getReadTimeout() != null && t.getWebFetch().getReadTimeout().getSeconds() >= 5,
                "tool.web-fetch.read-timeout must be >= 5s, got: " + t.getWebFetch().getReadTimeout());
        require(t.getJsRender().getMaxPoolSize() >= 1,
                "tool.js-render.max-pool-size must be >= 1, got: " + t.getJsRender().getMaxPoolSize());
        require(t.getJsRender().getMaxWaitMillis() >= 1000,
                "tool.js-render.max-wait-millis must be >= 1000, got: " + t.getJsRender().getMaxWaitMillis());
        require(t.getJsRender().getBackgroundJsTimeout() >= 500,
                "tool.js-render.background-js-timeout must be >= 500, got: " + t.getJsRender().getBackgroundJsTimeout());

        require(llm.getConnectTimeout() != null && llm.getConnectTimeout().getSeconds() >= 5,
                "llm.connect-timeout must be >= 5s, got: " + llm.getConnectTimeout());
        require(llm.getReadTimeout() != null && llm.getReadTimeout().getSeconds() >= 5,
                "llm.read-timeout must be >= 5s, got: " + llm.getReadTimeout());
        require(llm.getStreamReadTimeout() != null && llm.getStreamReadTimeout().getSeconds() >= 10,
                "llm.stream-read-timeout must be >= 10s, got: " + llm.getStreamReadTimeout());
        require(llm.getStreamTimeout() != null && llm.getStreamTimeout().getSeconds() >= 10,
                "llm.stream-timeout must be >= 10s, got: " + llm.getStreamTimeout());
        require(mcp.getConnectTimeout() != null && mcp.getConnectTimeout().getSeconds() >= 5,
                "mcp.connect-timeout must be >= 5s, got: " + mcp.getConnectTimeout());
        require(mcp.getSseInitTimeout() != null && mcp.getSseInitTimeout().getSeconds() >= 5,
                "mcp.sse-init-timeout must be >= 5s, got: " + mcp.getSseInitTimeout());
        require(mcp.getSseReadTimeout() != null && mcp.getSseReadTimeout().getSeconds() >= 5,
                "mcp.sse-read-timeout must be >= 5s, got: " + mcp.getSseReadTimeout());
        require(mcp.getRpcTimeout() != null && mcp.getRpcTimeout().getSeconds() >= 5,
                "mcp.rpc-timeout must be >= 5s, got: " + mcp.getRpcTimeout());
        require(mcp.getDetectionTimeout() != null && mcp.getDetectionTimeout().getSeconds() >= 1,
                "mcp.detection-timeout must be >= 1s, got: " + mcp.getDetectionTimeout());
        require(mcp.getDirectReadTimeout() != null && mcp.getDirectReadTimeout().getSeconds() >= 5,
                "mcp.direct-read-timeout must be >= 5s, got: " + mcp.getDirectReadTimeout());
        require(mcp.getDirectConnectTimeout() != null && mcp.getDirectConnectTimeout().getSeconds() >= 1,
                "mcp.direct-connect-timeout must be >= 1s, got: " + mcp.getDirectConnectTimeout());
        require(lock.getCapability().getWaitTime() != null && lock.getCapability().getWaitTime().getSeconds() >= 1,
                "lock.capability.wait-time must be >= 1s, got: " + lock.getCapability().getWaitTime());
        require(lock.getCapability().getLeaseTime() != null && lock.getCapability().getLeaseTime().getSeconds() >= 1,
                "lock.capability.lease-time must be >= 1s, got: " + lock.getCapability().getLeaseTime());
        require(lock.getCapability().getLeaseTime() == null || lock.getCapability().getWaitTime() == null
                        || lock.getCapability().getLeaseTime().getSeconds() > lock.getCapability().getWaitTime().getSeconds(),
                "lock.capability.lease-time (" + lock.getCapability().getLeaseTime()
                        + ") must be > lock.capability.wait-time (" + lock.getCapability().getWaitTime() + ")");
        require(async.getAwaitTermination() != null && async.getAwaitTermination().getSeconds() >= 5,
                "async.await-termination must be >= 5s, got: " + async.getAwaitTermination());

        // === WebRead advanced 校验 ===
        var wrAdv = t.getWebReadAdvanced();
        if (wrAdv.isUseJinaFallback()) {
            require(wrAdv.getJinaApiKey() != null && !wrAdv.getJinaApiKey().isEmpty(),
                    "web-read-advanced.jina-api-key must be non-empty when use-jina-fallback=true");
        }
        require(VALID_ENGINES.contains(wrAdv.getJinaEngine()),
                "web-read-advanced.jina-engine must be one of " + VALID_ENGINES + ", got: " + wrAdv.getJinaEngine());
        require(wrAdv.getJinaConnectTimeout() >= 5,
                "web-read-advanced.jina-connect-timeout must be >= 5s, got: " + wrAdv.getJinaConnectTimeout());
        require(wrAdv.getJinaReadTimeout() >= 5,
                "web-read-advanced.jina-read-timeout must be >= 5s, got: " + wrAdv.getJinaReadTimeout());

        // === WebFetch advanced 校验 ===
        var wfAdv = t.getWebFetchAdvanced();
        require(wfAdv.getMaxRetries() >= 0,
                "web-fetch-advanced.max-retries must be >= 0, got: " + wfAdv.getMaxRetries());
        require(wfAdv.getRetryBackoffMultiplier() >= 1,
                "web-fetch-advanced.retry-backoff-multiplier must be >= 1, got: " + wfAdv.getRetryBackoffMultiplier());
        require(wfAdv.getRetryDelayMs() >= 100,
                "web-fetch-advanced.retry-delay-ms must be >= 100, got: " + wfAdv.getRetryDelayMs());
        require(wfAdv.getMaxRedirects() >= 0,
                "web-fetch-advanced.max-redirects must be >= 0, got: " + wfAdv.getMaxRedirects());
        require(wfAdv.getMaxResponseBytes() >= 1024,
                "web-fetch-advanced.max-response-bytes must be >= 1024, got: " + wfAdv.getMaxResponseBytes());

        // === 跨字段 WARN（不阻止启动） ===
        if (wrAdv.isUseJinaFallback()) {
            long htmlMax = wrAdv.getHtmlToMarkdown().getMaxContentLength();
            if (wfAdv.getMaxResponseBytes() > htmlMax) {
                log.warn("web-fetch-advanced.max-response-bytes ({}) > html-to-markdown.max-content-length ({}), "
                                + "fetched content may be truncated by Markdown converter",
                        wfAdv.getMaxResponseBytes(), htmlMax);
            }
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            log.error("Configuration validation failed: {}", message);
            throw new IllegalStateException(message);
        }
    }

    private void dumpConfig(String prefix, Object obj) {
        if (obj == null) return;

        for (Field field : obj.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            String name = field.getName();
            String fullKey = prefix.isEmpty() ? name : prefix + "." + name;

            try {
                Object value = field.get(obj);
                if (value == null) continue;
                if (value == obj) continue;

                if (isConfigClass(value.getClass())) {
                    dumpConfig(fullKey, value);
                } else {
                    String displayValue = maskIfSensitive(name, value);
                    log.info("  {} = {}", fullKey, displayValue);
                }
            } catch (IllegalAccessException e) {
                log.warn("  {} = <unreadable>", fullKey);
            }
        }
    }

    private boolean isConfigClass(Class<?> clazz) {
        return !clazz.isPrimitive()
                && clazz != String.class
                && clazz != Boolean.class
                && clazz != Integer.class
                && clazz != Long.class
                && clazz != Float.class
                && clazz != Double.class
                && clazz != Short.class
                && clazz != Byte.class
                && !Duration.class.isAssignableFrom(clazz)
                && !clazz.isEnum();
    }

    private String maskIfSensitive(String fieldName, Object value) {
        String str = valueToString(value);
        if (str == null || str.isEmpty()) return "<empty>";

        boolean sensitive = SENSITIVE_PREFIXES.stream()
                .anyMatch(p -> fieldName.toLowerCase().contains(p));
        if (sensitive) {
            if (str.length() <= 8) return "****";
            return str.substring(0, 4) + "****" + str.substring(str.length() - 4);
        }
        return str;
    }

    private String valueToString(Object value) {
        if (value instanceof Duration) {
            return ((Duration) value).getSeconds() + "s";
        }
        return value.toString();
    }
}
