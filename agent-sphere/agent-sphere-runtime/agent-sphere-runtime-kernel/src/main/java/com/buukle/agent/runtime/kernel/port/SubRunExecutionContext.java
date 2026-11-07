package com.buukle.agent.runtime.kernel.port;

import java.util.List;

/**
 * Explicit context for nested sub-agent execution (passing it via ThreadLocal is forbidden).
 * The main agent creates root (depth=0, empty ancestry, ownerSubAgentRunId=null);
 * entering a sub-agent creates a child, recording the call chain in toolRefAncestry to detect recursion.
 */
public class SubRunExecutionContext {

    private final Long sessionId;
    private final Long runId;
    private final KernelContext kernelContext;
    /** Current sub-agent nesting depth (0 in the main loop). */
    private final int depth;
    /** Current sub-agent call chain (toolRef), used for recursion detection. */
    private final List<String> toolRefAncestry;
    /** Id of the sub_agent_run owning the current execution context; null at top level. */
    private final Long ownerSubAgentRunId;
    private final String parentToolCallId;

    public SubRunExecutionContext(Long sessionId, Long runId, KernelContext kernelContext,
                                  int depth, List<String> toolRefAncestry,
                                  Long ownerSubAgentRunId, String parentToolCallId) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.kernelContext = kernelContext;
        this.depth = depth;
        this.toolRefAncestry = toolRefAncestry == null ? List.of() : List.copyOf(toolRefAncestry);
        this.ownerSubAgentRunId = ownerSubAgentRunId;
        this.parentToolCallId = parentToolCallId;
    }

    public static SubRunExecutionContext root(Long sessionId, Long runId, KernelContext kernelContext) {
        return new SubRunExecutionContext(sessionId, runId, kernelContext, 0, List.of(), null, null);
    }

    public SubRunExecutionContext child(int depth, List<String> ancestry,
                                        String parentToolCallId, Long ownerSubAgentRunId) {
        return new SubRunExecutionContext(sessionId, runId, kernelContext, depth, ancestry,
                ownerSubAgentRunId, parentToolCallId);
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getRunId() {
        return runId;
    }

    public KernelContext getKernelContext() {
        return kernelContext;
    }

    public int getDepth() {
        return depth;
    }

    public List<String> getToolRefAncestry() {
        return toolRefAncestry;
    }

    public Long getOwnerSubAgentRunId() {
        return ownerSubAgentRunId;
    }

    public String getParentToolCallId() {
        return parentToolCallId;
    }
}
