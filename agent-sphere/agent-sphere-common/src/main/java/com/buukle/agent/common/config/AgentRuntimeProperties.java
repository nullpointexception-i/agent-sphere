package com.buukle.agent.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "buukle.agent")
public class AgentRuntimeProperties {
    private SessionConfig session = new SessionConfig();
    private StepConfig step = new StepConfig();
    private SseConfig sse = new SseConfig();
    private RunnerConfig runner = new RunnerConfig();
    private ToolConfig tool = new ToolConfig();
    private LlmConfig llm = new LlmConfig();
    private McpConfig mcp = new McpConfig();
    private LockConfig lock = new LockConfig();
    private AsyncConfig async = new AsyncConfig();
    private SsoConfig sso = new SsoConfig();
    private DistributedConfig distributed = new DistributedConfig();

    @Data
    public static class SessionConfig {
        private Duration idleTimeout = Duration.ofMinutes(30);
        private int maxConcurrentRuns = 10;
        private Duration cacheTtl = Duration.ofMinutes(60);
    }

    @Data
    public static class StepConfig {
        private int maxRetries = 3;
        private Duration executionTimeout = Duration.ofMinutes(5);
    }

    @Data
    public static class SseConfig {
        private int bufferSize = 1024;
        private Duration cacheTtl = Duration.ofSeconds(30);
        private Duration heartbeatInterval = Duration.ofSeconds(15);
    }

    @Data
    public static class RunnerConfig {
        private int maxLoopCount = 25;
        private Duration turnTimeout = Duration.ofSeconds(180);
        private CompactionConfig compaction = new CompactionConfig();

        @Data
        public static class CompactionConfig {
            private double budgetRatio = 0.5;
        }
    }

    @Data
    public static class ToolConfig {
        private int maxParallel = 3;
        private Duration submitTimeout = Duration.ofSeconds(30);
        private Duration executionTimeout = Duration.ofSeconds(30);
        private Duration cliTimeout = Duration.ofSeconds(30);
        private WebToolConfig webRead = new WebToolConfig();
        private WebToolConfig webFetch = new WebToolConfig();
        private WebReadAdvancedConfig webReadAdvanced = new WebReadAdvancedConfig();
        private WebFetchAdvancedConfig webFetchAdvanced = new WebFetchAdvancedConfig();
        private JsRenderConfig jsRender = new JsRenderConfig();

        @Data
        public static class WebToolConfig {
            private Duration connectTimeout = Duration.ofSeconds(10);
            private Duration readTimeout = Duration.ofSeconds(30);
        }

        @Data
        public static class WebReadAdvancedConfig {
            private boolean useJinaFallback = true;
            private String jinaReaderUrl = "https://r.jina.ai/";
            private String jinaApiKey = "";
            private String jinaEngine = "direct";
            private int jinaConnectTimeout = 30;
            private int jinaReadTimeout = 30;
            private int jinaTimeoutBuffer = 10;
            private HtmlToMarkdownConfig htmlToMarkdown = new HtmlToMarkdownConfig();

            @Data
            public static class HtmlToMarkdownConfig {
                private long maxContentLength = 2097152L;  // 2MB
                private String excludeTags = "script,style,noscript,meta,link,header,footer,nav,iframe,embed,object";
            }
        }

        @Data
        public static class WebFetchAdvancedConfig {
            private int maxRetries = 3;
            private int retryBackoffMultiplier = 2;
            private long retryDelayMs = 1000L;
            private int maxRedirects = 5;
            private long maxResponseBytes = 5242880L;
        }

        @Data
        public static class JsRenderConfig {
            private boolean enabled = true;
            private int maxWaitMillis = 10000;
            private int backgroundJsTimeout = 3000;
            private int maxPoolSize = 3;
        }
    }

    @Data
    public static class LlmConfig {
        private Duration connectTimeout = Duration.ofSeconds(30);
        private Duration readTimeout = Duration.ofSeconds(60);
        private Duration streamReadTimeout = Duration.ofSeconds(120);
        private Duration streamTimeout = Duration.ofSeconds(120);

    }

    @Data
    public static class McpConfig {
        private Duration connectTimeout = Duration.ofSeconds(30);
        private Duration sseInitTimeout = Duration.ofSeconds(30);
        private Duration sseReadTimeout = Duration.ofSeconds(30);
        private Duration rpcTimeout = Duration.ofSeconds(30);
        private Duration detectionTimeout = Duration.ofSeconds(10);
        private Duration directReadTimeout = Duration.ofSeconds(30);
        private Duration directConnectTimeout = Duration.ofSeconds(10);
    }

    @Data
    public static class LockConfig {
        private CapabilityLockConfig capability = new CapabilityLockConfig();

        @Data
        public static class CapabilityLockConfig {
            private Duration waitTime = Duration.ofSeconds(5);
            private Duration leaseTime = Duration.ofSeconds(10);
        }
    }

    @Data
    public static class AsyncConfig {
        private Duration awaitTermination = Duration.ofSeconds(30);
    }

    @Data
    public static class SsoConfig {
        private String baseUrl = "http://localhost:8080";
        private Duration stateTtl = Duration.ofMinutes(5);
        private Duration otcTtl = Duration.ofSeconds(30);
    }

    /** 多副本分布式运行态配置。 */
    @Data
    public static class DistributedConfig {
        /** session 执行者 owner 租约时长（过期后孤儿清扫接管）。 */
        private Duration ownerLease = Duration.ofMinutes(5);
        /** 孤儿 run 清扫间隔。 */
        private Duration orphanSweepInterval = Duration.ofSeconds(30);
    }
}
