package com.buukle.agent.runtime.kernel.port;

import java.util.List;

/**
 * 子 Agent 嵌套执行的显式上下文（禁止经 ThreadLocal 传递）。
 * 主 Agent 创建 root（depth=0、ancestry 空、ownerSubAgentRunId=null）；
 * 进入子 Agent 时创建 child，用 toolRefAncestry 记录调用链以检测递归。
 */
public class SubRunExecutionContext {

    private final Long sessionId;
    private final Long runId;
    private final KernelContext kernelContext;
    /** 当前子 Agent 嵌套深度（主循环为 0）。 */
    private final int depth;
    /** 当前子 Agent 调用链（toolRef），用于递归检测。 */
    private final List<String> toolRefAncestry;
    /** 拥有当前执行上下文的 sub_agent_run id；顶层为 null。 */
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
