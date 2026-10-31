package com.buukle.agent.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    private DelegateConfig delegate = new DelegateConfig();
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
        /** Agentic run 连续无工具回复的最大容错轮数；超过后终止，防止无界模型调用。 */
        private int maxSilentTurns = 2;
        /** 任务触发的 run 使用更高轮次上限（覆盖 maxLoopCount），缺省回落 maxLoopCount。 */
        private Integer taskMaxLoopCount;
        /** 单个 run 内允许注入的浏览器截图观察次数上限（防模型无限截图烧 token）。 */
        private int maxScreenshotsPerRun = 20;
        /** 单 run 推理落库长度上限（数据完整兜底，默认 50 万字符）。 */
        private int maxReasoningChars = 500000;
        /** 必须大于 LLM 的 streamTimeout，否则单轮先被取消。 */
        private Duration turnTimeout = Duration.ofSeconds(660);
        /**
         * 单轮 LLM 失败（非取消）时在同轮内重试次数（退避见
         * {@code RunnerConstants.LLM_TURN_RETRY_BASE_MS}，指数递增）。
         *
         * <p>默认 3 而非 1：模型端会突发性连续秒回错误，实测有 run 在 9 秒内连撞 3 次就耗尽
         * 了 2 次尝试的预算而 FAILED，只有一个恰好只撞 2 次的活了下来。合计 4 次尝试可覆盖
         * 大多数抖动窗口。
         */
        private int llmTurnMaxRetries = 3;
        /** live 非浏览器工具结果单条压缩上限。 */
        private int liveToolResultMaxChars = 4000;
        /** live 非浏览器工具结果总预算；超出后更旧的结果用省略标记。 */
        private int liveToolResultBudgetChars = 50000;
        /** 非浏览器工具结果始终完整保留的最近条数。 */
        private int liveToolResultKeepRecent = 2;
        /** 浏览器结果保留全文的单条字符上限；超过后改为 callId 引用（大页面）。 */
        private int liveBrowserFullMaxChars = 20000;
        private CompactionConfig compaction = new CompactionConfig();

        @Data
        public static class CompactionConfig {
            /** 触发阈值（占 maxInputTokens 比例）。 */
            private double budgetRatio = 0.5;
            /** 压缩后目标（含 messages + tools + fixed 的总估算）。 */
            private double targetRatio = 0.55;
            /** 自适应目标：固定开销 + tail 放不下配置目标时自动抬高 target。 */
            private boolean adaptiveTargetEnabled = true;
            /** 自适应 target 至少为 tail 保留的 token 数。 */
            private long minTailTokens = 2000;
            /** 压缩输入预算占 maxInput 的比例。 */
            private double inputRatio = 0.35;
            /** run-local tail 预算占 maxInput 的比例。 */
            private double tailRatio = 0.25;
            /** 摘要绝对上限（tokens）。 */
            private long summaryMaxTokens = 10000;
            /** 摘要超限时最多再压缩几轮（不含首轮）。 */
            private int summaryCompressRounds = 3;
            /** 摘要上限占 maxInput 的比例（路由自适应）。 */
            private double summaryMaxRatio = 0.12;
            /** 压缩后至少间隔的轮数（硬上限超限时不受限）。 */
            private int cooldownTurns = 4;
            /** 单 run run-local 压缩次数上限。 */
            private int maxPerRun = 12;
            /** 按上下文长度分档；为空时使用上面的 ratio 配置。 */
            private List<CompactionTier> tiers = new ArrayList<>();
        }

        /** 一个上下文档位的绝对 token 预算。 */
        @Data
        public static class CompactionTier {
            /** 该档位适用的最大上下文长度（含）。 */
            private long maxInputTokens;
            /** 触发压缩的输入 token 上限。 */
            private long triggerTokens;
            /** 压缩后的目标输入 token 上限。 */
            private long targetTokens;
            /** 该档位聊天请求的 max_tokens 上限；0/null 表示不额外限制。 */
            private Long chatMaxOutputTokens;
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
        private Duration streamTimeout = Duration.ofSeconds(600);
        /** 流式传输瞬时失败（EOF/断连等，chunks=0）重试次数，耗尽后显式报错。 */
        private int streamMaxRetries = 2;
        /** 流式重试固定退避时长。 */
        private Duration streamRetryDelay = Duration.ofSeconds(1);
    }

    /** 统一 delegate 子 Agent 执行配置。 */
    @Data
    public static class DelegateConfig {
        /** 是否启用 delegate 子 Agent 机制（feature flag，可一键回退）。 */
        private boolean enabled = true;
        /** 子 Agent 最大嵌套深度。 */
        private int maxNestedDepth = 3;
        /** 子循环最大轮次。 */
        private int maxSubLoopCount = 8;
        /** 单个子 Agent 执行总超时（外层工具 fiber 需容得下该预算）。 */
        private Duration executionTimeout = Duration.ofMinutes(10);
        /** 渲染后 prompt 最大长度。 */
        private int maxPromptChars = 20000;
        /** 子 Agent 返回结果最大字符数。 */
        private int maxResultChars = 4000;
        /** DAG 层内最大并行数。 */
        private int maxParallel = 4;
        /** DAG 任务数上限。 */
        private int maxDagTasks = 16;
        /** 缺失/UNCERTAIN 泳道最多重派发轮数（P0 静默截断防御）。 */
        private int maxDagRetries = 1;
        /** 信封上游预算（字节）：超限时截断上游 value 并标 truncated，信封本身永不整体丢弃。 */
        private int maxEnvelopeChars = 8000;
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
