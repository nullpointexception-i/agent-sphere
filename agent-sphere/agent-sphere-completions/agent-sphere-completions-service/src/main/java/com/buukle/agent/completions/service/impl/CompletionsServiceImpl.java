package com.buukle.agent.completions.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.completions.domain.AgentCompletions;
import com.buukle.agent.completions.domain.AgentCompletionsCall;
import com.buukle.agent.completions.domain.AgentCompletionsPrompt;
import com.buukle.agent.completions.dtvo.ChatCompletionsResp;
import com.buukle.agent.completions.dtvo.CompletionsCallVO;
import com.buukle.agent.completions.dtvo.CompletionsInput;
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
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.runtime.kernel.config.RouteListBuilder;
import com.buukle.agent.runtime.kernel.model.invoke.KernelLlmService;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionMeta;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
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
    private static final String RESPONSE_FORMAT_TYPE_JSON_SCHEMA = "json_schema";

    private final CompletionsMapper completionsMapper;
    private final CompletionsPromptMapper promptMapper;
    private final CompletionsPromptService completionsPromptService;
    private final CompletionsCallService completionsCallService;
    private final RouteListBuilder routeListBuilder;
    private final ApiKeySpi apiKeySpi;
    private final KernelLlmService llmService;

    @Value("${hri-ai.completions.call-timeout:60s}")
    private Duration callTimeout;

    @Override
    public ChatCompletionsResp execute(Long completionsId, CompletionsInput input) {
        AgentCompletions c = completionsMapper.selectById(completionsId);
        if (c == null) {
            throw new BizException(CompletionsErrorCode.COMPLETIONS_NOT_FOUND);
        }
        if (!CompletionsEnum.STATUS_ACTIVE.equals(c.getStatus())) {
            throw new BizException(CompletionsErrorCode.COMPLETIONS_DISABLED);
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
        call.setCompletionsId(completionsId);
        call.setPromptId(prompt.getId());
        call.setInput(input == null ? null : JsonUtils.toJson(inputMap));
        call.setOutput(content);
        call.setModel(model);
        call.setUsage(usage == null ? null : JsonUtils.toJson(usage));
        call.setStatus(CompletionsEnum.CALL_STATUS_SUCCESS);
        call.setCaller(StringUtils.hasText(c.getCreatedBy())
                ? c.getCreatedBy() : CompletionsEnum.CALLER_ANONYMOUS);
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

    /** 若配置了 outputSchema，设置 response_format=json_schema（模型不支持时 LLM 层仍会按 prompt 提示输出）。 */
    private void applyResponseFormat(ChatCompletionRequestDTO request, String outputSchema) {
        if (!StringUtils.hasText(outputSchema)) {
            return;
        }
        try {
            com.buukle.agent.model.dtvo.dto.complete.ResponseFormatDTO responseFormat =
                    new com.buukle.agent.model.dtvo.dto.complete.ResponseFormatDTO();
            responseFormat.setType(RESPONSE_FORMAT_TYPE_JSON_SCHEMA);
            com.buukle.agent.model.dtvo.dto.complete.JsonSchemaDTO jsonSchema =
                    new com.buukle.agent.model.dtvo.dto.complete.JsonSchemaDTO();
            jsonSchema.setName("output");
            jsonSchema.setSchema(JsonUtils.getMapper().readTree(outputSchema));
            responseFormat.setJson_schema(jsonSchema);
            request.setResponseFormat(responseFormat);
        } catch (Exception e) {
            // schema 解析失败：忽略 response_format，仅靠 prompt 提示
        }
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
        vo.setPrompts(prompts);
        vo.setCreatedAt(c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        vo.setUpdatedAt(c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
        return vo;
    }
}
