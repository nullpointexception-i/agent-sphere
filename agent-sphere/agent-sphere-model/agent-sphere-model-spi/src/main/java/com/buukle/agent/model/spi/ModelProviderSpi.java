package com.buukle.agent.model.spi;

import com.buukle.agent.model.dtvo.dto.CreateModelProviderDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.complete.LLMEvent;
import com.buukle.agent.model.dtvo.vo.ModelProviderVO;

import java.util.List;
import java.util.function.Consumer;

public interface ModelProviderSpi {
    ModelProviderVO createProvider(CreateModelProviderDTO dto);
    ModelProviderVO getProvider(Long id);
    List<ModelProviderVO> listProviders(String keyword);
    long countProviders();
    ModelProviderVO updateProvider(Long id, CreateModelProviderDTO dto);
    void deleteProvider(Long id);
    void setActiveKey(Long id, Long apiKeyId);

    /**
     * Streaming LLM call with structured LLMEvent emission.
     * Emits TextDelta, ReasoningDelta, ToolInputStart/Delta/End, ToolCall events during streaming.
     * Calls onDone when the stream completes.
     */
    void stream(String company, String baseUrl, String apiKey, String modelName,
                ChatCompletionRequestDTO request,
                Consumer<LLMEvent> onEvent, Runnable onDone);
}
