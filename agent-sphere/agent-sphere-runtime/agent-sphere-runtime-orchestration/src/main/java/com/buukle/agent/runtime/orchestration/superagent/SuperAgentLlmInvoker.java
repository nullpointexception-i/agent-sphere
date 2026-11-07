package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ThinkingDTO;
import com.buukle.agent.model.dtvo.vo.ModelProviderVO;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.model.spi.ModelProviderSpi;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Single LLM call shared by the Super-Agent routing layer: resolves instance → route → provider → key,
 * forces thinking off and returns the raw text. Both Group Router and Agent Router go through here.
 */
@Service
@RequiredArgsConstructor
public class SuperAgentLlmInvoker {

    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";

    private final InstanceSpi instanceSpi;
    private final RouteSpi routeSpi;
    private final ModelProviderSpi providerSpi;
    private final ApiKeySpi apiKeySpi;
    private final KernelLlmService kernelLlmService;

    public String invoke(Long instanceId, String systemPrompt, String userPrompt, Integer maxTokens,
                         Long sessionId, String sessionType, Long superAgentId, Long threadId,
                         LlmInteractionType interactionType) {
        if (instanceId == null) {
            throw new IllegalArgumentException("router instanceId is required");
        }
        InstanceVO instance = instanceSpi.getInstance(instanceId);
        if (instance == null || instance.getModelRouteId() == null) {
            throw new IllegalStateException("router instance has no model route");
        }
        ModelRouteVO route = routeSpi.getRoute(instance.getModelRouteId());
        if (route == null) {
            throw new IllegalStateException("router model route not found");
        }
        ModelProviderVO provider = providerSpi.getProvider(route.getProviderId());
        if (provider == null || provider.getBaseUrl() == null) {
            throw new IllegalStateException("router provider not found");
        }
        String apiKey = provider.getApiKeyId() == null
                ? null : apiKeySpi.getApiKeyValue(provider.getApiKeyId());

        ChatCompletionRequestDTO requestDTO = new ChatCompletionRequestDTO()
                .setModel(route.getModelName())
                .setStream(true)
                .setTemperature(0.0)
                .setMaxTokens(maxTokens)
                .setThinking(new ThinkingDTO().setType("disabled"))
                .setEnableThinking(false)
                .setReasoningEffort(null)
                .setMessages(List.of(
                        new ChatMessageDTO().setRole(ROLE_SYSTEM).setContent(systemPrompt),
                        new ChatMessageDTO().setRole(ROLE_USER).setContent(userPrompt)));

        KernelLlmService.InvokeResult result = kernelLlmService.invokeSync(
                route.getCompany(), provider.getBaseUrl(), apiKey, route.getModelName(), requestDTO,
                new LlmInteractionMeta()
                        .setSessionId(sessionId)
                        .setSessionType(sessionType)
                        .setSuperAgentId(superAgentId)
                        .setThreadId(threadId)
                        .setInteractionType(interactionType)
                        .setModelRouteId(route.getId()));
        if (!result.isSuccess()) {
            throw new IllegalStateException("router LLM failed: " + result.getError());
        }
        return result.getContent();
    }
}
