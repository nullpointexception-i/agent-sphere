package com.buukle.agent.runtime.kernel.model.invoke;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.spi.ModelProviderSpi;
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

    public KernelLlmService(ModelProviderSpi modelProviderSpi,
                            ApplicationEventPublisher eventPublisher,
                            AgentRuntimeProperties properties) {
        this.modelProviderSpi = modelProviderSpi;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    public CompletableFuture<Void> stream(String company, String baseUrl, String apiKey, String modelName,
                                          ChatCompletionRequestDTO request,
                                          Consumer<LLMEvent> onEvent, LlmInteractionMeta meta) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> {
            long start = System.currentTimeMillis();
            boolean success = true;
            String errorMessage = null;
            StringBuilder resultCollector = new StringBuilder();
            String requestBody = JsonUtils.toJson(request);
            try {
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<Exception> streamError = new AtomicReference<>();

                Thread.ofVirtual().start(() -> {
                    try {
                        modelProviderSpi.stream(company, baseUrl, apiKey, modelName, request,
                                event -> {
                                    if (future.isCancelled()) return;
                                    if (event instanceof LLMEvent.TextDelta(String text1)) resultCollector.append(text1);
                                    else if (event instanceof LLMEvent.ReasoningDelta(String text)) resultCollector.append(text);
                                    onEvent.accept(event);
                                },
                                done::countDown);
                    } catch (Exception e) {
                        streamError.set(e);
                        done.countDown();
                    }
                });

                long timeout = properties.getLlm().getStreamTimeout().getSeconds();
                if (!done.await(timeout, TimeUnit.SECONDS)) {
                    throw new RuntimeException("LLM stream timed out after " + timeout + "s");
                }
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
                eventPublisher.publishEvent(new LlmInteractionEvent(
                        this, meta, modelName, requestBody, resultCollector.toString(),
                        System.currentTimeMillis() - start, success, errorMessage));
            }
        });

        return future;
    }

    /**
     * 同步便捷调用：内部复用 {@link #stream} 流式收集文本，阻塞直到完成（沿用 llm.stream-timeout）。
     * 供单次 LLM 能力（completions 层）使用，同样走 LlmInteractionEvent 审计。
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
        AtomicReference<Exception> failRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        request.setStream(true);
        Thread.ofVirtual().start(() -> {
            try {
                modelProviderSpi.stream(company, baseUrl, apiKey, modelName, request, event -> {
                    if (event instanceof LLMEvent.TextDelta(String t)) {
                        content.append(t);
                        merged.append(t);
                    } else if (event instanceof LLMEvent.ReasoningDelta(String r)) {
                        reasoning.append(r);
                        merged.append(r);
                    } else if (event instanceof LLMEvent.Finish(String reason, Map<String, Object> usage)) {
                        if (usage != null) {
                            usageRef.set(usage);
                        }
                    }
                }, done::countDown);
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
        Exception err = failRef.get();
        boolean success = err == null;
        String errorMessage = err == null ? null : err.getMessage();

        eventPublisher.publishEvent(new LlmInteractionEvent(
                this, meta, modelName, JsonUtils.toJson(request), merged.toString(),
                System.currentTimeMillis() - start, success, errorMessage));

        InvokeResult result = new InvokeResult();
        result.setSuccess(success);
        result.setContent(content.toString());
        result.setReasoning(reasoning.toString());
        result.setUsage(usageRef.get());
        result.setError(errorMessage);
        return result;
    }

    /** 单次同步 LLM 调用结果。 */
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
