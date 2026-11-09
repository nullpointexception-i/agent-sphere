package com.buukle.agent.runtime.orchestration.controller;

import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.model.dtvo.dto.ModelConnectionTestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ThinkingDTO;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Tests a model provider/key/model tuple without persisting it. */
@RestController
@RequestMapping(ApiPrefix.V1 + "/admin/tenants")
@RequiredArgsConstructor
public class TenantModelTestController extends BaseController {

    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";

    private final KernelLlmService kernelLlmService;

    @RequirePermission(PermissionCodes.ADMIN_TENANT_CREATE)
    @PostMapping("/test-model")
    public ResponseEntity<?> test(@Valid @RequestBody ModelConnectionTestDTO dto) {
        long started = System.currentTimeMillis();
        ChatCompletionRequestDTO request = new ChatCompletionRequestDTO()
                .setModel(dto.getModelName())
                .setStream(false)
                .setTemperature(0.0)
                .setMaxTokens(dto.getMaxTokens() == null ? 8 : dto.getMaxTokens())
                .setThinking(new ThinkingDTO().setType("disabled"))
                .setEnableThinking(false)
                .setMessages(List.of(
                        new ChatMessageDTO().setRole(ROLE_SYSTEM).setContent("Connectivity test."),
                        new ChatMessageDTO().setRole(ROLE_USER).setContent("ping")));
        KernelLlmService.InvokeResult result = kernelLlmService.invokeSync(
                dto.getCompany(), dto.getBaseUrl(), dto.getApiKey(), dto.getModelName(), request,
                new LlmInteractionMeta().setInteractionType(LlmInteractionType.INTENT_CLASSIFICATION));
        long latency = System.currentTimeMillis() - started;
        String message = result.isSuccess()
                ? "Connection succeeded"
                : (result.getError() == null ? "Connection failed" : result.getError());
        return ok(Map.of(
                "ok", result.isSuccess(),
                "latencyMs", latency,
                "message", message));
    }
}
