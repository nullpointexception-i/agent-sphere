package com.buukle.agent.common.config;

import com.buukle.agent.common.constant.ReasoningEffort;
import com.buukle.agent.common.constant.ReasoningMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        /** Maximum number of consecutive tool-less replies tolerated in an agentic run; the run is terminated beyond this to prevent unbounded model calls. */
        private int maxSilentTurns = 2;
        /** Task-triggered runs use a higher loop limit (overrides maxLoopCount); falls back to maxLoopCount when unset. */
        private Integer taskMaxLoopCount;
        /** Maximum number of browser screenshot observations injected into a single run (prevents the model from screenshotting forever and burning tokens). */
        private int maxScreenshotsPerRun = 20;
        /** Maximum number of recent screenshot observations replayed on resume (wait/clarification/approval); only the current run is replayed. */
        private int screenshotReplayMax = 2;
        /** Total byte cap for replayed screenshot data URLs on resume; skipped when exceeded. */
        private int screenshotReplayMaxBytes = 2_000_000;
        /** Maximum length of reasoning persisted per run (data-integrity fallback, 500k characters by default). */
        private int maxReasoningChars = 500000;
        /** Must be greater than the LLM streamTimeout, otherwise a single turn is cancelled first. */
        private Duration turnTimeout = Duration.ofSeconds(660);
        /**
         * Retry count within the same turn after a single-turn LLM failure (not cancellation); the
         * backoff is defined by {@code RunnerConstants.LLM_TURN_RETRY_BASE_MS} and grows exponentially.
         *
         * <p>Defaults to 3 rather than 1: the model side fails in bursts of instant errors; in
         * production a run hit 3 of them within 9 seconds and exhausted a budget of 2 attempts and
         * FAILED, while only the run that happened to hit exactly 2 survived. A total of 4 attempts
         * covers most jitter windows.
         */
        private int llmTurnMaxRetries = 3;
        /**
         * Graded backoff for transient LLM failures (timeout / 5xx / 429) (V115).
         *
         * <p>"Many short steps, sawtooth": the first few rungs retry in place, then every few rungs
         * the delay returns to 1s/3s, so a few tens of seconds of upstream jitter is caught by
         * several quick retries instead of backing off exponentially to minutes. The first
         * {@link #llmRetryInlineRungs} rungs sleep within the turn; the remaining rungs suspend the
         * run (WAITING, kind=LLM_RETRY).
         */
        private java.util.List<java.time.Duration> llmRetryLadder = java.util.List.of(
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(3),
                java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(15),
                java.time.Duration.ofSeconds(20),
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(3),
                java.time.Duration.ofSeconds(15), java.time.Duration.ofSeconds(20),
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(3),
                java.time.Duration.ofSeconds(15), java.time.Duration.ofSeconds(20),
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(3),
                java.time.Duration.ofSeconds(15), java.time.Duration.ofSeconds(20),
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(3),
                java.time.Duration.ofSeconds(15), java.time.Duration.ofSeconds(20),
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(3),
                java.time.Duration.ofSeconds(15), java.time.Duration.ofSeconds(20));
        /** The first few rungs retry in place with sleep; beyond them the run suspends. */
        private int llmRetryInlineRungs = 2;
        /** Jitter ratio (±) for suspended rungs, so multiple runs do not wake at the same moment and saturate upstream. */
        private double llmRetryJitter = 0.2;
        /** Total retry window measured from the first failure; exceeding it means FAILED. */
        private java.time.Duration llmRetryMaxTotal = java.time.Duration.ofMinutes(5);
        /** Per-item compaction cap for live non-browser tool results. */
        private int liveToolResultMaxChars = 4000;
        /** Total budget for live non-browser tool results; older results are replaced with an omission marker once exceeded. */
        private int liveToolResultBudgetChars = 50000;
        /** Number of recent non-browser tool results always kept in full. */
        private int liveToolResultKeepRecent = 2;
        /** Per-item character cap for keeping browser results in full; larger ones become callId references (large pages). */
        private int liveBrowserFullMaxChars = 20000;
        private CompactionConfig compaction = new CompactionConfig();

        @Data
        public static class CompactionConfig {
            /** Trigger threshold (fraction of maxInputTokens). */
            private double budgetRatio = 0.5;
            /** Post-compaction target (total estimate including messages + tools + fixed overhead). */
            private double targetRatio = 0.55;
            /** Adaptive target: raise the target automatically when the configured target cannot fit the fixed overhead + tail. */
            private boolean adaptiveTargetEnabled = true;
            /** Tokens the adaptive target reserves for the tail at minimum. */
            private long minTailTokens = 2000;
            /** Compaction input budget as a fraction of maxInput. */
            private double inputRatio = 0.35;
            /** run-local tail budget as a fraction of maxInput. */
            private double tailRatio = 0.25;
            /** Absolute summary cap (tokens). */
            private long summaryMaxTokens = 10000;
            /** Maximum number of additional compression rounds when the summary exceeds the cap (excluding the first round). */
            private int summaryCompressRounds = 3;
            /** Summary cap as a fraction of maxInput (route-adaptive). */
            private double summaryMaxRatio = 0.12;
            /** Minimum number of turns between compactions (not applied when the hard cap is exceeded). */
            private int cooldownTurns = 4;
            /** Maximum number of run-local compactions per run. */
            private int maxPerRun = 12;
            /** Buckets by context length; when empty, the ratio settings above are used. */
            private List<CompactionTier> tiers = new ArrayList<>();
        }

        /** Absolute token budget for one context tier. */
        @Data
        public static class CompactionTier {
            /** Maximum context length this tier applies to (inclusive). */
            private long maxInputTokens;
            /** Input token threshold that triggers compaction. */
            private long triggerTokens;
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
        /** Response header / TLS handshake timeout, separate from the body read timeout so a stuck handshake does not wait the full 120s. */
        private Duration streamHeaderTimeout = Duration.ofSeconds(10);
        private Duration streamTimeout = Duration.ofSeconds(600);
        /** Retry count for transient stream failures (EOF/disconnect, chunks=0); an explicit error is raised once exhausted. */
        private int streamMaxRetries = 2;
        /** Fixed backoff for stream retries (fallback when streamRetryDelays is not configured). */
        private Duration streamRetryDelay = Duration.ofSeconds(1);
        /** Stream retry backoff ladder; attempt N uses entry N, and the last entry is reused beyond that. */
        private List<Duration> streamRetryDelays = new ArrayList<>(
                List.of(Duration.ofMillis(300), Duration.ofSeconds(1)));
        /** Unified reasoning-effort configuration: global thresholds + each provider's supported range. */
        private ReasoningConfig reasoning = new ReasoningConfig();

        @Data
        public static class ReasoningConfig {
            /** Answer retention ratio: answerReserve = max(minAnswerTokens, maxOutput * ratio). */
            private double answerReserveRatio = 0.25;
            /** Minimum number of tokens reserved for the answer body. */
            private int minAnswerTokens = 1024;
            /** Lower bound of the Qwen thinking budget. */
            private int minThinkingBudget = 1024;
            /** Effort levels from low to high, used to rank the "highest feasible level". */
            private List<String> levelOrder = new ArrayList<>(ReasoningEffort.ORDER);
            /** Default thresholds per level (used when neither provider nor route overrides them). */
            private Map<String, LevelConfig> levels = defaultLevels();
            /** Provider default supported ranges; key = ModelProviderCompany.value (supplied by application.yml). */
            private Map<String, ProviderReasoningConfig> providers = new LinkedHashMap<>();
            /** Fallback levels when a provider has no levels configured. */
            private List<String> defaultLevels = new ArrayList<>(ReasoningEffort.DEFAULT_LEVELS);
            /** Upper bound of the automatic default level: without a user choice the level will not exceed this (users can still pick higher manually). */
            private String defaultMaxLevel = ReasoningEffort.HIGH;

            private static Map<String, LevelConfig> defaultLevels() {
                Map<String, LevelConfig> map = new LinkedHashMap<>();
                map.put(ReasoningEffort.NONE, level(0));
                map.put(ReasoningEffort.MINIMAL, level(2048));
                map.put(ReasoningEffort.LOW, level(4096));
                map.put(ReasoningEffort.MEDIUM, level(8192));
                map.put(ReasoningEffort.HIGH, level(16384));
                map.put(ReasoningEffort.XHIGH, level(32768));
                map.put(ReasoningEffort.MAX, level(65536));
                return map;
            }

            private static LevelConfig level(long minOutputTokens) {
                LevelConfig cfg = new LevelConfig();
                cfg.setMinOutputTokens(minOutputTokens);
                return cfg;
            }
        }

        @Data
        public static class LevelConfig {
            /** Recommended minimum output budget for this level (tokens). */
            private long minOutputTokens;
        }

        @Data
        public static class ProviderReasoningConfig {
            /** Whether reasoning effort is supported. */
            private boolean supported = false;
            /** Always levels for now; the field is kept for future extension. */
            private String mode = ReasoningMode.LEVELS;
            /** Supported levels; empty means use the global default-levels. */
            private List<String> levels = new ArrayList<>();
            /** Provider-level answer retention ratio; empty inherits the global value. */
            private Double answerReserveRatio;
            /** Provider-level per-level threshold overrides. */
            private Map<String, Long> levelOverrides = new LinkedHashMap<>();
            /** Whether the Qwen gateway supports thinking_budget; when false only enable_thinking is sent. */
            private boolean thinkingBudgetSupported = true;
        }
    }

    /** Unified execution configuration for delegate sub-agents. */
    @Data
    public static class DelegateConfig {
        /** Whether the delegate sub-agent mechanism is enabled (feature flag, roll back with one switch). */
        private boolean enabled = true;
        /** Maximum sub-agent nesting depth. */
        private int maxNestedDepth = 3;
        /** Maximum number of sub-loop turns. */
        private int maxSubLoopCount = 8;
        /** Total execution timeout for a single sub-agent (the outer tool fiber must accommodate this budget). */
        private Duration executionTimeout = Duration.ofMinutes(10);
        /** Maximum rendered prompt length. */
        private int maxPromptChars = 20000;
        /** Maximum number of characters returned by a sub-agent. */
        private int maxResultChars = 4000;
        /** Maximum parallelism within a DAG layer. */
        private int maxParallel = 4;
        /** Maximum number of DAG tasks. */
        private int maxDagTasks = 16;
        /** Maximum number of re-dispatch rounds for missing/UNCERTAIN lanes (P0 silent-truncation defense). */
        private int maxDagRetries = 1;
        /** Upstream envelope budget (bytes): when exceeded, truncate upstream values and mark them truncated; the envelope itself is never dropped as a whole. */
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

    /** Distributed runtime state configuration for multiple replicas. */
    @Data
    public static class DistributedConfig {
        /** Session executor owner lease duration (orphan sweep takes over after it expires). */
        private Duration ownerLease = Duration.ofMinutes(5);
        /** Orphan run sweep interval. */
        private Duration orphanSweepInterval = Duration.ofSeconds(30);
    }
}
