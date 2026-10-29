package com.buukle.agent.runtime.orchestration.pipeline;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.dtvo.vo.BuiltinToolVO;
import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.FinishConstants;
import com.buukle.agent.runtime.kernel.constants.ToolResultReadConstants;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.capability.cli.dtvo.vo.CliVO;
import com.buukle.agent.capability.cli.spi.CapabilityCliSpi;
import com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.sub.agent.ToolRefs;
import com.buukle.agent.instance.dtvo.vo.CapabilityFullVO;
import com.buukle.agent.instance.dtvo.vo.CapabilityVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.InstanceCapabilitySpi;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.runner.sub.SubAgentConstants;
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

    private static final String WAIT_PARAM_SCHEMA = "{\"type\":\"object\",\"properties\":{\"ms\":{\"type\":\"integer\",\"minimum\":100,\"maximum\":600000,\"description\":\"Milliseconds to wait (100-600000). The run suspends immediately and resumes automatically afterwards. Use it to give dynamic content time to appear or settle.\"}},\"required\":[\"ms\"],\"additionalProperties\":false}";
    private static final String FINISH_PARAM_SCHEMA = "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"success\",\"failed\",\"blocked\"],\"description\":\"success=work done and verified; failed=tried but could not complete; blocked=cannot proceed without something external (login/permission/missing data)\"},\"summary\":{\"type\":\"string\",\"description\":\"Complete, self-contained answer for the user; the run's final reply is taken from it\"}},\"required\":[\"status\",\"summary\"],\"additionalProperties\":false}";
    private final InstanceCapabilitySpi capabilitySpi;
    private final CapabilityMcpSpi mcpSpi;
    private final CapabilitySkillSpi skillSpi;
    private final CapabilityCliSpi cliSpi;
    private final CapabilityBuiltinSpi builtinSpi;
    private final AgentRuntimeProperties properties;

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
        // 启用 delegate 时注册唯一执行入口 pseudo-tool：skill 本体不再注册为 tool，
        // 由主 agent 通过 delegate 自行决定内联执行（main）或隔离子 Agent（subagent）。
        if (properties.getDelegate().isEnabled()) {
            result.add(RuntimeTool.builder()
                    .capabilityType(SubAgentConstants.CAPABILITY_TYPE_SUB_AGENT)
                    .llmToolName(SubAgentConstants.DELEGATE_TOOL)
                    .toolRef(ToolRefs.agent(SubAgentConstants.DELEGATE_TOOL))
                    .displayName("委派子 Agent").displayNameCn("委派子 Agent").displayNameEn("Delegate")
                    .description("The only execution entry for delegated work. Use mode=main to execute inline in the current loop "
                            + "(no sub-agent), or mode=subagent to isolate work in a sub-agent. Provide optional agentRef=instance:<id> "
                            + "to prefix the sub-agent system prompt, or tasks[] for a DAG of parallel sub-agents (requires mode=subagent).")
                    .parametersSchemaJson(SubAgentConstants.DELEGATE_SCHEMA)
                    .execBinding(Map.of()).build());
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
        // 默认注册 wait：挂起-恢复型工具，全局可用（不需要用户在场，故无 noClarification 豁免）。
        // 形态照 ask_clarification：无 SPI bean、无 DB seed，直接注册。
        {
            long waitId = BuiltinToolEnum.WAIT.getId();
            Map<String, Object> waitBinding = new HashMap<>();
            waitBinding.put(ExecBindingKeys.BUILTIN_INTERNAL_NAME, WaitConstants.INTERNAL_NAME);
            result.add(RuntimeTool.builder()
                    .capabilityType(CAPABILITY_TYPE_BUILTIN)
                    .capabilityId(waitId)
                    .llmToolName(LLM_PREFIX_BUILTIN + waitId)
                    .toolRef(ToolRefs.builtin(WaitConstants.INTERNAL_NAME))
                    .displayName(WaitConstants.DISPLAY_NAME)
                    .displayNameCn(WaitConstants.DISPLAY_NAME_CN)
                    .displayNameEn(WaitConstants.DISPLAY_NAME)
                    .description(WaitConstants.DESCRIPTION)
                    .parametersSchemaJson(WAIT_PARAM_SCHEMA)
                    .execBinding(waitBinding).build());
        }
        // 默认注册 read_tool_result：回读被上下文预算省略的历史浏览器/工具结果。
        if (capabilities != null && !capabilities.isEmpty()) {
            long readId = BuiltinToolEnum.READ_TOOL_RESULT.getId();
            Map<String, Object> readBinding = new HashMap<>();
            readBinding.put(ExecBindingKeys.BUILTIN_INTERNAL_NAME, ToolResultReadConstants.INTERNAL_NAME);
            result.add(RuntimeTool.builder()
                    .capabilityType(CAPABILITY_TYPE_BUILTIN)
                    .capabilityId(readId)
                    .llmToolName(LLM_PREFIX_BUILTIN + readId)
                    .toolRef(ToolRefs.builtin(ToolResultReadConstants.INTERNAL_NAME))
                    .displayName(ToolResultReadConstants.DISPLAY_NAME)
                    .displayNameCn(ToolResultReadConstants.DISPLAY_NAME_CN)
                    .displayNameEn(ToolResultReadConstants.DISPLAY_NAME)
                    .description(ToolResultReadConstants.DESCRIPTION)
                    .parametersSchemaJson(ToolResultReadConstants.PARAM_SCHEMA)
                    .execBinding(readBinding).build());
        }
        // 默认注册 finish：agentic run 的唯一合法结束方式。
        // 「没有工具调用」既可能是做完、也可能是放弃，只靠沉默无法区分 —— finish 让它变成显式声明。
        // 纯聊天实例（无任何真实能力且非自主任务）不下发，避免模型用 terse summary 顶替正常回答；
        // 运行期还会用「是否真的用过工具」再兜一层（SessionRunner）。
        if (noClarification || (capabilities != null && !capabilities.isEmpty())) {
            long finishId = BuiltinToolEnum.FINISH.getId();
            Map<String, Object> finishBinding = new HashMap<>();
            finishBinding.put(ExecBindingKeys.BUILTIN_INTERNAL_NAME, FinishConstants.INTERNAL_NAME);
            result.add(RuntimeTool.builder()
                    .capabilityType(CAPABILITY_TYPE_BUILTIN)
                    .capabilityId(finishId)
                    .llmToolName(LLM_PREFIX_BUILTIN + finishId)
                    .toolRef(ToolRefs.builtin(FinishConstants.INTERNAL_NAME))
                    .displayName(FinishConstants.DISPLAY_NAME)
                    .displayNameCn(FinishConstants.DISPLAY_NAME_CN)
                    .displayNameEn(FinishConstants.DISPLAY_NAME)
                    .description(FinishConstants.DESCRIPTION)
                    .parametersSchemaJson(FINISH_PARAM_SCHEMA)
                    .execBinding(finishBinding).build());
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
