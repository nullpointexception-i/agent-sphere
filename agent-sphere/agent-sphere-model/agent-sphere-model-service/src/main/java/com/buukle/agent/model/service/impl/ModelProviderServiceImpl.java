package com.buukle.agent.model.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.constant.OwnerScope;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.model.domain.AgentModelProvider;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.complete.LlmStreamStats;
import com.buukle.agent.model.dtvo.dto.CreateModelProviderDTO;
import com.buukle.agent.model.dtvo.dto.TokenUsage;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.vo.ModelProviderVO;
import com.buukle.agent.model.exception.ModelErrorCode;
import com.buukle.agent.model.repository.ModelProviderMapper;
import com.buukle.agent.model.service.ModelProviderService;
import com.buukle.agent.model.service.adapter.ChatRequestAdapter;
import com.buukle.agent.model.service.adapter.ChatResponseAdapter;
import com.buukle.agent.model.service.adapter.record.ContentToolCallResult;
import com.buukle.agent.model.service.constants.LlmApiConstants;
import com.buukle.agent.model.service.converter.ModelProviderConverter;
import com.buukle.agent.model.service.stream.LlmStreamUtils;
import com.buukle.agent.model.service.stream.ToolStream;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLHandshakeException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Service
@Primary
public class ModelProviderServiceImpl extends ServiceImpl<ModelProviderMapper, AgentModelProvider> implements ModelProviderService {
    private final ModelProviderConverter modelProviderConverter;
    private final AgentRuntimeProperties properties;
    private final AgentMetrics metrics;
    private final HttpClient httpClient;
    private final int logBodyMaxLength;
    private final long streamReadTimeoutSeconds;
    private final long streamHeaderTimeoutSeconds;
    private static final ScheduledExecutorService READ_TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "llm-read-timeout");
        t.setDaemon(true);
        return t;
    });
    @Autowired
    ChatRequestAdapter chatRequestAdapter;
    @Autowired
    ChatResponseAdapter chatResponseAdapter;

    public ModelProviderServiceImpl(ModelProviderConverter modelProviderConverter,
                                    AgentRuntimeProperties properties,
                                    AgentMetrics metrics,
                                    @Value("${buukle.agent.llm.log-body-max-length:2000}") int logBodyMaxLength) {
        this.modelProviderConverter = modelProviderConverter;
        this.properties = properties;
        this.metrics = metrics;
        this.logBodyMaxLength = logBodyMaxLength;
        this.streamReadTimeoutSeconds = properties.getLlm().getStreamReadTimeout().getSeconds();
        this.streamHeaderTimeoutSeconds = Math.max(1,
                properties.getLlm().getStreamHeaderTimeout().getSeconds());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getLlm().getConnectTimeout())
                .build();
    }

    @Override
    public ModelProviderVO createProvider(CreateModelProviderDTO dto) {
        AgentModelProvider provider = modelProviderConverter.toDO(dto);
        save(provider);
        return modelProviderConverter.toVO(provider);
    }

    @Override
    public ModelProviderVO getProvider(Long id) {
        AgentModelProvider provider = getById(id);
        if (provider == null) throw new BizException(ModelErrorCode.PROVIDER_NOT_FOUND);
        return modelProviderConverter.toVO(provider);
    }

    @Override
    public List<ModelProviderVO> listProviders(String keyword) {
        List<AgentModelProvider> providers = lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), AgentModelProvider::getName, keyword)
                .orderByDesc(AgentModelProvider::getCreatedAt)
                .list();
        return providers.stream().map(modelProviderConverter::toVO).toList();
    }

    @Override
    public com.baomidou.mybatisplus.core.metadata.IPage<ModelProviderVO> pageProviders(
            int page, int size, String keyword) {
        return pageProviders(page, size, keyword, null);
    }

    @Override
    public com.baomidou.mybatisplus.core.metadata.IPage<ModelProviderVO> pageProviders(
            int page, int size, String keyword, String scope) {
        boolean tenantScope = OwnerScope.isTenant(scope);
        com.buukle.agent.common.context.TenantScope currentScope =
                com.buukle.agent.common.context.TenantScopeHolder.currentOrFallback();
        com.buukle.agent.common.context.TenantScope previousScope =
                com.buukle.agent.common.context.TenantScopeHolder.get();
        try {
            if (tenantScope && currentScope != null && currentScope.selfOnly()) {
                com.buukle.agent.common.context.TenantScopeHolder.start(
                        new com.buukle.agent.common.context.TenantScope(
                                currentScope.userId(), currentScope.username(),
                                currentScope.currentTenantId(), currentScope.visibleTenantIds(),
                                false, currentScope.manageableTenantIds(),
                                currentScope.subtreeVisible()));
            }
            return lambdaQuery()
                    .eq(AgentModelProvider::getOwnerScope, tenantScope ? OwnerScope.TENANT : OwnerScope.USER)
                    .like(keyword != null && !keyword.isBlank(), AgentModelProvider::getName, keyword)
                    .orderByDesc(AgentModelProvider::getCreatedAt)
                    .page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                            Math.max(1, page), Math.max(1, size)))
                    .convert(modelProviderConverter::toVO);
        } finally {
            if (previousScope == null) {
                com.buukle.agent.common.context.TenantScopeHolder.clear();
            } else {
                com.buukle.agent.common.context.TenantScopeHolder.start(previousScope);
            }
        }
    }

    @Override
    public long countProviders() {
        return count();
    }

    @Override
    public ModelProviderVO updateProvider(Long id, CreateModelProviderDTO dto) {
        AgentModelProvider existing = requireProvider(id);
        requireTenantPermission(existing);
        AgentModelProvider provider = modelProviderConverter.toDO(dto);
        provider.setId(id);
        provider.setOwnerScope(existing.getOwnerScope());
        if (dto.getApiKeyId() == null) {
            provider.setApiKeyId(existing.getApiKeyId());
        }
        updateById(provider);
        return modelProviderConverter.toVO(getById(id));
    }

    @Override
    public void deleteProvider(Long id) {
        AgentModelProvider existing = requireProvider(id);
        requireTenantPermission(existing);
        if (baseMapper.countCurrentInstanceUsage(id) > 0) {
            throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID,
                    "The provider is used by the current tenant instance and cannot be deleted");
        }
        removeById(id);
    }

    private AgentModelProvider requireProvider(Long id) {
        AgentModelProvider provider = getById(id);
        if (provider == null) {
            throw new BizException(ModelErrorCode.PROVIDER_NOT_FOUND);
        }
        return provider;
    }

    /** Tenant-owned providers may only be maintained by tenant administrators. */
    private void requireTenantPermission(AgentModelProvider provider) {
        if (OwnerScope.isTenant(provider.getOwnerScope())
                && !AuthContext.isSuperAdmin()
                && !AuthContext.hasPermission(
                        com.buukle.agent.common.constant.PermissionCodes.MODEL_PROVIDER_TENANT)) {
            throw new BizException(com.buukle.agent.common.error.CommonErrorCode.FORBIDDEN,
                    "Cannot operate on a tenant-owned provider");
        }
    }

    @Override
    public void setActiveKey(Long id, Long apiKeyId) {
        lambdaUpdate().eq(AgentModelProvider::getId, id)
                .set(AgentModelProvider::getApiKeyId, apiKeyId).update();
    }

    @Override
    public void stream(String company, String baseUrl, String apiKey, String modelName,
                       ChatCompletionRequestDTO request,
                       Consumer<LLMEvent> onEvent, Runnable onDone) {
        streamWithStats(company, baseUrl, apiKey, modelName, request, onEvent, onDone);
    }

    @Override
    public LlmStreamStats streamWithStats(String company, String baseUrl, String apiKey,
                                          String modelName,
                                          ChatCompletionRequestDTO request,
                                          Consumer<LLMEvent> onEvent, Runnable onDone) {
        chatRequestAdapter.adapt(request, company);
        String requestBody = JsonUtils.toJson(request);
        return streamEvents(baseUrl, apiKey, modelName, requestBody, company, onEvent, onDone);
    }

    private LlmStreamStats streamEvents(String baseUrl, String apiKey, String modelName,
                                        String requestBody, String company,
                                        Consumer<LLMEvent> onEvent, Runnable onDone) {
        int streamMaxRetries = properties.getLlm().getStreamMaxRetries();
        int attempts = 0;
        int retryCount = 0;
        boolean handshakeFailed = false;
        String lastErrorType = null;
        String lastErrorMessage = null;

        for (int attempt = 0; attempt <= streamMaxRetries; attempt++) {
            if (attempt > 0) {
                long retryDelayMillis = retryDelayMillis(attempt);
                log.warn("LLM stream transient failure, retrying {}/{} in {}ms: model={}, errorType={}, handshake={}",
                        attempt, streamMaxRetries, retryDelayMillis, modelName,
                        lastErrorType, handshakeFailed);
                try {
                    Thread.sleep(retryDelayMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    onDone.run();
                    return stats(attempts, retryCount, handshakeFailed, lastErrorType, lastErrorMessage);
                }
            }
            attempts++;
            StreamAccumulator acc = new StreamAccumulator(onEvent);
            java.util.concurrent.Future<?> readTimeoutFuture = null;
            try {
                String url = baseUrl + LlmApiConstants.CHAT_COMPLETIONS_PATH;
                log.info("LLM stream request: model={}, url={}, body={}", modelName, url, truncate(requestBody));

                HttpRequest request = buildStreamRequest(url, apiKey, requestBody);

                // Separate short timeout for response headers: fail fast and retry when the TLS handshake /
                // response headers hang; the 120s response-body read timeout must not be reused here.
                HttpResponse<java.io.InputStream> response = httpClient.sendAsync(request,
                                HttpResponse.BodyHandlers.ofInputStream())
                        .orTimeout(streamHeaderTimeoutSeconds, TimeUnit.SECONDS)
                        .get();
                if (response.statusCode() >= 400) {
                    int status = response.statusCode();
                    String errorBody = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    log.error("LLM stream error: model={}, status={}, body={}", modelName, status, errorBody);
                    String uiMsg = switch (status) {
                        case 429 -> "Too many requests; retry later";
                        case 502 -> "The model service is temporarily unavailable";
                        default -> "Model call failed (" + status + "): " + truncateError(errorBody);
                    };
                    // 4xx (except 408/429) are terminal errors: retrying cannot succeed and only multiplies one
                    // failure into several; 5xx/429/408 are genuine transient faults, so retries are kept.
                    boolean retryable = status == 408 || status == 429 || status >= 500;
                    onEvent.accept(new LLMEvent.Error(uiMsg, status, retryable));
                    onDone.run();
                    return stats(attempts, retryCount, handshakeFailed, lastErrorType, lastErrorMessage);
                }
                log.info("LLM stream response: status={}", response.statusCode());

                ToolStream toolStream = acc.toolStream;
                // Response headers are ready: for the body phase, use a scheduled interrupt as the read-timeout
                // fallback (cancelled each round to avoid a leftover interrupt).
                readTimeoutFuture = READ_TIMEOUT_SCHEDULER.schedule(
                        Thread.currentThread()::interrupt, streamReadTimeoutSeconds, TimeUnit.SECONDS);
                consumeStreamBody(response, company, onEvent, acc);
                toolStream.finishAll();

                emitContentToolCalls(acc, company, onEvent);

                Map<String, Object> usage = toUsageMap(acc.usage.get());
                TokenUsage usageModel = TokenUsage.from(usage);
                logStreamCompleted(modelName, acc, usage, usageModel);
                String finishReason = resolveFinishReason(acc.lastFinishReason);
                warnOnAbnormalFinish(modelName, finishReason, acc.chunkCount);
                onEvent.accept(new LLMEvent.Finish(finishReason, usage));
                onDone.run();
                if (readTimeoutFuture != null) {
                    readTimeoutFuture.cancel(false);
                }
                return stats(attempts, retryCount, handshakeFailed, lastErrorType, lastErrorMessage);
            } catch (Exception e) {
                boolean userTimedOut = Thread.interrupted();
                if (readTimeoutFuture != null) {
                    readTimeoutFuture.cancel(false);
                }
                Throwable root = rootCause(e);
                lastErrorType = root.getClass().getSimpleName();
                lastErrorMessage = truncateError(e.toString());
                boolean handshake = isHandshakeFailure(e);
                if (handshake) {
                    handshakeFailed = true;
                    metrics.increment(AgentMetrics.LLM_STREAM_HANDSHAKE_FAILED, "model", modelName);
                }
                // Transient transport failure (EOF/disconnect, no chunk received) -> retry; timeout/cancel -> no retry.
                if (attempt < streamMaxRetries && acc.chunkCount == 0 && !userTimedOut) {
                    retryCount++;
                    metrics.increment(AgentMetrics.LLM_STREAM_RETRY, "model", modelName);
                    log.warn("LLM stream failed, will retry: model={}, attempt={}/{}, timedOut={}, chunks={}, errorType={}, handshake={}, err={}",
                            modelName, attempt + 1, streamMaxRetries + 1, userTimedOut, acc.chunkCount,
                            lastErrorType, handshake, e.toString());
                    continue;
                }
                log.error("LLM stream failed: model={}, timedOut={}, chunks={}, retried={}, errorType={}, handshake={}",
                        modelName, userTimedOut, acc.chunkCount, attempt, lastErrorType, handshake, e);
                // Report an explicit error instead of silently returning an empty result, so the upper layer can
                // degrade the route / retry the task.
                onEvent.accept(new LLMEvent.Error(userTimedOut
                        ? "Model stream read timed out (already retried)"
                        : "Upstream connection interrupted (chunks=0); still failing after " + attempt + " retries"));
                onDone.run();
                return stats(attempts, retryCount, handshakeFailed, lastErrorType, lastErrorMessage);
            }
        }
        onDone.run();
        return stats(attempts, retryCount, handshakeFailed, lastErrorType, lastErrorMessage);
    }

    private HttpRequest buildStreamRequest(String url, String apiKey, String requestBody) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header(LlmApiConstants.HEADER_CONTENT_TYPE, LlmApiConstants.APPLICATION_JSON)
                .header(LlmApiConstants.HEADER_AUTHORIZATION, LlmApiConstants.BEARER_PREFIX + apiKey)
                .header(LlmApiConstants.HEADER_ACCEPT, LlmApiConstants.TEXT_EVENT_STREAM)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    /** Reads the SSE body and folds every data frame into the accumulator. */
    private void consumeStreamBody(HttpResponse<java.io.InputStream> response, String company,
                                   Consumer<LLMEvent> onEvent, StreamAccumulator acc) throws java.io.IOException {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(LlmApiConstants.SSE_DATA_PREFIX)) {
                    continue;
                }
                String data = line.substring(LlmApiConstants.SSE_DATA_PREFIX.length()).trim();
                if (LlmApiConstants.SSE_DONE_MARKER.equals(data)) {
                    break;
                }
                acc.chunkCount++;
                try {
                    applyChunk(JsonUtils.parse(data, JsonNode.class), company, onEvent, acc);
                } catch (Exception ignored) {
                    // A malformed frame must not abort the whole stream.
                }
            }
        }
    }

    /** Applies one OpenAI-compatible chunk: usage frame, finish_reason, reasoning/content deltas. */
    private void applyChunk(JsonNode chunk, String company, Consumer<LLMEvent> onEvent, StreamAccumulator acc) {
        if (chunk == null) {
            return;
        }
        // Final usage frame (stream_options.include_usage): choices is empty, usage sits at
        // the chunk top level; keep the last non-null one.
        if (chunk.has(LlmApiConstants.FIELD_USAGE) && !chunk.get(LlmApiConstants.FIELD_USAGE).isNull()) {
            acc.usage.set(chunk.get(LlmApiConstants.FIELD_USAGE));
        }
        if (!chunk.has(LlmApiConstants.FIELD_CHOICES)
                || !chunk.get(LlmApiConstants.FIELD_CHOICES).isArray()
                || chunk.get(LlmApiConstants.FIELD_CHOICES).isEmpty()) {
            return;
        }
        JsonNode choice = chunk.get(LlmApiConstants.FIELD_CHOICES).get(0);
        String finishReason = textOrNull(choice, LlmApiConstants.FIELD_FINISH_REASON);
        if (finishReason != null) {
            acc.lastFinishReason = finishReason;
            log.debug("LLM finish_reason: {}", finishReason);
        }
        JsonNode delta = choice.get(LlmApiConstants.FIELD_DELTA);
        if (delta == null) {
            return;
        }
        // Dual-field compatibility: vLLM/Qwen3 use reasoning, DeepSeek/Zhipu use reasoning_content.
        String reasoning = LlmStreamUtils.firstTextual(delta,
                LlmApiConstants.FIELD_REASONING,
                LlmApiConstants.FIELD_REASONING_CONTENT);
        if (reasoning != null && !reasoning.isEmpty()) {
            acc.reasoning.append(reasoning);
            onEvent.accept(new LLMEvent.ReasoningDelta(reasoning));
        }
        String content = textOrNull(delta, LlmApiConstants.FIELD_CONTENT);
        if (content != null && !content.isEmpty()) {
            acc.content.append(content);
            onEvent.accept(new LLMEvent.TextDelta(content));
        }
        chatResponseAdapter.adaptResponse(choice, acc.toolStream, company);
    }

    /** Falls back to parsing tool calls out of the assistant text when no native tool_calls arrived. */
    private void emitContentToolCalls(StreamAccumulator acc, String company, Consumer<LLMEvent> onEvent) {
        if (!acc.toolStream.isEmpty()) {
            return;
        }
        ContentToolCallResult parsed = chatResponseAdapter.extractToolCalls(acc.content.toString(), company);
        if (parsed.isEmpty()) {
            parsed = chatResponseAdapter.extractToolCalls(acc.reasoning.toString(), company);
        }
        if (parsed.isEmpty()) {
            return;
        }
        acc.content.setLength(0);
        if (parsed.cleanedContent() != null) {
            acc.content.append(parsed.cleanedContent());
        }
        for (ContentToolCallResult.ContentToolCall toolCall : parsed.toolCalls()) {
            String callId = LlmApiConstants.PARSED_CALL_ID_PREFIX + System.nanoTime();
            onEvent.accept(new LLMEvent.ToolCall(callId, toolCall.name(), toolCall.arguments()));
        }
    }

    private Map<String, Object> toUsageMap(JsonNode usageNode) {
        return usageNode == null ? Map.of()
                : JsonUtils.getMapper().convertValue(usageNode,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }

    private void logStreamCompleted(String modelName, StreamAccumulator acc,
                                    Map<String, Object> usage, TokenUsage usageModel) {
        log.info("LLM stream completed: model={}, chunks={}, contentLen={}, reasoningLen={}, usage={}, cached={}/{}",
                modelName, acc.chunkCount, acc.content.length(), acc.reasoning.length(),
                usage.isEmpty() ? "-" : usage.getOrDefault("total_tokens", "-"),
                usageModel == null ? "-" : usageModel.getCacheHitTokens(),
                usageModel == null ? "-" : usageModel.getCacheMissTokens());
    }

    private String resolveFinishReason(String lastFinishReason) {
        return lastFinishReason != null && !lastFinishReason.isBlank()
                ? lastFinishReason
                : com.buukle.agent.model.dtvo.complete.FinishReason.STOP;
    }

    private void warnOnAbnormalFinish(String modelName, String finishReason, int chunkCount) {
        if (!com.buukle.agent.model.dtvo.complete.FinishReason.STOP.equals(finishReason)
                && !com.buukle.agent.model.dtvo.complete.FinishReason.TOOL_CALLS.equals(finishReason)) {
            log.warn("LLM stream ended with abnormal finish_reason: model={}, reason={}, chunks={}",
                    modelName, finishReason, chunkCount);
        }
    }

    /** Returns the trimmed textual value of a field, or null when absent/null/non-textual/blank. */
    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isTextual()) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    /** Mutable per-attempt stream state (text/reasoning/usage/tool stream). */
    private static final class StreamAccumulator {
        private final ToolStream toolStream;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final AtomicReference<JsonNode> usage = new AtomicReference<>();
        private int chunkCount;
        private String lastFinishReason;

        private StreamAccumulator(Consumer<LLMEvent> onEvent) {
            this.toolStream = new ToolStream(onEvent);
        }
    }

    /** Backoff for the Nth retry (N starts at 1); use the last entry once the list is exhausted. */
    private long retryDelayMillis(int attempt) {
        List<Duration> delays = properties.getLlm().getStreamRetryDelays();
        if (delays != null && !delays.isEmpty()) {
            Duration delay = delays.get(Math.min(attempt - 1, delays.size() - 1));
            if (delay != null) {
                return Math.max(0L, delay.toMillis());
            }
        }
        Duration fallback = properties.getLlm().getStreamRetryDelay();
        return fallback == null ? 0L : Math.max(0L, fallback.toMillis());
    }

    private static LlmStreamStats stats(int attempts, int retryCount, boolean handshakeFailed,
                                        String lastErrorType, String lastErrorMessage) {
        return new LlmStreamStats(attempts, retryCount, handshakeFailed,
                lastErrorType, lastErrorMessage);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isHandshakeFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SSLHandshakeException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("SSLHandshake")) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }


    private String truncate(String body) {
        if (body == null || body.isEmpty() || logBodyMaxLength <= 0 || body.length() <= logBodyMaxLength) return body;
        return body.substring(0, logBodyMaxLength) + "...<truncated>";
    }

    /** Error bodies get a separate, larger limit: the full reason of a 400 must be persisted, otherwise production issues cannot be diagnosed. */
    private static String truncateError(String body) {
        int max = 2000;
        if (body == null || body.length() <= max) return body;
        return body.substring(0, max) + "...<truncated>";
    }

}
