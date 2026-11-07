package com.buukle.agent.runtime.kernel.port;

import com.buukle.agent.common.sub.agent.InvalidSubRunDefinition;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;

import java.time.Duration;

/**
 * Sub-agent ReAct execution policy (host configuration and semantics for sub-runs such as delegate).
 * {@code SessionSubRunner} only cares about the generic ReAct skeleton; every policy-specific difference converges into this interface.
 * Implementations must keep the returned naming/text consistent with the protocol (frontend routing, event bus).
 */
public interface SubRunPolicy {

    boolean isEnabled();

    int maxNestedDepth();

    int maxSubLoopCount();

    Duration executionTimeout();

    int maxPromptChars();

    int maxResultChars();

    /** Tool identity (for grouping/dedup), e.g. the stable hash of agent:&lt;key&gt;. */
    long toolId(RuntimeTool tool);

    String nodeNamePrefix();

    String markerPrefix();

    String publishIdPrefix();

    String agentType();

    String toolRef(long toolId);

    LlmInteractionType interactionType();

    String displayName(RuntimeTool tool);

    /** Sub-agent-specific system prompt (agentRef's systemPrompt+customInstructions); null when absent. */
    default String systemPrompt(RuntimeTool tool) {
        return null;
    }

    /** Renders the sub-agent's first user prompt (including goal / input injection). */
    String renderPrompt(RuntimeTool tool, String argsJson, SubRunExecutionContext ctx) throws InvalidSubRunDefinition;

    /** The sub-agent's structured contact envelope (escalated args + upstream dependency results); null/empty string when absent. */
    default String contactEnvelope(RuntimeTool tool, String argsJson) {
        return null;
    }

    /**
     * One-shot "depth/recursion validation + sub-context construction" (the policy owns depth + ancestry semantics;
     * the runner no longer touches depth/ancestry). Returns either an error or a ready sub-context.
     */
    PreparedSubRun prepare(RuntimeTool tool, long toolId, SubRunExecutionContext parentCtx);

    record PreparedSubRun(String error, SubRunExecutionContext childCtx, int depth) {
        public boolean ready() {
            return error == null;
        }
    }

    String errorDisabled();

    String errorDepthExceeded();

    String errorRecursive(long toolId);

    String errorMissingPrompt();

    String errorRenderFailed(String message);

    String errorCancelled();

    String errorTimeout();

    String errorExecutionFailed(String detail);

    String errorNotAllowed(String toolName);

    String textStarted(String displayName, int depth);

    String textTimeout(String displayName);

    String textFailed(String displayName);

    String textCompleted(String displayName);

    String textSubLoopCapped(String displayName);
}
