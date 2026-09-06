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
import com.buukle.agent.capability.skill.dtvo.enums.SkillCapabilityEnum;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;
import com.buukle.agent.common.skill.SkillDefinition;
import com.buukle.agent.common.skill.SkillDefinitionParser;
import com.buukle.agent.common.skill.ToolRefs;
import com.buukle.agent.instance.dtvo.vo.CapabilityFullVO;
import com.buukle.agent.instance.dtvo.vo.CapabilityVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.InstanceCapabilitySpi;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
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

    private static final String DEFAULT_EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";
    private static final String CLARIFICATION_PARAM_SCHEMA = "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\",\"description\":\"Question or prompt for the user (clear, friendly, as if speaking to them)\"},\"type\":{\"type\":\"string\",\"enum\":[\"confirm\",\"choice\",\"input\"],\"description\":\"confirm=yes/no; choice=provide 2-5 concise distinct options; input=free-form answer\"},\"options\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":5,\"items\":{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\",\"description\":\"Short display text shown to the user (max ~20 chars, a concise option phrase, NOT a full sentence, NOT placeholder like 'http://')\"},\"value\":{\"type\":\"string\",\"description\":\"Machine value sent back to the agent when the user picks this option\"},\"description\":{\"type\":\"string\",\"description\":\"Optional short explanation for the option\"}},\"required\":[\"label\"],\"additionalProperties\":false},\"description\":\"2-5 concise, distinct choices (required when type=choice). No duplicates, no placeholder URLs, no full sentences.\"},\"description\":{\"type\":\"string\",\"description\":\"Additional context or explanation to help the user understand the question\"}},\"required\":[\"title\",\"type\"]}";

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

    public KernelContext prepare(RunVO run, ValidationResult validated, String message) {
        List<CapabilityVO> capabilities = capabilitySpi.getCapabilitiesByInstance(
                validated.getAgentInstance().getId());
        List<CapabilityFullVO> fullCapabilities = resolveFullCapabilities(capabilities);
        List<RuntimeTool> tools = resolveTools(capabilities, validated,
                Boolean.TRUE.equals(run.getNoClarification()));

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
    private List<RuntimeTool> resolveTools(List<CapabilityVO> capabilities, ValidationResult validated, boolean noClarification) {
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
                    .toolRef(ToolRefs.builtin(tool.getName()))
                    .displayName(tool.getDisplayNameCn())
                    .displayNameCn(tool.getDisplayNameCn())
                    .displayNameEn(tool.getDisplayNameEn())
                    .description(tool.getDescription() != null ? tool.getDescription() : "")
                    .parametersSchemaJson(tool.getParamSchema())
                    .execBinding(binding).build());
        }
        // 默认注册 ask_clarification；自主任务（noClarification）场景剔除，禁止模型向用户提问
        if (!noClarification) {
            long clarificationId = BuiltinToolEnum.ASK_CLARIFICATION.getId();
            String clarificationLlmName = LLM_PREFIX_BUILTIN + clarificationId;
            Map<String, Object> clarificationBinding = new HashMap<>();
            clarificationBinding.put(ExecBindingKeys.BUILTIN_INTERNAL_NAME, ChatClarification.INTERNAL_NAME);
            result.add(RuntimeTool.builder()
                    .capabilityType(CAPABILITY_TYPE_BUILTIN)
                    .capabilityId(clarificationId)
                    .llmToolName(clarificationLlmName)
                    .toolRef(ToolRefs.builtin(ChatClarification.INTERNAL_NAME))
                    .displayName(ChatClarification.DISPLAY_NAME)
                    .displayNameCn(ChatClarification.DISPLAY_NAME_CN)
                    .displayNameEn(ChatClarification.DISPLAY_NAME)
                    .description(ChatClarification.DESCRIPTION)
                    .parametersSchemaJson(CLARIFICATION_PARAM_SCHEMA)
                    .execBinding(clarificationBinding).build());
        }
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
                    .toolRef(ToolRefs.builtin(tool.getName()))
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
                .toolRef(ToolRefs.cli(cap.getCapabilityId()))
                .displayName(cli.getName())
                .description("")
                .parametersSchemaJson(cli.getParamSchema())
                .execBinding(binding).build());
    }

    void resolveSkillTool(SkillVO skill, CapabilityVO cap, List<RuntimeTool> result) {
        if (!SkillCapabilityEnum.STATUS_ENABLED.equals(skill.getStatus())) {
            log.warn("Skill {} is disabled, skipping", skill.getId());
            return;
        }
        String llmName = LLM_PREFIX_SKILL + skill.getId();
        SkillDefinition def;
        try {
            def = SkillDefinitionParser.parse(skill.getDefinition());
        } catch (com.buukle.agent.common.skill.InvalidSkillDefinition e) {
            log.warn("Skill {} definition invalid, skipping: {}", skill.getId(), e.getMessage());
            return;
        }
        if (def == null) {
            return;
        }
        Map<String, Object> binding = new HashMap<>();
        binding.put(ExecBindingKeys.SKILL_PROMPT_TEMPLATE, def.promptTemplate());
        binding.put(ExecBindingKeys.SKILL_ALLOW_TOOLS, def.allowTools());
        result.add(RuntimeTool.builder()
                .capabilityType(CAPABILITY_TYPE_SKILL)
                .capabilityId(cap.getCapabilityId())
                .llmToolName(llmName)
                .toolRef(ToolRefs.skill(skill.getId()))
                .displayName(skill.getName())
                .description(skill.getDescription() != null ? skill.getDescription() : "")
                .parametersSchemaJson(enrichSkillSchema(def.parametersSchemaJson()))
                .execBinding(binding).build());
    }

    private static final String SKILL_TASK_PARAM_SCHEMA = """
            {"type":"object","additionalProperties":true,"properties":{
              "input":{"type":"string","description":"调用方给定的原始任务指令/任务配置全文（含【任务配置】结构化 JSON 时保留原样），skill 子 Agent 据此自抽取 channel_url/keywords/城市/学历/年限/薪资/评估阈值等任务参数"},
              "channel_url":{"type":"string","description":"渠道 BOSS直聘牛人搜索地址 https://www.zhipin.com/web/chat/search（任务只允许在该地址内完成，禁止单独打开 /web/frame/search/）"},
              "keywords":{"type":"array","items":{"type":"string"},"description":"候选检索关键词列表（如 流程管理、IPD、LTC、ITR、变革管理、端到端流程）"},
              "city":{"type":"string","description":"期望工作城市（Step2 城市控件需设置的目标城市）"},
              "years":{"type":"string","description":"期望工作年限档（如 3-5年，Step4 年限筛选）"},
              "degree":{"type":"string","description":"期望学历（如 本科及以上，Step4 学历筛选）"},
              "salary":{"type":"string","description":"期望薪资区间（如 20K 以上，Step4 薪资筛选）"},
              "industry":{"type":"string","description":"期望行业（如 物流/电商/供应链）"},
              "evaluate_rule":{"type":"string","description":"候选人评分阈值/规则（如 匹配度>=75% 才收藏）"}},"description":"Skill 任务执行参数：由调用方上下文提供；传入原始任务配置全文给 input 即可，其余字段按语义辅助模型抽取"}""";

    /**
     * Skill 工具的入参 schema：默认的空 schema（绝大多数 skill 定义都是空 properties）
     * 替换为「input/任务参数」通道 schema，避免 LLM 调用 skill 时 argumentsJson 恒为 {}、
     * 子 Agent 拿不到任务；非空 schema 保持定义原样（允许 skill 自定义入参契约）。
     */
    private String enrichSkillSchema(String originalSchema) {
        if (originalSchema != null && !originalSchema.isBlank() && !DEFAULT_EMPTY_SCHEMA.equals(originalSchema)) {
            return originalSchema;
        }
        return SKILL_TASK_PARAM_SCHEMA;
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
                            .toolRef(ToolRefs.mcp(cap.getCapabilityId(), tool.getName()))
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
                .toolRef(ToolRefs.mcp(cap.getCapabilityId(), mcp.getName()))
                .displayName(mcp.getName())
                .description(mcp.getDescription() != null ? mcp.getDescription() : "")
                .parametersSchemaJson(DEFAULT_EMPTY_SCHEMA)
                .execBinding(binding).build());
    }
}
