package com.buukle.agent.runtime.kernel.model.invoke;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.instance.domain.AgentLlmInteractionRecord;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.TokenUsage;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.spi.ModelProviderSpi;
import com.buukle.agent.util.TextSanitizer;
import com.buukle.agent.util.json.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.Map;

@Slf4j
@Service
public class KernelLlmService {
    private final ModelProviderSpi modelProviderSpi;
    private final ApplicationEventPublisher eventPublisher;
    private final AgentRuntimeProperties properties;
    private final AgentLlmInteractionRecordSpi interactionRecordSpi;

    public KernelLlmService(ModelProviderSpi modelProviderSpi,
                            ApplicationEventPublisher eventPublisher,
                            AgentRuntimeProperties properties,
                            AgentLlmInteractionRecordSpi interactionRecordSpi) {
        this.modelProviderSpi = modelProviderSpi;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.interactionRecordSpi = interactionRecordSpi;
    }

    /**
     * Creates the RUNNING row before the main agent's Q&A turn starts.
     *
     * <p>Non-main-agent calls (title generation / compaction / sub-agents) are not pre-created; the listener still inserts them after the call ends.
     * A pre-create failure must fail this call: otherwise the run ends up in an unrecoverable state of "already streaming output but no turn record".
     */
    private void preCreateTurnRecord(LlmInteractionMeta meta, String modelName) {
        if (meta == null
                || meta.getInteractionType() != LlmInteractionType.CHAT_REPLY
                || meta.getTurnNo() == null) {
            return;
        }
        AgentLlmInteractionRecordVO vo = new AgentLlmInteractionRecordVO();
        vo.setRunId(meta.getRunId());
        vo.setSessionId(meta.getSessionId());
        vo.setTurnNo(meta.getTurnNo());
        vo.setAttemptNo(meta.getAttemptNo() == null ? 0 : meta.getAttemptNo());
        vo.setRetryAttempt(meta.getRetryAttempt());
        vo.setInteractionType(meta.getInteractionType().name());
        vo.setModelName(modelName);
        vo.setSubAgentRunId(meta.getSubAgentRunId());
        vo.setStatus(AgentLlmInteractionRecord.STATUS_RUNNING);
        meta.setRecordId(interactionRecordSpi.createOrGetRunning(vo));
    }

