package com.buukle.agent.runtime.orchestration.pipeline;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.dtvo.vo.BuiltinToolVO;
import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.capability.cli.dtvo.vo.CliVO;
import com.buukle.agent.capability.cli.spi.CapabilityCliSpi;
import com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;
import com.buukle.agent.instance.dtvo.vo.CapabilityFullVO;
import com.buukle.agent.instance.dtvo.vo.CapabilityVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.InstanceCapabilitySpi;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContextPreparer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";
    private static final String CLARIFICATION_PARAM_SCHEMA = "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\",\"description\":\"Question or prompt for the user (be clear and friendly, as if speaking to them)\"},\"type\":{\"type\":\"string\",\"enum\":[\"confirm\",\"choice\",\"input\"],\"description\":\"Type of clarification needed: confirm for yes/no, choice for multiple options, input for free-form answer\"},\"options\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"}},\"description\":\"Choices for the user (required when type=choice)\"}},\"description\":{\"type\":\"string\",\"description\":\"Additional context or explanation to help the user understand the question\"}},\"required\":[\"title\",\"type\"]}";

    private final InstanceCapabilitySpi capabilitySpi;
    private final CapabilityMcpSpi mcpSpi;
    private final CapabilitySkillSpi skillSpi;
    private final CapabilityCliSpi cliSpi;
    private final CapabilityBuiltinSpi builtinSpi;

    private static CapabilityFullVO buildFull(CapabilityVO cap, String name, String description,
                                              String endpoint, String serverType,
                                              String authConfig, String toolSchema) {
        CapabilityFullVO full = new CapabilityFullVO();
        full.setId(cap.getId());
        full.setCapabilityType(cap.getCapabilityType());
        full.setCapabilityId(cap.getCapabilityId());
        full.setStatus(cap.getStatus());
        full.setName(name);
        full.setDescription(description);
        full.setEndpoint(endpoint);
        full.setServerType(serverType);
        full.setAuthConfig(authConfig);
        full.setToolSchema(toolSchema);
        return full;
    }

    static SkillDefinition parseSkillDefinition(String definition) {
        if (definition == null || definition.isBlank()) return null;
        String jsonStr = definition;

        int jsonStart = definition.indexOf(SKILL_MARKDOWN_FENCE);
        if (jsonStart >= 0) {
            int contentStart = jsonStart + SKILL_MARKDOWN_FENCE.length();
            int jsonEnd = definition.indexOf("```", contentStart);
            if (jsonEnd >= 0) {
                jsonStr = definition.substring(contentStart, jsonEnd).trim();
            }
        }

        try {
            JsonNode root = JSON.readTree(jsonStr);
            JsonNode params = root.get(SKILL_DEF_PARAMETERS);
            JsonNode promptTemplate = root.get(SKILL_DEF_PROMPT_TEMPLATE);
            if (params == null || promptTemplate == null) {
                log.warn("Skill definition missing '{}' or '{}'", SKILL_DEF_PARAMETERS, SKILL_DEF_PROMPT_TEMPLATE);
                return null;
            }
            return new SkillDefinition(params.toString(), promptTemplate.asText());
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse skill definition JSON: {}", e.getMessage());
            return null;
        }
    }

    public KernelContext prepare(RunVO run, ValidationResult validated, String message) {
        List<CapabilityVO> capabilities = capabilitySpi.getCapabilitiesByInstance(
                validated.getAgentInstance().getId());
        List<CapabilityFullVO> fullCapabilities = resolveFullCapabilities(capabilities);
        List<RuntimeTool> tools = resolveTools(capabilities, validated);

        return KernelContext.builder()
                .agentInstance(validated.getAgentInstance())
                .session(validated.getSession())
                .run(run)
                .capabilities(fullCapabilities)
                .tools(tools)
                .modelRoute(validated.getModelRoute())
                .fallbackRoutes(validated.getFallbackRoutes())
                .userMessage(message)
                .build();
    }

    // ---- Legacy full capability resolution (for existing prompt builders) ----
    private List<CapabilityFullVO> resolveFullCapabilities(List<CapabilityVO> capabilities) {
        List<CapabilityFullVO> result = new ArrayList<>();
        for (CapabilityVO cap : capabilities) {
            if (!STATUS_ENABLED.equals(cap.getStatus())) continue;
            try {
                switch (cap.getCapabilityType()) {
                    case CAPABILITY_TYPE_MCP -> {
                        McpVO mcp = mcpSpi.getMcp(cap.getCapabilityId());
                        if (mcp != null) result.add(buildFull(cap, mcp.getName(), mcp.getDescription(),
                                mcp.getServerUrl(), mcp.getServerType(), mcp.getAuthConfig(), mcp.getToolDefinitions()));
                    }
                    case CAPABILITY_TYPE_SKILL -> {
                        SkillVO skill = skillSpi.getSkill(cap.getCapabilityId());
                        if (skill != null) result.add(buildFull(cap, skill.getName(), skill.getDescription(),
                                null, null, null, skill.getDefinition()));
                    }
                    case CAPABILITY_TYPE_CLI -> {
                        CliVO cli = cliSpi.getCli(cap.getCapabilityId());
                        if (cli != null) result.add(buildFull(cap, cli.getName(), null,
                                cli.getWorkingDir(), null, null, cli.getParamSchema()));
                    }
                    case CAPABILITY_TYPE_BUILTIN -> {
                        for (var tool : builtinSpi.listBuiltinTools())
                            result.add(buildFull(cap, tool.getName(), tool.getDescription(),
                                    null, null, null, tool.getParamSchema()));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to resolve capability id={} type={}", cap.getId(), cap.getCapabilityType(), e);
            }
        }
        return result;
    }

    // ---- New RuntimeTool resolution ----
    private List<RuntimeTool> resolveTools(List<CapabilityVO> capabilities, ValidationResult validated) {
        List<RuntimeTool> result = new ArrayList<>();
        for (CapabilityVO cap : capabilities) {
            if (!STATUS_ENABLED.equals(cap.getStatus())) continue;
            try {
                switch (cap.getCapabilityType()) {
                    case CAPABILITY_TYPE_MCP -> {
                        McpVO mcp = mcpSpi.getMcp(cap.getCapabilityId());
                        if (mcp != null) resolveMcpTools(mcp, cap, result);
                    }
                    case CAPABILITY_TYPE_SKILL -> {
                        SkillVO skill = skillSpi.getSkill(cap.getCapabilityId());
                        if (skill != null) resolveSkillTool(skill, cap, result);
                    }
                    case CAPABILITY_TYPE_CLI -> {
                        CliVO cli = cliSpi.getCli(cap.getCapabilityId());
                        if (cli != null) resolveCliTool(cli, cap, result);
                    }
                    case CAPABILITY_TYPE_BUILTIN -> resolveBuiltinTool(cap, result);
                }
            } catch (Exception e) {
                log.warn("Failed to resolve RuntimeTool id={} type={}", cap.getId(), cap.getCapabilityType(), e);
            }
        }
        for (BuiltinToolVO tool : builtinSpi.listAutoIncludeBuiltinTools()) {
            String llmName = LLM_PREFIX_BUILTIN + tool.getId();
            Map<String, Object> binding = new HashMap<>();
            binding.put(ExecBindingKeys.BUILTIN_INTERNAL_NAME, tool.getName());
            result.add(RuntimeTool.builder()
                    .capabilityType(CAPABILITY_TYPE_BUILTIN)
                    .capabilityId(tool.getId())
                    .llmToolName(llmName)
                    .displayName(tool.getDisplayNameCn())
                    .displayNameCn(tool.getDisplayNameCn())
                    .displayNameEn(tool.getDisplayNameEn())
                    .description(tool.getDescription() != null ? tool.getDescription() : "")
                    .parametersSchemaJson(tool.getParamSchema())
                    .execBinding(binding).build());
        }
        // Always register ask_clarification
        long clarificationId = BuiltinToolEnum.ASK_CLARIFICATION.getId();
        String clarificationLlmName = LLM_PREFIX_BUILTIN + clarificationId;
        Map<String, Object> clarificationBinding = new HashMap<>();
        clarificationBinding.put(ExecBindingKeys.BUILTIN_INTERNAL_NAME, ChatClarification.INTERNAL_NAME);
        result.add(RuntimeTool.builder()
                .capabilityType(CAPABILITY_TYPE_BUILTIN)
                .capabilityId(clarificationId)
                .llmToolName(clarificationLlmName)
                .displayName(ChatClarification.DISPLAY_NAME)
                .displayNameCn(ChatClarification.DISPLAY_NAME_CN)
                .displayNameEn(ChatClarification.DISPLAY_NAME)
                .description(ChatClarification.DESCRIPTION)
                .parametersSchemaJson(CLARIFICATION_PARAM_SCHEMA)
                .execBinding(clarificationBinding).build());
        Set<String> seen = new HashSet<>();
        List<RuntimeTool> deduped = new ArrayList<>();
        for (RuntimeTool t : result) {
            if (seen.add(t.getLlmToolName())) {
                deduped.add(t);
            }
        }
        return deduped;
    }

    private void resolveBuiltinTool(CapabilityVO cap, List<RuntimeTool> result) {
        for (BuiltinToolVO tool : builtinSpi.listBuiltinTools()) {
            if (!tool.getId().equals(cap.getCapabilityId())) continue;
            String llmName = LLM_PREFIX_BUILTIN + tool.getId();
            Map<String, Object> binding = new HashMap<>();
            binding.put(ExecBindingKeys.BUILTIN_INTERNAL_NAME, tool.getName());
            result.add(RuntimeTool.builder()
                    .capabilityType(CAPABILITY_TYPE_BUILTIN)
                    .capabilityId(cap.getCapabilityId())
                    .llmToolName(llmName)
                    .displayName(tool.getDisplayNameCn())
                    .displayNameCn(tool.getDisplayNameCn())
                    .displayNameEn(tool.getDisplayNameEn())
                    .description(tool.getDescription() != null ? tool.getDescription() : "")
                    .parametersSchemaJson(tool.getParamSchema())
                    .execBinding(binding).build());
        }
    }

    private void resolveCliTool(CliVO cli, CapabilityVO cap, List<RuntimeTool> result) {
        String llmName = LLM_PREFIX_CLI + cli.getId();
        Map<String, Object> binding = new HashMap<>();
        binding.put(ExecBindingKeys.CLI_COMMAND_TEMPLATE, cli.getCommandTemplate());
        binding.put(ExecBindingKeys.CLI_WORKING_DIR, cli.getWorkingDir());
        result.add(RuntimeTool.builder()
                .capabilityType(CAPABILITY_TYPE_CLI)
                .capabilityId(cap.getCapabilityId())
                .llmToolName(llmName)
                .displayName(cli.getName())
                .description("")
                .parametersSchemaJson(cli.getParamSchema())
                .execBinding(binding).build());
    }

    void resolveSkillTool(SkillVO skill, CapabilityVO cap, List<RuntimeTool> result) {
        String llmName = LLM_PREFIX_SKILL + skill.getId();
        SkillDefinition def = parseSkillDefinition(skill.getDefinition());
        if (def == null) {
            log.warn("Skill {} has invalid definition, skipping", skill.getId());
            return;
        }
        Map<String, Object> binding = new HashMap<>();
        binding.put(ExecBindingKeys.SKILL_PROMPT_TEMPLATE, def.promptTemplate);
        result.add(RuntimeTool.builder()
                .capabilityType(CAPABILITY_TYPE_SKILL)
                .capabilityId(cap.getCapabilityId())
                .llmToolName(llmName)
                .displayName(skill.getName())
                .description(skill.getDescription() != null ? skill.getDescription() : "")
                .parametersSchemaJson(def.parametersSchemaJson)
                .execBinding(binding).build());
    }

    void resolveMcpTools(McpVO mcp, CapabilityVO cap, List<RuntimeTool> result) {
        String llmNamePrefix = LLM_PREFIX_MCP + mcp.getId() + "_";

        try {
            List<McpToolInfoVO> tools = mcpSpi.listMcpTools(mcp.getId());
            if (tools != null && !tools.isEmpty()) {
                for (int i = 0; i < tools.size(); i++) {
                    McpToolInfoVO tool = tools.get(i);
                    String llmName = llmNamePrefix + i;
                    Map<String, Object> binding = new HashMap<>();
                    binding.put(ExecBindingKeys.MCP_SERVER_URL, mcp.getServerUrl());
                    binding.put(ExecBindingKeys.MCP_SERVER_TYPE, mcp.getServerType());
                    binding.put(ExecBindingKeys.MCP_AUTH_CONFIG, mcp.getAuthConfig());
                    binding.put(ExecBindingKeys.MCP_NATIVE_TOOL_NAME, tool.getName());
                    result.add(RuntimeTool.builder()
                            .capabilityType(CAPABILITY_TYPE_MCP)
                            .capabilityId(cap.getCapabilityId())
                            .llmToolName(llmName)
                            .displayName(tool.getName())
                            .description(tool.getDescription() != null ? tool.getDescription() : "")
                            .parametersSchemaJson(tool.getInputSchema())
                            .execBinding(binding).build());
                }
                return;
            }
        } catch (Exception e) {
            log.debug("MCP listMcpTools not available yet (Phase 5), using fallback: {}", e.getMessage());
        }

        String llmName = llmNamePrefix + "0";
        Map<String, Object> binding = new HashMap<>();
        binding.put(ExecBindingKeys.MCP_SERVER_URL, mcp.getServerUrl());
        binding.put(ExecBindingKeys.MCP_SERVER_TYPE, mcp.getServerType());
        binding.put(ExecBindingKeys.MCP_AUTH_CONFIG, mcp.getAuthConfig());
        binding.put(ExecBindingKeys.MCP_NATIVE_TOOL_NAME, mcp.getName());
        result.add(RuntimeTool.builder()
                .capabilityType(CAPABILITY_TYPE_MCP)
                .capabilityId(cap.getCapabilityId())
                .llmToolName(llmName)
                .displayName(mcp.getName())
                .description(mcp.getDescription() != null ? mcp.getDescription() : "")
                .parametersSchemaJson(DEFAULT_EMPTY_SCHEMA)
                .execBinding(binding).build());
    }

    record SkillDefinition(String parametersSchemaJson, String promptTemplate) {
    }
}
