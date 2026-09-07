package com.buukle.agent.runtime.kernel.skill;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.sub.agent.InvalidSubRunDefinition;
import com.buukle.agent.common.skill.SkillPromptRenderer;
import com.buukle.agent.common.sub.agent.ToolRefs;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.SubRunExecutionContext;
import com.buukle.agent.runtime.kernel.port.SubRunPolicy;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Skill 子 Agent 策略：宿主配置（SkillConfig）+ 渲染（{{input}} 注入 + SkillPromptRenderer）
 * + 白名单 + 协议命名/文案。
 *
 * <p>协议 wire 值（nodeName "skill:"、哨兵 "▶ Skill "、publishId "skill-"、agentType "SKILL"、
 * ToolRefs.skill、LlmInteractionType.SKILL_EXECUTION）与历史一致，保持前端路由与事件总线兼容。
 */
@Component
@RequiredArgsConstructor
public class SkillSubRunPolicy implements SubRunPolicy {

    private final AgentRuntimeProperties properties;

    private static final String NODE_NAME_PREFIX = "skill:";
    private static final String MARKER_PREFIX = "▶ Skill ";
    private static final String PUBLISH_ID_PREFIX = "skill-";

    @Override
    public boolean isEnabled() {
        return properties.getSkill().isExecutionEnabled();
    }

    @Override
    public int maxNestedDepth() {
        return properties.getSkill().getMaxNestedDepth();
    }

    @Override
    public int maxSubLoopCount() {
        return properties.getSkill().getMaxSubLoopCount();
    }

    @Override
    public Duration executionTimeout() {
        return properties.getSkill().getExecutionTimeout();
    }

    @Override
    public int maxPromptChars() {
        return properties.getSkill().getMaxPromptChars();
    }

    @Override
    public int maxResultChars() {
        return properties.getSkill().getMaxResultChars();
    }

    @Override
    public long toolId(RuntimeTool tool) {
        String ref = tool != null ? tool.getToolRef() : null;
        if (ref != null && ref.startsWith(ToolRefs.TYPE_SKILL + ToolRefs.SEPARATOR)) {
            try {
                return Long.parseLong(ref.substring(ToolRefs.TYPE_SKILL.length() + 1));
            } catch (NumberFormatException ignored) {
                // fallthrough
            }
        }
        return tool != null && tool.getCapabilityId() != null ? tool.getCapabilityId() : -1L;
    }

    @Override
    public String nodeNamePrefix() {
        return NODE_NAME_PREFIX;
    }

    @Override
    public String markerPrefix() {
        return MARKER_PREFIX;
    }

    @Override
    public String publishIdPrefix() {
        return PUBLISH_ID_PREFIX;
    }

    @Override
    public String agentType() {
        return "SKILL";
    }

    @Override
    public String toolRef(long toolId) {
        return ToolRefs.skill(toolId);
    }

    @Override
    public LlmInteractionType interactionType() {
        return LlmInteractionType.SKILL_EXECUTION;
    }

    @Override
    public String displayName(RuntimeTool tool) {
        return tool != null && tool.getDisplayName() != null ? tool.getDisplayName() : String.valueOf(toolId(tool));
    }

    @Override
    public boolean hasPromptTemplate(RuntimeTool tool) {
        return StringUtils.hasText(bindingString(tool));
    }

    @Override
    public String renderPrompt(RuntimeTool tool, String argsJson, SubRunExecutionContext ctx)
            throws InvalidSubRunDefinition {
        String template = bindingString(tool);
        String effectiveArgs = effectiveArgsJson(ctx, argsJson);
        String rendered = SkillPromptRenderer.render(template, effectiveArgs);
        int max = maxPromptChars();
        return rendered != null && rendered.length() > max ? rendered.substring(0, max) : rendered;
    }

    @Override
    public Set<String> allowedRefs(RuntimeTool tool, SubRunExecutionContext parentCtx) {
        Set<String> own = bindingAllowTools(tool);
        if (parentCtx.getInheritedAllowedToolRefs() == null) {
            return own;
        }
        Set<String> result = new HashSet<>();
        for (String ref : own) {
            if (parentCtx.getInheritedAllowedToolRefs().contains(ref)) {
                result.add(ref);
            }
        }
        return result;
    }

