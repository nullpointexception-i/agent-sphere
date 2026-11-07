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
 * Unified delegate sub-agent policy: host configuration ({@code buukle.agent.delegate}) + neutral protocol naming/text.
 *
 * <p>A sub-agent fully inherits the parent agent's resources (tool set, model route); only the agentRef's
 * instance systemPrompt+customInstructions are layered on top.
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
     * P0 envelope contract preamble: machine-readable description of where upstream input lives and its shape, requiring verbatim quoting,
     * removing the mismatch between "framework envelope fields vs consumer probe fields".
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
                // Non-numeric key: fall back to a stable hash
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
        // The P0 envelope is exempt from truncation: the envelope truncates upstream values under its own budget (marked truncated); the body only truncates the head and never cuts the envelope
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
        // Note: must not start with markerPrefix, otherwise the frontend misreads the "start executing" system prompt as a new sub-agent segment
        return "⚙️ Sub-agent " + displayName + " started (depth " + depth + ")…";
    }

    @Override
    public String textTimeout(String displayName) {
        return "⏱️ Sub-agent " + displayName + " timed out and was stopped";
    }

    @Override
    public String textFailed(String displayName) {
        return "❌ Sub-agent " + displayName + " failed";
    }

    @Override
    public String textCompleted(String displayName) {
        return "✅ Sub-agent " + displayName + " completed";
    }

    @Override
    public String textSubLoopCapped(String displayName) {
        return "⏹️ Sub-agent " + displayName + " reached the sub-loop limit";
    }
}
