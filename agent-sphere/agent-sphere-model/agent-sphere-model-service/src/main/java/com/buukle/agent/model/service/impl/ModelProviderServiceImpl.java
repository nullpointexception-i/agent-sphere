package com.buukle.agent.model.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.model.domain.AgentModelProvider;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.dto.CreateModelProviderDTO;
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
import com.buukle.agent.model.service.stream.ToolStream;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@Service
@Primary
public class ModelProviderServiceImpl extends ServiceImpl<ModelProviderMapper, AgentModelProvider> implements ModelProviderService {
    private final ModelProviderConverter modelProviderConverter;
    private final AgentRuntimeProperties properties;
    private final HttpClient httpClient;
    private final int logBodyMaxLength;
    private final long streamReadTimeoutSeconds;
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
                                    @Value("${buukle.agent.llm.log-body-max-length:2000}") int logBodyMaxLength) {
        this.modelProviderConverter = modelProviderConverter;
        this.properties = properties;
        this.logBodyMaxLength = logBodyMaxLength;
        this.streamReadTimeoutSeconds = properties.getLlm().getStreamReadTimeout().getSeconds();
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
    public long countProviders() {
        return count();
    }

    @Override
    public ModelProviderVO updateProvider(Long id, CreateModelProviderDTO dto) {
        AgentModelProvider provider = modelProviderConverter.toDO(dto);
        provider.setId(id);
        updateById(provider);
        return modelProviderConverter.toVO(provider);
    }

    @Override
    public void deleteProvider(Long id) {
        removeById(id);
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
        chatRequestAdapter.adapt(request, company);
        String requestBody = JsonUtils.toJson(request);
        streamEvents(baseUrl, apiKey, modelName, requestBody, company, onEvent, onDone);
    }

    private void streamEvents(String baseUrl, String apiKey, String modelName, String requestBody,
                              String company, Consumer<LLMEvent> onEvent, Runnable onDone) {
        int streamMaxRetries = properties.getLlm().getStreamMaxRetries();
        long retryDelayMillis = properties.getLlm().getStreamRetryDelay().toMillis();

        for (int attempt = 0; attempt <= streamMaxRetries; attempt++) {
            if (attempt > 0) {
                log.warn("LLM stream transient failure, retrying {}/{}: model={}", attempt, streamMaxRetries, modelName);
                try {
                    Thread.sleep(retryDelayMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    onDone.run();
                    return;
                }
            }
            int chunkCount = 0;
            java.util.concurrent.Future<?> readTimeoutFuture = null;
            try {
                StringBuilder contentBuilder = new StringBuilder();
                StringBuilder reasoningBuilder = new StringBuilder();
                String url = baseUrl + LlmApiConstants.CHAT_COMPLETIONS_PATH;
                log.info("LLM stream request: model={}, url={}, body={}", modelName, url, truncate(requestBody));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header(LlmApiConstants.HEADER_CONTENT_TYPE, LlmApiConstants.APPLICATION_JSON)
                        .header(LlmApiConstants.HEADER_AUTHORIZATION, LlmApiConstants.BEARER_PREFIX + apiKey)
                        .header(LlmApiConstants.HEADER_ACCEPT, LlmApiConstants.TEXT_EVENT_STREAM)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<java.io.InputStream> response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                        .orTimeout(streamReadTimeoutSeconds, TimeUnit.SECONDS)
                        .get();
                if (response.statusCode() >= 400) {
                    String errorBody = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    log.error("LLM stream error: status={}, body={}", response.statusCode(), errorBody);
                    String uiMsg = switch (response.statusCode()) {
                        case 429 -> "请求过于频繁，请稍后再试";
                        case 502 -> "模型服务暂时不可用";
                        default -> "模型调用异常 (" + response.statusCode() + "): " + truncate(errorBody);
                    };
                    onEvent.accept(new LLMEvent.Error(uiMsg));
                    onDone.run();
                    return;
                }
                log.info("LLM stream response: status={}", response.statusCode());

                ToolStream toolStream = new ToolStream(onEvent);
                // 响应头已就绪：body 阶段用定时中断兜底读取超时（每轮取消，避免残留中断）
                readTimeoutFuture = READ_TIMEOUT_SCHEDULER.schedule(
                        Thread.currentThread()::interrupt, streamReadTimeoutSeconds, TimeUnit.SECONDS);
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith(LlmApiConstants.SSE_DATA_PREFIX)) {
                            String data = line.substring(LlmApiConstants.SSE_DATA_PREFIX.length()).trim();
                            if (LlmApiConstants.SSE_DONE_MARKER.equals(data)) break;
                            chunkCount++;
                            try {
                                JsonNode chunk = JsonUtils.parse(data, JsonNode.class);
                                if (chunk != null && chunk.has(LlmApiConstants.FIELD_CHOICES)
                                        && chunk.get(LlmApiConstants.FIELD_CHOICES).isArray()
                                        && chunk.get(LlmApiConstants.FIELD_CHOICES).size() > 0) {
                                    JsonNode choice = chunk.get(LlmApiConstants.FIELD_CHOICES).get(0);
                                    if (choice.has(LlmApiConstants.FIELD_FINISH_REASON)
                                            && !choice.get(LlmApiConstants.FIELD_FINISH_REASON).isNull()) {
                                        log.debug("LLM finish_reason: {}", choice.get(LlmApiConstants.FIELD_FINISH_REASON).asText());
                                    }
                                    JsonNode delta = choice.get(LlmApiConstants.FIELD_DELTA);
                                    if (delta != null) {
                                        if (delta.has(LlmApiConstants.FIELD_REASONING_CONTENT)
                                                && delta.get(LlmApiConstants.FIELD_REASONING_CONTENT).isTextual()) {
                                            String r = delta.get(LlmApiConstants.FIELD_REASONING_CONTENT).asText();
                                            if (!r.isEmpty()) {
                                                reasoningBuilder.append(r);
                                                onEvent.accept(new LLMEvent.ReasoningDelta(r));
                                            }
                                        }
                                        if (delta.has(LlmApiConstants.FIELD_CONTENT)
                                                && delta.get(LlmApiConstants.FIELD_CONTENT).isTextual()) {
                                            String c = delta.get(LlmApiConstants.FIELD_CONTENT).asText();
                                            if (!c.isEmpty()) {
                                                contentBuilder.append(c);
                                                onEvent.accept(new LLMEvent.TextDelta(c));
                                            }
                                        }
                                        chatResponseAdapter.adaptResponse(choice, toolStream, company);
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                toolStream.finishAll();

                if (toolStream.isEmpty()) {
                    ContentToolCallResult ctcr = chatResponseAdapter.extractToolCalls(contentBuilder.toString(), company);
                    if (ctcr.isEmpty()) {
                        ctcr = chatResponseAdapter.extractToolCalls(reasoningBuilder.toString(), company);
                    }
                    if (!ctcr.isEmpty()) {
                        contentBuilder.setLength(0);
                        if (ctcr.cleanedContent() != null) {
                            contentBuilder.append(ctcr.cleanedContent());
                        }
                        for (ContentToolCallResult.ContentToolCall tc : ctcr.toolCalls()) {
                            String callId = LlmApiConstants.PARSED_CALL_ID_PREFIX + System.nanoTime();
                            onEvent.accept(new LLMEvent.ToolCall(callId, tc.name(), tc.arguments()));
                        }
                    }
                }

                Map<String, Object> usage = Map.of();
                String content = contentBuilder.toString();
                String reasoning = reasoningBuilder.toString();
                log.info("LLM stream completed: model={}, chunks={}, contentLen={}, reasoningLen={}",
                        modelName, chunkCount, content.length(), reasoning.length());
                onEvent.accept(new LLMEvent.Finish(LlmApiConstants.FINISH_REASON_STOP, usage));
                onDone.run();
                if (readTimeoutFuture != null) {
                    readTimeoutFuture.cancel(false);
                }
                return;
            } catch (Exception e) {
                boolean userTimedOut = Thread.interrupted();
                if (readTimeoutFuture != null) {
                    readTimeoutFuture.cancel(false);
                }
                // 传输层瞬时失败（EOF/断连等，未收到任何分片）→ 重试；超时/取消不重试
                if (attempt < streamMaxRetries && chunkCount == 0 && !userTimedOut) {
                    log.warn("LLM stream failed, will retry: model={}, timedOut={}, chunks={}, err={}",
                            modelName, userTimedOut, chunkCount, e.toString());
                    continue;
                }
                log.error("LLM stream failed: model={}, timedOut={}, chunks={}, retried={}",
                        modelName, userTimedOut, chunkCount, attempt, e);
                // 显式报错而非静默空结果，供上层路由降级/任务重试感知
                onEvent.accept(new LLMEvent.Error(userTimedOut
                        ? "模型流式读取超时（已重试）"
                        : "上游连接中断（chunks=0），重试 " + attempt + " 次后仍失败"));
                onDone.run();
                return;
            }
        }
        onDone.run();
    }

    private String truncate(String body) {
        if (body == null || body.isEmpty() || logBodyMaxLength <= 0 || body.length() <= logBodyMaxLength) return body;
        return body.substring(0, logBodyMaxLength) + "...<truncated>";
    }

}