    @Override
    public PreparedSubRun prepare(RuntimeTool tool, long toolId, SubRunExecutionContext parentCtx) {
        int depth = parentCtx.getSkillDepth() + 1;
        if (depth > maxNestedDepth()) {
            return new PreparedSubRun(errorDepthExceeded(), null, depth);
        }
        if (parentCtx.getSkillStack().contains(toolId)) {
            return new PreparedSubRun(errorRecursive(toolId), null, depth);
        }
        List<Long> childStack = new ArrayList<>(parentCtx.getSkillStack());
        childStack.add(toolId);
        SubRunExecutionContext childCtx = parentCtx.child(depth, childStack,
                allowedRefs(tool, parentCtx), parentCtx.getParentToolCallId());
        return new PreparedSubRun(null, childCtx, depth);
    }

    private String bindingString(RuntimeTool tool) {
        Object v = tool != null && tool.getExecBinding() != null
                ? tool.getExecBinding().get(ExecBindingKeys.SKILL_PROMPT_TEMPLATE) : null;
        return v == null ? "" : String.valueOf(v);
    }

    private Set<String> bindingAllowTools(RuntimeTool tool) {
        Set<String> refs = new HashSet<>();
        Object raw = tool != null && tool.getExecBinding() != null
                ? tool.getExecBinding().get(ExecBindingKeys.SKILL_ALLOW_TOOLS) : null;
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    refs.add(String.valueOf(o));
                }
            }
        }
        return refs;
    }

    /**
     * 子 Agent 实际入参：把根上下文的本轮用户消息全文作为 {@code {{input}}} 合并进入参，
     * 其余 {@code {{path}}} 仍从 argumentsJson 解析（缺失回填 [缺参数:...] 标记）。
     */
    private String effectiveArgsJson(SubRunExecutionContext parentCtx, String argsJson) {
        KernelContext kernelContext = parentCtx != null ? parentCtx.getKernelContext() : null;
        String userMessage = kernelContext != null ? kernelContext.getUserMessage() : null;
        if (userMessage == null || userMessage.isBlank()) {
            RunVO run = kernelContext != null ? kernelContext.getRun() : null;
            if (run != null) {
                userMessage = run.getUserMessage();
            }
        }
        if (userMessage == null || userMessage.isBlank()) {
            return argsJson;
        }
        StringBuilder merged = new StringBuilder();
        merged.append('{');
        String argsBody = null;
        if (argsJson != null && !argsJson.isBlank() && !RunnerConstants.EMPTY_JSON_ARGS.equals(argsJson)) {
            String trimmed = argsJson.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                argsBody = trimmed.substring(1, trimmed.length() - 1);
            }
        }
        if (argsBody != null && !argsBody.isBlank()) {
            merged.append(argsBody.trim()).append(',');
        }
        merged.append("\"input\":\"").append(escapeJson(userMessage)).append('"');
        merged.append('}');
        return merged.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public String errorDisabled() {
        return "{\"error\":\"Skill execution disabled\"}";
    }

    @Override
    public String errorDepthExceeded() {
        return "{\"error\":\"Skill max nested depth exceeded (" + properties.getSkill().getMaxNestedDepth() + ")\"}";
    }

    @Override
    public String errorRecursive(long toolId) {
        return "{\"error\":\"Skill recursive call detected: skill " + toolId + "\"}";
    }

    @Override
    public String errorMissingPrompt() {
        return "{\"error\":\"Skill definition missing promptTemplate\"}";
    }

    @Override
    public String errorRenderFailed(String message) {
        return "{\"error\":\"Skill prompt render failed: " + message + "\"}";
    }

    @Override
    public String errorCancelled() {
        return "{\"error\":\"Skill cancelled\"}";
    }

    @Override
    public String errorTimeout() {
        return "{\"error\":\"Skill execution timeout\"}";
    }

    @Override
    public String errorExecutionFailed(String detail) {
        return "{\"error\":\"Skill execution failed: " + detail + "\"}";
    }

    @Override
    public String errorNotAllowed(String toolName) {
        return "{\"error\":\"tool not allowed by skill allowTools: " + toolName + "\"}";
    }

    @Override
    public String textStarted(String displayName, int depth) {
        return "⚙️ 技能 " + displayName + " 开始执行（深度 " + depth + "）…";
    }

    @Override
    public String textTimeout(String displayName) {
        return "⏱️ 技能 " + displayName + " 超时终止";
    }

    @Override
    public String textFailed(String displayName) {
        return "❌ 技能 " + displayName + " 执行失败";
    }

    @Override
    public String textCompleted(String displayName) {
        return "✅ 技能 " + displayName + " 完成";
    }

    @Override
    public String textSubLoopCapped(String displayName) {
        return "⏹️ 技能 " + displayName + " 达到子循环上限";
    }
}