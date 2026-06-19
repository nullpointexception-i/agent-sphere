package com.buukle.agent.model.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.model.domain.AgentModelProvider;
import com.buukle.agent.model.service.constants.LlmApiConstants;
import com.buukle.agent.model.dtvo.dto.CreateModelProviderDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.vo.ModelProviderVO;
import com.buukle.agent.model.exception.ModelErrorCode;
import com.buukle.agent.model.repository.ModelProviderMapper;
import com.buukle.agent.model.service.ModelProviderService;
import com.buukle.agent.model.service.converter.ModelProviderConverter;
import com.buukle.agent.model.service.helper.ChatRequestAdapter;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
@Primary
public class ModelProviderServiceImpl extends ServiceImpl<ModelProviderMapper, AgentModelProvider> implements ModelProviderService {
    private final ModelProviderConverter modelProviderConverter;
    private final AgentRuntimeProperties properties;
    private final HttpClient httpClient;
    private final int logBodyMaxLength;

    public ModelProviderServiceImpl(ModelProviderConverter modelProviderConverter,
                                    AgentRuntimeProperties properties,
                                    @Value("${buukle.agent.llm.log-body-max-length:2000}") int logBodyMaxLength) {
        this.modelProviderConverter = modelProviderConverter;
        this.properties = properties;
        this.logBodyMaxLength = logBodyMaxLength;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getLlm().getConnectTimeout())
                .build();
    }

    @Autowired
    ChatRequestAdapter chatRequestAdapter;

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
        streamEvents(baseUrl, apiKey, modelName, requestBody, onEvent, onDone);
    }

    private void streamEvents(String baseUrl, String apiKey, String modelName, String requestBody,
                              Consumer<LLMEvent> onEvent, Runnable onDone) {
        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        int chunkCount = 0;
        try {
            String url = baseUrl.endsWith("/") ? baseUrl + LlmApiConstants.CHAT_COMPLETIONS_PATH : baseUrl + LlmApiConstants.CHAT_COMPLETIONS_PATH;
            log.info("LLM stream request: model={}, url={}, body={}", modelName, url, truncate(requestBody));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header(LlmApiConstants.HEADER_CONTENT_TYPE, LlmApiConstants.APPLICATION_JSON)
                    .header(LlmApiConstants.HEADER_AUTHORIZATION, LlmApiConstants.BEARER_PREFIX + apiKey)
                    .header(LlmApiConstants.HEADER_ACCEPT, LlmApiConstants.TEXT_EVENT_STREAM)
                    .timeout(properties.getLlm().getStreamReadTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                String errorBody = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                log.error("LLM stream error: status={}, body={}", response.statusCode(), errorBody);
                onDone.run();
                return;
            }
            log.info("LLM stream response: status={}", response.statusCode());

            ToolStream toolStream = new ToolStream(onEvent);
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
                                if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                                    log.debug("LLM finish_reason: {}", choice.get("finish_reason").asText());
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
                                    if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
                                        for (JsonNode tc : delta.get("tool_calls")) {
                                            int index = tc.has("index") ? tc.get("index").asInt() : 0;
                                            String tcId = tc.has("id") ? tc.get("id").asText() : null;
                                            String tcName = null;
                                            String tcArgs = null;
                                            if (tc.has("function")) {
                                                JsonNode fn = tc.get("function");
                                                if (fn.has("name") && !fn.get("name").isNull()) tcName = fn.get("name").asText();
                                                if (fn.has("arguments")) tcArgs = fn.get("arguments").asText();
                                            }
                                            toolStream.onDelta(index, tcId, tcName, tcArgs);
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            toolStream.finishAll();
            Map<String, Object> usage = Map.of();
            String content = contentBuilder.toString();
            String reasoning = reasoningBuilder.toString();
            log.info("LLM stream completed: model={}, chunks={}, contentLen={}, reasoningLen={}",
                modelName, chunkCount, content.length(), reasoning.length());
            onEvent.accept(new LLMEvent.Finish("stop", usage));
        } catch (Exception e) {
            log.error("LLM stream failed: model={}, chunks={}, content={}, reasoning={}",
                modelName, chunkCount, truncate(contentBuilder.toString()), truncate(reasoningBuilder.toString()), e);
        } finally {
            onDone.run();
        }
    }

    private String truncate(String body) {
        if (body == null || body.isEmpty() || logBodyMaxLength <= 0 || body.length() <= logBodyMaxLength) return body;
        return body.substring(0, logBodyMaxLength) + "...<truncated>";
    }

}
