package com.buukle.agent.runtime.kernel.runner.sub;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.sub.agent.InvalidSubRunDefinition;
import com.buukle.agent.common.sub.agent.ToolRefs;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import com.buukle.agent.runtime.kernel.port.SubRunExecutionContext;
import com.buukle.agent.runtime.kernel.port.SubRunPolicy;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一 delegate 子 Agent 策略：宿主配置（{@code buukle.agent.delegate}）+ 中性协议命名/文案。
 *
 * <p>子 Agent 完全继承父 Agent 的资源（工具集、模型路由），仅通过 agentRef 叠加
 * instance 的 systemPrompt+customInstructions。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubAgentPolicy implements SubRunPolicy {

    private static final String NODE_NAME_PREFIX = "agent:";
    private static final String MARKER_PREFIX = "▶ Agent ";
    private static final String PUBLISH_ID_PREFIX = "agent-";
    private static final String AGENT_TYPE = "AGENT";

    private static final String PROMPT_ARGS_SEPARATOR = "\n\nArgs: ";
    private static final String EMPTY_ARGS = "{}";
    /**
     * P0 信封契约前言：机读说明上游输入的位置与形状，要求原文引用，消除
     * “框架信封字段 vs 消费端探针字段”口径不一致。
     */
    private static final String ENVELOPE_PREAMBLE = "UPSTREAM INPUTS: Your upstream lane results (if any) are under "
            + "CONTACT_ENVELOPE.upstream.<depKey> as complete result JSON. "
            + "The fields upstream_visible (boolean) and upstream_raw (exact JSON text) are computed by the "
            + "framework — quote upstream_raw verbatim when reporting upstream values, do not invent them. "
            + "If upstream_visible is false, report verdict UPSTREAM_MISSING instead of guessing.";

    private final AgentRuntimeProperties properties;

    @Override
    public boolean isEnabled() {
        return properties.getDelegate().isEnabled();
    }

    @Override
    public int maxNestedDepth() {
        return properties.getDelegate().getMaxNestedDepth();
    }

    @Override
    public int maxSubLoopCount() {
        return properties.getDelegate().getMaxSubLoopCount();
    }

    @Override
    public Duration executionTimeout() {
        return properties.getDelegate().getExecutionTimeout();
    }

    @Override
    public int maxPromptChars() {
        return properties.getDelegate().getMaxPromptChars();
    }

    public int maxEnvelopeChars() {
        return properties.getDelegate().getMaxEnvelopeChars();
    }

    @Override
    public int maxResultChars() {
        return properties.getDelegate().getMaxResultChars();
    }

    @Override
    public long toolId(RuntimeTool tool) {
        String ref = tool != null ? tool.getToolRef() : null;
        if (ref != null && ref.startsWith(ToolRefs.TYPE_AGENT + ToolRefs.SEPARATOR)) {
            String suffix = ref.substring(ToolRefs.TYPE_AGENT.length() + 1);
            try {
                return Long.parseLong(suffix);
            } catch (NumberFormatException ignored) {
                // 非数字 key：回退稳定 hash
            }
        }
        return ref != null ? Math.abs((long) ref.hashCode()) : -1L;
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
        return AGENT_TYPE;
    }

    @Override
    public String toolRef(long toolId) {
        return ToolRefs.agent(String.valueOf(toolId));
    }

    @Override
    public LlmInteractionType interactionType() {
        return LlmInteractionType.AGENT_EXECUTION;
    }

    @Override
    public String displayName(RuntimeTool tool) {
        return tool != null && StringUtils.hasText(tool.getDisplayName())
                ? tool.getDisplayName() : String.valueOf(toolId(tool));
    }

    @Override
    public String systemPrompt(RuntimeTool tool) {
        Object v = tool != null && tool.getExecBinding() != null
                ? tool.getExecBinding().get(ExecBindingKeys.DELEGATE_SYSTEM) : null;
        return v == null ? null : String.valueOf(v);
    }

    @Override
    public String renderPrompt(RuntimeTool tool, String argsJson, SubRunExecutionContext ctx)
            throws InvalidSubRunDefinition {
        String goal = bindingString(tool, ExecBindingKeys.DELEGATE_INSTRUCTION);
        if (!StringUtils.hasText(goal)) {
            throw new InvalidSubRunDefinition("delegate goal is required");
        }
        String effectiveArgs = StringUtils.hasText(argsJson) ? argsJson.trim() : EMPTY_ARGS;
        String head = goal + PROMPT_ARGS_SEPARATOR + effectiveArgs;
        // P0 信封免截断：信封按自有预算截断上游 value（标 truncated），正文只截 head，永不砍信封
        String envelopePart = "";
        ContactEnvelope envelope = ContactEnvelope.parse(bindingString(tool, ExecBindingKeys.DELEGATE_CONTACT));
        if (envelope != null && envelope.hasData()) {
            ContactEnvelope budgeted = envelope.truncateUpstream(maxEnvelopeChars());
            envelopePart = "\n\n" + ENVELOPE_PREAMBLE + "\n\nCONTACT_ENVELOPE: " + budgeted.serialize();
            log.debug("Sub-run prompt envelope: laneKey={} bytes={} truncated={}",
                    budgeted.laneKey(), budgeted.serialize().length(), budgeted.isTruncated());
        }
        int max = maxPromptChars();
        if (head.length() + envelopePart.length() <= max) {
            return head + envelopePart;
        }
        int headBudget = Math.max(0, max - envelopePart.length());
        return head.substring(0, Math.min(head.length(), headBudget)) + envelopePart;
    }

    @Override
    public String contactEnvelope(RuntimeTool tool, String argsJson) {
        ContactEnvelope envelope = ContactEnvelope.parse(bindingString(tool, ExecBindingKeys.DELEGATE_CONTACT));
        return envelope != null ? envelope.serialize() : null;
    }

    @Override
    public PreparedSubRun prepare(RuntimeTool tool, long toolId, SubRunExecutionContext parentCtx) {
        int depth = parentCtx.getDepth() + 1;
        if (depth > maxNestedDepth()) {
            return new PreparedSubRun(errorDepthExceeded(), null, depth);
        }
        String ref = tool != null ? tool.getToolRef() : null;
        List<String> ancestry = parentCtx.getToolRefAncestry();
        if (ref != null && ancestry.contains(ref)) {
            return new PreparedSubRun(errorRecursive(toolId), null, depth);
        }
        List<String> childAncestry = new ArrayList<>(ancestry);
        if (ref != null) {
            childAncestry.add(ref);
        }
        SubRunExecutionContext childCtx = parentCtx.child(depth, childAncestry,
                parentCtx.getParentToolCallId(), parentCtx.getOwnerSubAgentRunId());
        return new PreparedSubRun(null, childCtx, depth);
    }

    private String bindingString(RuntimeTool tool, String key) {
        Object v = tool != null && tool.getExecBinding() != null ? tool.getExecBinding().get(key) : null;
        return v == null ? "" : String.valueOf(v);
    }

    @Override
    public String errorDisabled() {
        return "{\"error\":\"Sub-agent execution disabled\"}";
    }

    @Override
    public String errorDepthExceeded() {
        return "{\"error\":\"Sub-agent max nested depth exceeded (" + maxNestedDepth() + ")\"}";
    }

    @Override
    public String errorRecursive(long toolId) {
        return "{\"error\":\"Sub-agent recursive call detected: " + toolId + "\"}";
    }

    @Override
    public String errorMissingPrompt() {
        return "{\"error\":\"Delegate goal is required\"}";
    }

    @Override
    public String errorRenderFailed(String message) {
        return "{\"error\":\"Delegate prompt render failed: " + message + "\"}";
    }

    @Override
    public String errorCancelled() {
        return "{\"error\":\"Sub-agent cancelled\"}";
    }

    @Override
    public String errorTimeout() {
        return "{\"error\":\"Sub-agent execution timeout\"}";
    }

    @Override
    public String errorExecutionFailed(String detail) {
        return "{\"error\":\"Sub-agent execution failed: " + detail + "\"}";
    }

    @Override
    public String errorNotAllowed(String toolName) {
        return "{\"error\":\"tool not available to sub-agent: " + toolName + "\"}";
    }

    @Override
    public String textStarted(String displayName, int depth) {
        // 注意：不得以 markerPrefix 开头，避免前端把“开始执行”系统提示误判为新子 Agent 段
        return "⚙️ 子 Agent " + displayName + " 开始执行（深度 " + depth + "）…";
    }

    @Override
    public String textTimeout(String displayName) {
        return "⏱️ 子 Agent " + displayName + " 超时终止";
    }

    @Override
    public String textFailed(String displayName) {
        return "❌ 子 Agent " + displayName + " 执行失败";
    }

    @Override
    public String textCompleted(String displayName) {
        return "✅ 子 Agent " + displayName + " 完成";
    }

    @Override
    public String textSubLoopCapped(String displayName) {
        return "⏹️ 子 Agent " + displayName + " 达到子循环上限";
    }
}
