package com.buukle.agent.runtime.orchestration.pipeline;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.dtvo.vo.BuiltinToolVO;
import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.FinishConstants;
import com.buukle.agent.runtime.kernel.constants.ProgressConstants;
import com.buukle.agent.runtime.kernel.constants.ToolResultReadConstants;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.capability.cli.dtvo.vo.CliVO;
import com.buukle.agent.capability.cli.spi.CapabilityCliSpi;
import com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.capability.mcp.spi.McpHubSpi;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;
import com.buukle.agent.capability.skill.spi.SkillHubSpi;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.constant.CompletionProtocol;
import com.buukle.agent.common.constant.RunMode;
import com.buukle.agent.common.constant.ToolAccessMode;
import com.buukle.agent.common.sub.agent.ToolRefs;
import com.buukle.agent.instance.dtvo.vo.CapabilityFullVO;
import com.buukle.agent.instance.dtvo.vo.CapabilityVO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.enums.SessionType;
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
    /**
     * Hub MCP ids share a sequence with local MCP ids in a tenant, so the LLM-facing tool name
     * offsets hub ids to avoid collisions. Runtime capabilityType stays "mcp" for ToolExecutor.
     */
    private static final long MCP_HUB_ID_BASE = 1_000_000_000L;
    private static final String CLARIFICATION_PARAM_SCHEMA = "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\",\"description\":\"Question or prompt for the user (clear, friendly, as if speaking to them)\"},\"type\":{\"type\":\"string\",\"enum\":[\"confirm\",\"choice\",\"input\"],\"description\":\"confirm=yes/no; choice=provide 2-5 concise distinct options; input=free-form answer\"},\"multiple\":{\"type\":\"boolean\",\"description\":\"For type=choice: true when the user may select more than one option.\"},\"options\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":5,\"items\":{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\",\"description\":\"Short display text shown to the user (max ~20 chars, a concise option phrase, NOT a full sentence, NOT placeholder like 'http://')\"},\"value\":{\"type\":\"string\",\"description\":\"Machine value sent back to the agent when the user picks this option\"},\"description\":{\"type\":\"string\",\"description\":\"Optional short explanation for the option\"}},\"required\":[\"label\"],\"additionalProperties\":false},\"description\":\"2-5 concise, distinct choices (required when type=choice). No duplicates, no placeholder URLs, no full sentences.\"},\"description\":{\"type\":\"string\",\"description\":\"Additional context or explanation to help the user understand the question\"},\"targetUserId\":{\"type\":\"integer\",\"description\":\"Optional. Ask a specific project member instead of the current user. The run pauses until that member answers or the question expires.\"},\"expiresInHours\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":168,\"description\":\"Optional. Hours before a targeted question expires (default 24).\"}},\"required\":[\"title\",\"type\"]}";

    private static final String WAIT_PARAM_SCHEMA = "{\"type\":\"object\",\"properties\":{\"ms\":{\"type\":\"integer\",\"minimum\":100,\"maximum\":600000,\"description\":\"Milliseconds to wait (100-600000). The run suspends immediately and resumes automatically afterwards. Use it to give dynamic content time to appear or settle.\"}},\"required\":[\"ms\"],\"additionalProperties\":false}";
    private static final String FINISH_PARAM_SCHEMA = "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"success\",\"failed\",\"blocked\"],\"description\":\"success=work done and verified; failed=tried but could not complete; blocked=cannot proceed without something external (login/permission/missing data)\"},\"summary\":{\"type\":\"string\",\"description\":\"Complete, self-contained answer for the user; the run's final reply is taken from it\"}},\"required\":[\"status\",\"summary\"],\"additionalProperties\":false}";
    private static final String PROGRESS_PARAM_SCHEMA = "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1000,\"description\":\"Meaningful intermediate milestone\"},\"nextAction\":{\"type\":\"string\",\"maxLength\":500,\"description\":\"What the agent will do next\"},\"percent\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100,\"description\":\"Optional approximate completion percentage\"}},\"required\":[\"summary\"],\"additionalProperties\":false}";
    private static final Set<String> READ_ONLY_CONTROL_TOOLS = Set.of(
            LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId(),
            LLM_PREFIX_BUILTIN + BuiltinToolEnum.FINISH.getId(),
            LLM_PREFIX_BUILTIN + BuiltinToolEnum.ASK_CLARIFICATION.getId(),
            LLM_PREFIX_BUILTIN + BuiltinToolEnum.WAIT.getId(),
            LLM_PREFIX_BUILTIN + BuiltinToolEnum.PROGRESS.getId(),
            // Insight writing is an analysis side channel, not a project-data mutation, so a
            // READ_ONLY analysis run may still persist its findings.
            LLM_PREFIX_BUILTIN + BuiltinToolEnum.PROJECT_INSIGHT.getId(),
            SubAgentConstants.DELEGATE_TOOL);
    private static final Set<Long> ROUTER_TOOL_IDS = Set.of(
            (long) BuiltinToolEnum.DISPATCH_WORKER.getId(),
            (long) BuiltinToolEnum.SEND_MESSAGE.getId(),
            (long) BuiltinToolEnum.RECALL_EVENT.getId(),
            (long) BuiltinToolEnum.SUBSCRIBE_THREAD.getId(),
            (long) BuiltinToolEnum.CLOSE_THREAD.getId(),
            (long) BuiltinToolEnum.WORKER_CAPABILITY.getId());
    private static final Set<Long> ROUTER_ALLOWED_IDS = Set.of(
            (long) BuiltinToolEnum.DISPATCH_WORKER.getId(),
            (long) BuiltinToolEnum.SEND_MESSAGE.getId(),
            (long) BuiltinToolEnum.RECALL_EVENT.getId(),
            (long) BuiltinToolEnum.SUBSCRIBE_THREAD.getId(),
            (long) BuiltinToolEnum.CLOSE_THREAD.getId(),
            (long) BuiltinToolEnum.WORKER_CAPABILITY.getId(),
            (long) BuiltinToolEnum.FINISH.getId(),
            (long) BuiltinToolEnum.ASK_CLARIFICATION.getId(),
            (long) BuiltinToolEnum.CURRENT_TIME.getId(),
            (long) BuiltinToolEnum.READ_TOOL_RESULT.getId(),
            // L2 write tools: can be triggered live in a Router DM session (docwrite / skill_manage),
            // hitting SessionRunner's L2 approval interceptor → TOOL_APPROVAL_PENDING → ROUTER_APPROVAL card delivered to IM.
            (long) BuiltinToolEnum.DOCWRITE.getId(),
            (long) BuiltinToolEnum.SKILL_MANAGE.getId());
    /** Tools the tenant project assistant always receives, even if the DB binding is missing. */
    private static final Set<Long> PROJECT_TOOL_IDS = Set.of(
            (long) BuiltinToolEnum.PROJECT_QUERY.getId(),
            (long) BuiltinToolEnum.PROJECT_MUTATE.getId(),
            (long) BuiltinToolEnum.PROJECT_AVATAR.getId(),
            (long) BuiltinToolEnum.PROJECT_INSIGHT.getId());
    private final InstanceCapabilitySpi capabilitySpi;
    private final CapabilityMcpSpi mcpSpi;
    private final McpHubSpi mcpHubSpi;
    private final CapabilitySkillSpi skillSpi;
    private final SkillHubSpi skillHubSpi;
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

    /**
     * Preview the tools an instance would receive, for Router → Worker feasibility checks.
     * Mirrors the Worker dispatch context: no clarification tool and no run-mode filtering.
     */
    public List<RuntimeTool> previewTools(InstanceVO instance) {
        if (instance == null || instance.getId() == null) {
            return List.of();
        }
        List<CapabilityVO> capabilities = capabilitySpi.getCapabilitiesByInstance(instance.getId());
        ValidationResult validated = ValidationResult.builder().agentInstance(instance).build();
        // Same as a real Worker session: no clarification tool, and Router-only tools removed.
        return applyRolePolicy(resolveTools(capabilities, validated, true, null), validated);
    }

    public KernelContext prepare(RunVO run, ValidationResult validated, String message) {
        List<CapabilityVO> capabilities = capabilitySpi.getCapabilitiesByInstance(
                validated.getAgentInstance().getId());
        List<CapabilityFullVO> fullCapabilities = resolveFullCapabilities(capabilities);
        List<RuntimeTool> tools = resolveTools(capabilities, validated,
                Boolean.TRUE.equals(run.getNoClarification()), run);
        tools = applyRolePolicy(tools, validated);

        return KernelContext.builder()
                .agentInstance(validated.getAgentInstance())
                .session(validated.getSession())
                .run(run)
                .capabilities(fullCapabilities)
                .tools(tools)
                .modelRoute(validated.getModelRoute())
                .fallbackRoutes(validated.getFallbackRoutes())
                .userMessage(message)
                .reasoningEffort(run.getReasoningEffort())
                .reasoningBudgetTokens(run.getReasoningBudgetTokens())
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
                    case CAPABILITY_TYPE_MCP_HUB -> {
                        // Followed hub MCP: read-only, always resolved from the current hub snapshot.
                        McpVO mcp = mcpHubSpi.getHubMcp(cap.getCapabilityId());
                        if (mcp != null) result.add(buildFull(cap, mcp.getName(), mcp.getDescription(),
                                mcp.getServerUrl(), mcp.getServerType(), mcp.getAuthConfig(), mcp.getToolDefinitions()));
                    }
                    case CAPABILITY_TYPE_SKILL -> {
                        SkillVO skill = skillSpi.getSkill(cap.getCapabilityId());
                        if (skill != null) result.add(buildFull(cap, skill.getName(), skill.getDescription(),
                                null, null, null, skill.getDefinition()));
                    }
                    case CAPABILITY_TYPE_SKILL_HUB -> {
                        // Followed hub skill: read-only, always resolved from the current hub snapshot.
                        SkillVO skill = skillHubSpi.getHubSkill(cap.getCapabilityId());
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

    private List<RuntimeTool> applyRolePolicy(List<RuntimeTool> tools, ValidationResult validated) {
        String sessionType = validated.getSession() == null ? null
                : validated.getSession().getSessionType();
        if (SessionType.GROUP_ROUTER.equals(sessionType)) {
            return List.of();
        }
        if (SessionType.PROJECT_ROUTER.equals(sessionType)) {
            // The project assistant is a focused worker: no wait, no sub-agent delegation,
            // and none of the Super-Agent router tools.
            tools.removeIf(tool -> SubAgentConstants.DELEGATE_TOOL.equals(tool.getLlmToolName())
                    || (CAPABILITY_TYPE_BUILTIN.equals(tool.getCapabilityType())
                    && tool.getCapabilityId() != null
                    && (ROUTER_TOOL_IDS.contains(tool.getCapabilityId())
                    || tool.getCapabilityId() == BuiltinToolEnum.WAIT.getId())));
            // Capability rows may be hidden by a self-only data scope; finish is mandatory for
            // an agentic run, so make sure it is present regardless of the DB binding.
            boolean hasFinish = tools.stream().anyMatch(tool ->
                    CAPABILITY_TYPE_BUILTIN.equals(tool.getCapabilityType())
                            && tool.getCapabilityId() != null
                            && tool.getCapabilityId() == BuiltinToolEnum.FINISH.getId());
            if (!hasFinish) {
                for (BuiltinToolVO tool : builtinSpi.listBuiltinTools()) {
                    if (tool.getId() == BuiltinToolEnum.FINISH.getId()) {
                        tools.add(toRuntimeTool(tool));
                        break;
                    }
                }
            }
            return tools;
        }
        if (SessionType.ROUTER.equals(sessionType)) {
            List<RuntimeTool> filtered = new ArrayList<>();
            Set<Long> present = new HashSet<>();
            for (RuntimeTool tool : tools) {
                if (CAPABILITY_TYPE_BUILTIN.equals(tool.getCapabilityType())
                        && tool.getCapabilityId() != null
                        && ROUTER_ALLOWED_IDS.contains(tool.getCapabilityId())) {
                    filtered.add(tool);
                    present.add(tool.getCapabilityId());
                }
            }
            for (BuiltinToolVO tool : builtinSpi.listBuiltinTools()) {
                long id = tool.getId();
                if (ROUTER_TOOL_IDS.contains(id) && !present.contains(id)) {
                    filtered.add(toRuntimeTool(tool));
                }
            }
            return filtered;
        }
        tools.removeIf(tool -> CAPABILITY_TYPE_BUILTIN.equals(tool.getCapabilityType())
                && tool.getCapabilityId() != null
                && ROUTER_TOOL_IDS.contains(tool.getCapabilityId()));
        return tools;
    }

    private static RuntimeTool toRuntimeTool(BuiltinToolVO tool) {
        String llmName = LLM_PREFIX_BUILTIN + tool.getId();
        Map<String, Object> binding = new HashMap<>();
        binding.put(ExecBindingKeys.BUILTIN_INTERNAL_NAME, tool.getName());
        return RuntimeTool.builder()
                .capabilityType(CAPABILITY_TYPE_BUILTIN)
                .capabilityId(tool.getId())
                .llmToolName(llmName)
                .toolRef(ToolRefs.builtin(tool.getName()))
                .displayName(tool.getDisplayNameCn())
                .displayNameCn(tool.getDisplayNameCn())
                .displayNameEn(tool.getDisplayNameEn())
                .description(tool.getDescription() != null ? tool.getDescription() : "")
                .parametersSchemaJson(tool.getParamSchema())
                .execBinding(binding)
                .compressEnabled(tool.isCompressEnabled())
                .accessMode(tool.getAccessMode())
                .securityLevel(tool.getSecurityLevel())
                .approvalScope(tool.getApprovalScope())
                .build();
    }

    // ---- New RuntimeTool resolution ----
    private List<RuntimeTool> resolveTools(List<CapabilityVO> capabilities, ValidationResult validated,
                                           boolean noClarification, RunVO run) {
        List<RuntimeTool> result = new ArrayList<>();
        resolveCapabilityTools(capabilities, result);
        addAutoIncludeBuiltinTools(result);
        addProjectAssistantTools(validated, result);
        addDelegateTool(result);
        addClarificationTool(noClarification, result);
        addWaitTool(result);
        addReadToolResultTool(capabilities, result);
        addProgressTool(result);
        addFinishTool(capabilities, noClarification, run, result);
        applyReadOnlyFilter(run, result);
        return dedupeByLlmName(result);
    }

    /** Resolves tools bound to the instance capabilities (MCP / CLI / builtin). */
    private void resolveCapabilityTools(List<CapabilityVO> capabilities, List<RuntimeTool> result) {
        for (CapabilityVO cap : capabilities) {
            if (!STATUS_ENABLED.equals(cap.getStatus())) continue;
            try {
                switch (cap.getCapabilityType()) {
                    case CAPABILITY_TYPE_MCP -> {
                        McpVO mcp = mcpSpi.getMcp(cap.getCapabilityId());
                        if (mcp != null) resolveMcpTools(mcp, cap, result);
                    }
                    case CAPABILITY_TYPE_MCP_HUB -> {
                        McpVO mcp = mcpHubSpi.getHubMcp(cap.getCapabilityId());
                        if (mcp != null) resolveMcpHubTools(mcp, cap, result);
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
    }

    /** Builtin tools that every instance gets without an explicit DB binding. */
    private void addAutoIncludeBuiltinTools(List<RuntimeTool> result) {
        for (BuiltinToolVO tool : builtinSpi.listAutoIncludeBuiltinTools()) {
            result.add(toRuntimeTool(tool));
        }
    }

    /**
     * The project assistant must keep working even when its capability rows were not created
     * (tenant upgrade edge case). Bindings are a fast path, not the only path.
     */
    private void addProjectAssistantTools(ValidationResult validated, List<RuntimeTool> result) {
        String sessionType = validated.getSession() == null ? null
                : validated.getSession().getSessionType();
        if (!SessionType.PROJECT_ROUTER.equals(sessionType)) {
            return;
        }
        Set<Long> present = new HashSet<>();
        for (RuntimeTool tool : result) {
            if (CAPABILITY_TYPE_BUILTIN.equals(tool.getCapabilityType()) && tool.getCapabilityId() != null) {
                present.add(tool.getCapabilityId());
            }
        }
        for (BuiltinToolVO tool : builtinSpi.listBuiltinTools()) {
            if (PROJECT_TOOL_IDS.contains(tool.getId()) && !present.contains(tool.getId())) {
                result.add(toRuntimeTool(tool));
            }
        }
    }

    /** Single execution entry for delegated work (inline main loop or isolated sub-agent). */
    private void addDelegateTool(List<RuntimeTool> result) {
        if (!properties.getDelegate().isEnabled()) {
            return;
        }
        result.add(RuntimeTool.builder()
                .capabilityType(SubAgentConstants.CAPABILITY_TYPE_SUB_AGENT)
                .llmToolName(SubAgentConstants.DELEGATE_TOOL)
                .toolRef(ToolRefs.agent(SubAgentConstants.DELEGATE_TOOL))
                .displayName("Delegate sub-agent").displayNameCn("委派子 Agent").displayNameEn("Delegate")
                .description("The only execution entry for delegated work. Use mode=main to execute inline in the current loop "
                        + "(no sub-agent), or mode=subagent to isolate work in a sub-agent. Provide optional agentRef=instance:<id> "
                        + "to prefix the sub-agent system prompt, or tasks[] for a DAG of parallel sub-agents (requires mode=subagent).")
                .parametersSchemaJson(SubAgentConstants.DELEGATE_SCHEMA)
                .execBinding(Map.of()).build());
    }

    /** ask_clarification is omitted for autonomous tasks so the model cannot ask the user questions. */
    private void addClarificationTool(boolean noClarification, List<RuntimeTool> result) {
        if (noClarification) {
            return;
        }
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

    /** wait is global: suspend-resume needs no user presence, hence no noClarification exemption. */
    private void addWaitTool(List<RuntimeTool> result) {
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

    /** read_tool_result reads back historical tool results omitted by the context budget. */
    private void addReadToolResultTool(List<CapabilityVO> capabilities, List<RuntimeTool> result) {
        if (capabilities == null || capabilities.isEmpty()) {
            return;
        }
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

    /** progress reports intermediate progress explicitly instead of disguising it as plain text. */
    private void addProgressTool(List<RuntimeTool> result) {
        long progressId = BuiltinToolEnum.PROGRESS.getId();
        Map<String, Object> progressBinding = new HashMap<>();
        progressBinding.put(ExecBindingKeys.BUILTIN_INTERNAL_NAME, ProgressConstants.INTERNAL_NAME);
        result.add(RuntimeTool.builder()
                .capabilityType(CAPABILITY_TYPE_BUILTIN)
                .capabilityId(progressId)
                .llmToolName(LLM_PREFIX_BUILTIN + progressId)
                .toolRef(ToolRefs.builtin(ProgressConstants.INTERNAL_NAME))
                .displayName(ProgressConstants.DISPLAY_NAME)
                .displayNameCn(ProgressConstants.DISPLAY_NAME_CN)
                .displayNameEn(ProgressConstants.DISPLAY_NAME)
                .description(ProgressConstants.DESCRIPTION)
                .parametersSchemaJson(PROGRESS_PARAM_SCHEMA)
                .execBinding(progressBinding).build());
    }

    /**
     * finish is the only legal way to end an agentic run.
     *
     * <p>The contract requires it unconditionally for STRICT_FINISH runs: a resume path once
     * missed finish because noClarification was lost, leaving the worker spinning.
     */
    private void addFinishTool(List<CapabilityVO> capabilities, boolean noClarification,
                               RunVO run, List<RuntimeTool> result) {
        boolean strictFinish = CompletionProtocol.from(
                run == null ? null : run.getCompletionProtocol()) == CompletionProtocol.STRICT_FINISH;
        if (!noClarification && (capabilities == null || capabilities.isEmpty()) && !strictFinish) {
            return;
        }
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

    /** READ_ONLY runs only keep READ tools plus the explicitly allowed control tools. */
    private void applyReadOnlyFilter(RunVO run, List<RuntimeTool> result) {
        if (run == null || !RunMode.from(run.getRunMode()).readOnly()) {
            return;
        }
        result.removeIf(tool -> !READ_ONLY_CONTROL_TOOLS.contains(tool.getLlmToolName())
                && ToolAccessMode.from(tool.getAccessMode()) != ToolAccessMode.READ);
    }

    private List<RuntimeTool> dedupeByLlmName(List<RuntimeTool> tools) {
        Set<String> seen = new HashSet<>();
        List<RuntimeTool> deduped = new ArrayList<>();
        for (RuntimeTool tool : tools) {
            if (seen.add(tool.getLlmToolName())) {
                deduped.add(tool);
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
                    .execBinding(binding)
                    .compressEnabled(tool.isCompressEnabled())
                    .accessMode(tool.getAccessMode())
                    .securityLevel(tool.getSecurityLevel())
                    .approvalScope(tool.getApprovalScope())
                    .build());
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
                .execBinding(binding)
                .compressEnabled(Boolean.TRUE.equals(cli.getCompressEnabled()))
                .accessMode(cli.getAccessMode())
                .securityLevel(cli.getSecurityLevel())
                .approvalScope(cli.getApprovalScope())
                .build());
    }

    void resolveMcpTools(McpVO mcp, CapabilityVO cap, List<RuntimeTool> result) {
        resolveMcpTools(mcp, cap, result, mcp.getId(), cap.getCapabilityId(), false);
    }

    /** Hub MCPs use an offset id for the LLM tool name so they never collide with local MCP ids. */
    private void resolveMcpHubTools(McpVO mcp, CapabilityVO cap, List<RuntimeTool> result) {
        Long hubId = cap.getCapabilityId() != null ? cap.getCapabilityId() : mcp.getId();
        resolveMcpTools(mcp, cap, result, MCP_HUB_ID_BASE + hubId, cap.getCapabilityId(), true);
    }

    private void resolveMcpTools(McpVO mcp, CapabilityVO cap, List<RuntimeTool> result,
                                 Long llmId, Long capabilityId, boolean fromHub) {
        String llmNamePrefix = LLM_PREFIX_MCP + llmId + "_";
        String capabilityType = fromHub ? CAPABILITY_TYPE_MCP_HUB : CAPABILITY_TYPE_MCP;

        try {
            List<McpToolInfoVO> tools = fromHub
                    ? mcpHubSpi.listHubTools(mcp.getId())
                    : mcpSpi.listMcpTools(mcp.getId());
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
                            .capabilityType(capabilityType)
                            .capabilityId(capabilityId)
                            .llmToolName(llmName)
                            .toolRef(ToolRefs.mcp(capabilityId, tool.getName()))
                            .displayName(tool.getName())
                            .description(tool.getDescription() != null ? tool.getDescription() : "")
                            .parametersSchemaJson(tool.getInputSchema())
                            .execBinding(binding)
                            .compressEnabled(Boolean.TRUE.equals(mcp.getCompressEnabled()))
                            .accessMode(mcp.getAccessMode())
                            .securityLevel(mcp.getSecurityLevel())
                            .approvalScope(mcp.getApprovalScope())
                            .build());
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
                .capabilityType(capabilityType)
                .capabilityId(capabilityId)
                .llmToolName(llmName)
                .toolRef(ToolRefs.mcp(capabilityId, mcp.getName()))
                .displayName(mcp.getName())
                .description(mcp.getDescription() != null ? mcp.getDescription() : "")
                .parametersSchemaJson(DEFAULT_EMPTY_SCHEMA)
                .execBinding(binding)
                .compressEnabled(Boolean.TRUE.equals(mcp.getCompressEnabled()))
                .accessMode(mcp.getAccessMode())
                .securityLevel(mcp.getSecurityLevel())
                .approvalScope(mcp.getApprovalScope())
                .build());
    }
}
