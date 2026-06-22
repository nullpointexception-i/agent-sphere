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
import java.util.function.Consumer;

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
                modelProviderSpi.stream(company, baseUrl, apiKey, modelName, request,
                        event -> {
                            if (future.isCancelled()) return;
                            if (event instanceof LLMEvent.TextDelta(String text1)) resultCollector.append(text1);
                            else if (event instanceof LLMEvent.ReasoningDelta(String text)) resultCollector.append(text);
                            onEvent.accept(event);
                        },
                        done::countDown);
                long timeout = properties.getLlm().getStreamTimeout().getSeconds();
                if (!done.await(timeout, TimeUnit.SECONDS)) {
                    throw new RuntimeException("LLM stream timed out after " + timeout + "s");
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
}