    public CompletableFuture<Void> stream(String company, String baseUrl, String apiKey, String modelName,
                                          ChatCompletionRequestDTO request,
                                          Consumer<LLMEvent> onEvent, LlmInteractionMeta meta) {
        // Main agent turn: create the RUNNING row before the call, so Timeline has a stable reference from the first token.
        // Throw directly on failure here (do not start the stream), avoiding "no turn record yet output has already started".
        preCreateTurnRecord(meta, modelName);

        CompletableFuture<Void> future = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> {
            long start = System.currentTimeMillis();
            boolean success = true;
            String errorMessage = null;
            StringBuilder resultCollector = new StringBuilder();
            StringBuilder textCollector = new StringBuilder();
            StringBuilder reasoningCollector = new StringBuilder();
            AtomicReference<Map<String, Object>> usageRef = new AtomicReference<>();
            // Real termination reason: finish_reason=length/content_filter must be auditable (truncation must not count as success).
            AtomicReference<String> finishReasonRef = new AtomicReference<>();
            /**
             * An error reported by the provider as an <b>event</b> (rather than an exception).
             *
             * <p>It does not go through the catch below, so {@code success}/{@code errorMessage} always stay at their defaults —
             * as a result, failures that "took the run down directly" were all recorded in {@code agent_llm_interaction_record}
             * as {@code success=true, error_message=NULL}, making them impossible to audit afterwards.
             * An AtomicReference is used because the inner stream consumer cannot modify an outer local variable directly.
             */
            AtomicReference<String> errorEventRef = new AtomicReference<>();
            // Upstream HTTP status code (carried by error events); when successful with no error event it is recorded as 200 so it can be filtered directly by status.
            AtomicReference<Integer> httpStatusRef = new AtomicReference<>();
            // Provider transport-layer retry statistics (connect/TLS/EOF), used to locate network-type slow replies.
            AtomicReference<com.buukle.agent.model.dtvo.complete.LlmStreamStats> streamStatsRef =
                    new AtomicReference<>(com.buukle.agent.model.dtvo.complete.LlmStreamStats.empty());
            try {
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<Exception> streamError = new AtomicReference<>();

                Thread providerThread = Thread.ofVirtual().start(() -> {
                    try {
                        streamStatsRef.set(modelProviderSpi.streamWithStats(company, baseUrl, apiKey, modelName, request,
                                event -> {
                                    if (future.isCancelled()) return;
                                    // Sanitize NUL characters in LLM text (PG text columns reject them), then feed the collectors and forward events
                                    if (event instanceof LLMEvent.TextDelta(String text1)) {
                                        String clean = TextSanitizer.sanitize(text1);
                                        resultCollector.append(clean);
                                        textCollector.append(clean);
                                        onEvent.accept(new LLMEvent.TextDelta(clean));
                                    } else if (event instanceof LLMEvent.ReasoningDelta(String text)) {
                                        String clean = TextSanitizer.sanitize(text);
                                        reasoningCollector.append(clean);
                                        resultCollector.append(clean); // keep response_body compatible (merged)
                                        onEvent.accept(new LLMEvent.ReasoningDelta(clean));
                                    } else if (event instanceof LLMEvent.Error error) {
                                        // Record first, then forward: after forwarding the caller may terminate this call immediately
                                        errorEventRef.compareAndSet(null, error.message());
                                        if (error.httpStatus() != null) {
                                            httpStatusRef.set(error.httpStatus());
                                        }
                                        onEvent.accept(event);
                                    } else if (event instanceof LLMEvent.Finish(String reason, Map<String, Object> usage)) {
                                        finishReasonRef.set(reason);
                                        if (usage != null) {
                                            usageRef.set(usage);
                                        }
                                        onEvent.accept(event);
                                    } else {
                                        onEvent.accept(event);
                                    }
                                },
                                done::countDown));
                    } catch (Exception e) {
                        streamError.set(e);
                        done.countDown();
                    }
                });

                long timeout = properties.getLlm().getStreamTimeout().getSeconds();
                if (!done.await(timeout, TimeUnit.SECONDS)) {
                    throw new RuntimeException("LLM stream timed out after " + timeout + "s");
                }
                // onDone fires before streamWithStats returns; wait for the provider thread to actually return,
                // ensuring the transport statistics have already been written to streamStatsRef.
                joinQuietly(providerThread);
                Exception err = streamError.get();
                if (err != null) {
                    throw err;
                }
                if (!future.isCancelled()) future.complete(null);
            } catch (Exception e) {
                if (future.isCancelled()) return;
                success = false;
                errorMessage = e.getMessage();
                future.completeExceptionally(e instanceof RuntimeException re ? re : new RuntimeException(e));
            } finally {
                // Merge event errors and exception errors into a single failure record: if any path reports an error, this call did not succeed
                String eventError = errorEventRef.get();
                boolean failed = !success || eventError != null;
                String finalError = errorMessage != null ? errorMessage : eventError;
                // Persist http_status: error events carry the real status code; a normal finish is recorded as 200.
                Integer httpStatus = httpStatusRef.get();
                if (httpStatus == null && !failed) {
                    httpStatus = 200;
                }
                if (meta != null) {
                    meta.setHttpStatus(httpStatus);
                }
                // Record "the request body actually sent": the provider strategy may rewrite the request before the call (Qwen downgrades trailing system),
                // so serializing in finally is what matches the real production request; otherwise audit records mislead troubleshooting.
                String requestBody = JsonUtils.toJson(request);
                eventPublisher.publishEvent(new LlmInteractionEvent(
                        this, meta, modelName, requestBody, resultCollector.toString(),
                        textCollector.length() > 0 ? textCollector.toString() : null,
                        reasoningCollector.length() > 0 ? reasoningCollector.toString() : null,
                        TokenUsage.from(usageRef.get()),
                        System.currentTimeMillis() - start, !failed, finalError)
                        .withFinishReason(finishReasonRef.get())
                        .withStreamStats(streamStatsRef.get()));
            }
        });

