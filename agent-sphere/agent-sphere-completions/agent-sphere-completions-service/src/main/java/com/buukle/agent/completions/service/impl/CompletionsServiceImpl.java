package com.buukle.agent.completions.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.completions.domain.AgentCompletions;
import com.buukle.agent.completions.domain.AgentCompletionsCall;
import com.buukle.agent.completions.domain.AgentCompletionsPrompt;
import com.buukle.agent.completions.dtvo.ChatCompletionsResp;
import com.buukle.agent.completions.dtvo.CompletionsCallVO;
import com.buukle.agent.completions.dtvo.CompletionsInput;
import com.buukle.agent.completions.dtvo.CompletionsConfigDTO;
import com.buukle.agent.completions.dtvo.CompletionsPromptVO;
import com.buukle.agent.completions.dtvo.CompletionsVO;
import com.buukle.agent.completions.dtvo.CreateCompletionsDTO;
import com.buukle.agent.completions.dtvo.enums.CompletionsEnum;
import com.buukle.agent.completions.exception.CompletionsErrorCode;
import com.buukle.agent.completions.repository.CompletionsMapper;
import com.buukle.agent.completions.repository.CompletionsPromptMapper;
import com.buukle.agent.completions.service.CompletionsCallService;
import com.buukle.agent.completions.service.CompletionsPromptService;
import com.buukle.agent.completions.service.CompletionsService;
import com.buukle.agent.model.dtvo.dto.complete.ChatCompletionRequestDTO;
import com.buukle.agent.model.dtvo.dto.complete.ThinkingDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import com.buukle.agent.sso.spi.CallerAuth;
import com.buukle.agent.sso.spi.ResolvedIdentityVO;
import com.buukle.agent.sso.spi.SsoIdentitySpi;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CompletionsServiceImpl implements CompletionsService {

    private static final Pattern FIELD_PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");
    private static final String INPUT_HOLDER = "{{input}}";
    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String OUTPUT_SCHEMA_HINT = "\n\n请严格按照以下 JSON Schema 输出最终结果（只输出符合 schema 的 JSON，不要额外说明）：\n";
    private static final String RESPONSE_FORMAT_TYPE_JSON_OBJECT = "json_object";
    private static final String JSON_SCHEMA_NAME = "output";
    private static final String THINKING_TYPE_ENABLED = "enabled";
    private static final String THINKING_TYPE_DISABLED = "disabled";
    private static final String BOOLEAN_STRING_TRUE = "true";
    private static final String BOOLEAN_STRING_FALSE = "false";

    private final CompletionsMapper completionsMapper;
    private final CompletionsPromptMapper promptMapper;
    private final CompletionsPromptService completionsPromptService;
    private final CompletionsCallService completionsCallService;
    private final RouteListBuilder routeListBuilder;
    private final ApiKeySpi apiKeySpi;
    private final KernelLlmService llmService;
    private final SsoIdentitySpi ssoIdentitySpi;

    @Value("${hri-ai.completions.call-timeout:60s}")
    private Duration callTimeout;

    @Override
    public ChatCompletionsResp execute(CallerAuth auth, CompletionsInput input) {
        ResolvedIdentityVO identity = resolveCallerIdentity(auth);
        AgentCompletions c = completionsMapper.selectOne(
                new LambdaQueryWrapper<AgentCompletions>()
                        .eq(AgentCompletions::getBusinessType, auth.businessType())
                        .eq(AgentCompletions::getCreatedBy, identity.getUsername())
                        .eq(AgentCompletions::getStatus, CompletionsEnum.STATUS_ACTIVE)
                        .last("LIMIT 1"));
        if (c == null) {
            throw new BizException(CompletionsErrorCode.COMPLETIONS_NOT_FOUND);
        }
        if (c.getModelRouteId() == null) {
            throw new BizException(CompletionsErrorCode.NO_MODEL_ROUTE);
        }
        AgentCompletionsPrompt prompt = c.getActivePromptId() == null ? null : promptMapper.selectById(c.getActivePromptId());
        if (prompt == null) {
            throw new BizException(CompletionsErrorCode.NO_ACTIVE_PROMPT);
        }

        Map<String, Object> inputMap = input == null ? Map.of() : input.getValues();
        List<ChatMessageDTO> messages = renderPrompt(prompt, inputMap, c.getOutputSchema());
        List<ModelRouteFullVO> routes = routeListBuilder.fromRouteId(c.getModelRouteId());
        if (routes.isEmpty()) {
            throw new BizException(CompletionsErrorCode.NO_MODEL_ROUTE);
        }

        String content = null;
        String model = null;
        Map<String, Object> usage = null;
        String lastError = null;
        for (ModelRouteFullVO route : routes) {
            if (route.getApiKeyId() == null) {
                lastError = "route " + route.getId() + " has no apiKey";
                continue;
            }
            String apiKey = apiKeySpi.getApiKeyValue(route.getApiKeyId());
            if (!StringUtils.hasText(apiKey)) {
                lastError = "route " + route.getId() + " apiKey is empty";
                continue;
            }
            ChatCompletionRequestDTO request = new ChatCompletionRequestDTO();
            request.setModel(route.getModelName());
            request.setMessages(messages);
            applyResponseFormat(request, c.getOutputSchema());
            applyConfig(request, c.getConfig());
            KernelLlmService.InvokeResult result;
            try {
                result = llmService.invokeSync(
                        route.getCompany(), route.getBaseUrl(), apiKey, route.getModelName(),
                        request,
                        new LlmInteractionMeta(null, null, LlmInteractionType.COMPLETIONS),
                        callTimeout.getSeconds());
            } catch (Exception e) {
                lastError = e.getMessage();
                continue;
            }
            if (result.isSuccess()) {
                content = result.getContent();
                model = route.getModelName();
                usage = result.getUsage();
                break;
            }
            lastError = result.getError();
        }
        if (content == null) {
            throw new BizException(CompletionsErrorCode.LLM_CALL_FAILED,
                    lastError == null ? "all routes failed" : lastError);
        }

        AgentCompletionsCall call = new AgentCompletionsCall();
        call.setCompletionsId(c.getId());
        call.setPromptId(prompt.getId());
        call.setInput(input == null ? null : JsonUtils.toJson(inputMap));
        call.setOutput(content);
        call.setModel(model);
        call.setUsage(usage == null ? null : JsonUtils.toJson(usage));
        call.setStatus(CompletionsEnum.CALL_STATUS_SUCCESS);
        call.setCaller(identity.getUsername());
        completionsCallService.record(call);

        ChatCompletionsResp resp = new ChatCompletionsResp();
        resp.setContent(content);
        resp.setModel(model);
        resp.setUsage(usage);
        return resp;
    }

    @Override
    @Transactional
    public CompletionsVO create(CreateCompletionsDTO dto) {
        AgentCompletions c = new AgentCompletions();
        c.setName(dto.getName());
        c.setDescription(dto.getDescription());
        c.setModelRouteId(dto.getModelRouteId());
        c.setInputSchema(dto.getInputSchema());
        c.setOutputSchema(dto.getOutputSchema());
        c.setConfig(dto.getConfig());
        c.setRemark(dto.getRemark());
        c.setBusinessType(dto.getBusinessType());
        c.setStatus(CompletionsEnum.STATUS_ACTIVE);
        completionsMapper.insert(c);
        return toVO(c, List.of());
    }

    @Override
    @Transactional
    public CompletionsVO update(Long id, CreateCompletionsDTO dto) {
        AgentCompletions c = completionsMapper.selectById(id);
        if (c == null) {
            throw new BizException(CompletionsErrorCode.COMPLETIONS_NOT_FOUND);
        }
        if (dto.getName() != null) {
            c.setName(dto.getName());
        }
        if (dto.getDescription() != null) {
            c.setDescription(dto.getDescription());
        }
        if (dto.getModelRouteId() != null) {
            c.setModelRouteId(dto.getModelRouteId());
        }
        if (dto.getInputSchema() != null) {
            c.setInputSchema(dto.getInputSchema());
        }
        if (dto.getOutputSchema() != null) {
            c.setOutputSchema(dto.getOutputSchema());
        }
        if (dto.getConfig() != null) {
            c.setConfig(dto.getConfig());
        }
        if (dto.getRemark() != null) {
            c.setRemark(dto.getRemark());
        }
        if (dto.getBusinessType() != null) {
            c.setBusinessType(dto.getBusinessType());
        }
        completionsMapper.updateById(c);
        return toVO(c, completionsPromptService.listByCompletions(id));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        AgentCompletions c = completionsMapper.selectById(id);
        if (c == null) {
            throw new BizException(CompletionsErrorCode.COMPLETIONS_NOT_FOUND);
        }
        completionsMapper.deleteById(id);
    }

    @Override
    public Page<CompletionsVO> list(String keyword, LocalDateTime startTime, LocalDateTime endTime, int page, int size) {
        LambdaQueryWrapper<AgentCompletions> qw = new LambdaQueryWrapper<AgentCompletions>()
                .like(StringUtils.hasText(keyword), AgentCompletions::getName, keyword)
                .ge(startTime != null, AgentCompletions::getCreatedAt, startTime)
                .le(endTime != null, AgentCompletions::getCreatedAt, endTime)
                .orderByDesc(AgentCompletions::getId);
        var mpPage = completionsMapper.selectPage(new Page<>(page, size), qw);
        var voPage = new Page<CompletionsVO>(mpPage.getCurrent(), mpPage.getSize(), mpPage.getTotal());
        voPage.setRecords(mpPage.getRecords().stream().map(c -> toVO(c, null)).toList());
        return voPage;
    }

    @Override
    public CompletionsVO detail(Long id) {
        AgentCompletions c = completionsMapper.selectById(id);
        if (c == null) {
            throw new BizException(CompletionsErrorCode.COMPLETIONS_NOT_FOUND);
        }
        return toVO(c, completionsPromptService.listByCompletions(id));
    }

    @Override
    public Page<CompletionsCallVO> listCalls(Long id, int page, int size) {
        return completionsCallService.pageByCompletions(id, page, size);
    }

    /** 解析外部调用方身份：code+subject 反查 SSO identity，失败 → 401。 */
    private ResolvedIdentityVO resolveCallerIdentity(CallerAuth auth) {
        ResolvedIdentityVO identity = ssoIdentitySpi.resolveByCodeSubject(auth.code(), auth.subject());
        if (identity == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return identity;
    }

    private List<ChatMessageDTO> renderPrompt(AgentCompletionsPrompt prompt, Map<String, Object> input, String outputSchema) {
        List<ChatMessageDTO> messages = new ArrayList<>();
        String system = prompt.getPromptSystem() == null ? "" : prompt.getPromptSystem();
        if (StringUtils.hasText(outputSchema)) {
            system += OUTPUT_SCHEMA_HINT + outputSchema;
        }
        if (StringUtils.hasText(system)) {
            messages.add(new ChatMessageDTO().setRole(ROLE_SYSTEM).setContent(system));
        }
        String user = prompt.getPromptUser() == null ? "" : prompt.getPromptUser();
        if (user.contains(INPUT_HOLDER)) {
            user = user.replace(INPUT_HOLDER, input == null ? "{}" : JsonUtils.toJson(input));
        }
        user = renderFieldPlaceholders(user, input);
        messages.add(new ChatMessageDTO().setRole(ROLE_USER).setContent(user));
        return messages;
    }

    /** 若配置了 outputSchema，设置 response_format=json_schema / object（模型不支持时 LLM 层仍会按 prompt 提示输出）。 */
    private void applyResponseFormat(ChatCompletionRequestDTO request, String outputSchema) {
        if (!StringUtils.hasText(outputSchema)) {
            return;
        }
        try {
            com.buukle.agent.model.dtvo.dto.complete.ResponseFormatDTO responseFormat =
                    new com.buukle.agent.model.dtvo.dto.complete.ResponseFormatDTO();
            responseFormat.setType(RESPONSE_FORMAT_TYPE_JSON_OBJECT);
            com.buukle.agent.model.dtvo.dto.complete.JsonSchemaDTO jsonSchema =
                    new com.buukle.agent.model.dtvo.dto.complete.JsonSchemaDTO();
            jsonSchema.setName(JSON_SCHEMA_NAME);
            jsonSchema.setSchema(JsonUtils.getMapper().readTree(outputSchema));
            responseFormat.setJson_schema(jsonSchema);
            request.setResponseFormat(responseFormat);
        } catch (Exception e) {
            // schema 解析失败：忽略 response_format，仅靠 prompt 提示
        }
    }

    /** 解析 agent_completions.config，把支持的参数映射到请求（temperature/thinking/max_tokens/top_p/penalties/stop）。 */
    private void applyConfig(ChatCompletionRequestDTO request, String configJson) {
        if (!StringUtils.hasText(configJson)) {
            return;
        }
        try {
            CompletionsConfigDTO cfg = JsonUtils.getMapper().readValue(configJson, CompletionsConfigDTO.class);
            if (cfg == null) {
                return;
            }
            if (cfg.getTemperature() != null) {
                request.setTemperature(cfg.getTemperature());
            }
            if (cfg.getMaxTokens() != null) {
                request.setMaxTokens(cfg.getMaxTokens());
            }
            if (cfg.getTopP() != null) {
                request.setTopP(cfg.getTopP());
            }
            if (cfg.getPresencePenalty() != null) {
                request.setPresencePenalty(cfg.getPresencePenalty());
            }
            if (cfg.getFrequencyPenalty() != null) {
                request.setFrequencyPenalty(cfg.getFrequencyPenalty());
            }
            if (cfg.getStop() != null && !cfg.getStop().isEmpty()) {
                request.setStop(cfg.getStop());
            }
            String thinkingValue = StringUtils.hasText(cfg.getThinking()) ? cfg.getThinking() : cfg.getReasoning();
            if (StringUtils.hasText(thinkingValue)) {
                String type = resolveThinkingType(thinkingValue);
                if (StringUtils.hasText(type)) {
                    request.setThinking(new ThinkingDTO().setType(type));
                }
            }
        } catch (Exception e) {
            // config 解析失败：忽略，保持默认
        }
    }

    private String resolveThinkingType(String value) {
        if (THINKING_TYPE_ENABLED.equalsIgnoreCase(value) || THINKING_TYPE_DISABLED.equalsIgnoreCase(value)) {
            return value.toLowerCase();
        }
        if (BOOLEAN_STRING_TRUE.equalsIgnoreCase(value)) {
            return THINKING_TYPE_ENABLED;
        }
        if (BOOLEAN_STRING_FALSE.equalsIgnoreCase(value)) {
            return THINKING_TYPE_DISABLED;
        }
        return null;
    }

    private String renderFieldPlaceholders(String text, Map<String, Object> input) {
        if (text == null || input == null || !text.contains("{{")) {
            return text;
        }
        Matcher m = FIELD_PLACEHOLDER.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Object val = resolvePath(input, m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(val == null ? "" : String.valueOf(val)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Object resolvePath(Map<String, Object> map, String path) {
        Object cur = map;
        for (String part : path.split("\\.")) {
            if (cur instanceof Map<?, ?> m && m.containsKey(part)) {
                cur = m.get(part);
            } else {
                return null;
            }
        }
        return cur;
    }

    private CompletionsVO toVO(AgentCompletions c, List<CompletionsPromptVO> prompts) {
        CompletionsVO vo = new CompletionsVO();
        vo.setId(c.getId());
        vo.setName(c.getName());
        vo.setDescription(c.getDescription());
        vo.setModelRouteId(c.getModelRouteId());
        vo.setActivePromptId(c.getActivePromptId());
        vo.setInputSchema(c.getInputSchema());
        vo.setOutputSchema(c.getOutputSchema());
        vo.setConfig(c.getConfig());
        vo.setStatus(c.getStatus());
        vo.setRemark(c.getRemark());
        vo.setBusinessType(c.getBusinessType());
        vo.setCreatedBy(c.getCreatedBy());
        vo.setPrompts(prompts);
        vo.setCreatedAt(c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        vo.setUpdatedAt(c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
        return vo;
    }
}