        return future;
    }

    /**
     * Synchronous convenience call: internally reuses {@link #stream} to collect text, blocking until done (uses llm.stream-timeout).
     * Used by single-shot LLM capabilities (completions layer), and also goes through LlmInteractionEvent auditing.
     */
    public InvokeResult invokeSync(String company, String baseUrl, String apiKey, String modelName,
                                   ChatCompletionRequestDTO request, LlmInteractionMeta meta) {
        return invokeSync(company, baseUrl, apiKey, modelName, request, meta,
                properties.getLlm().getStreamTimeout().getSeconds());
    }

    public InvokeResult invokeSync(String company, String baseUrl, String apiKey, String modelName,
                                   ChatCompletionRequestDTO request, LlmInteractionMeta meta,
                                   long timeoutSeconds) {
        long start = System.currentTimeMillis();
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        StringBuilder merged = new StringBuilder();
        AtomicReference<Map<String, Object>> usageRef = new AtomicReference<>();
        AtomicReference<String> finishReasonRef = new AtomicReference<>();
        AtomicReference<Exception> failRef = new AtomicReference<>();
        AtomicReference<com.buukle.agent.model.dtvo.complete.LlmStreamStats> streamStatsRef =
                new AtomicReference<>(com.buukle.agent.model.dtvo.complete.LlmStreamStats.empty());
        CountDownLatch done = new CountDownLatch(1);

        request.setStream(true);
        Thread providerThread = Thread.ofVirtual().start(() -> {
            try {
                streamStatsRef.set(modelProviderSpi.streamWithStats(company, baseUrl, apiKey, modelName, request, event -> {
                    if (event instanceof LLMEvent.TextDelta(String t)) {
                        content.append(t);
                        merged.append(t);
                    } else if (event instanceof LLMEvent.ReasoningDelta(String r)) {
                        reasoning.append(r);
                        merged.append(r);
                    } else if (event instanceof LLMEvent.Finish(String reason, Map<String, Object> usage)) {
                        finishReasonRef.set(reason);
                        if (usage != null) {
                            usageRef.set(usage);
                        }
                    }
                }, done::countDown));
            } catch (Exception e) {
                failRef.set(e);
                done.countDown();
            }
        });

        try {
            if (!done.await(timeoutSeconds, TimeUnit.SECONDS)) {
                failRef.compareAndSet(null, new RuntimeException(
                        "LLM stream timed out after " + timeoutSeconds + "s"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failRef.compareAndSet(null, new RuntimeException("LLM call interrupted", e));
        }
        // Same as stream(): wait for the provider thread to return before reading transport statistics, avoiding an onDone race.
        joinQuietly(providerThread);
        Exception err = failRef.get();
        boolean success = err == null;
        String errorMessage = err == null ? null : err.getMessage();

        eventPublisher.publishEvent(new LlmInteractionEvent(
                this, meta, modelName, JsonUtils.toJson(request), merged.toString(),
                null, null, TokenUsage.from(usageRef.get()),
                System.currentTimeMillis() - start, success, errorMessage)
                .withFinishReason(finishReasonRef.get())
                .withStreamStats(streamStatsRef.get()));

        InvokeResult result = new InvokeResult();
        result.setSuccess(success);
        result.setContent(content.toString());
        result.setReasoning(reasoning.toString());
        result.setUsage(usageRef.get());
        result.setError(errorMessage);
        return result;
    }

    /** Waits for the provider thread to return in order to collect transport statistics; does not mask caller interruption. */
    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Result of a single synchronous LLM call. */
    public static class InvokeResult {
        private boolean success;
        private String content;
        private String reasoning;
        private Map<String, Object> usage;
        private String error;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getReasoning() {
            return reasoning;
        }

        public void setReasoning(String reasoning) {
            this.reasoning = reasoning;
        }

        public Map<String, Object> getUsage() {
            return usage;
        }

        public void setUsage(Map<String, Object> usage) {
            this.usage = usage;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }
    }
}
