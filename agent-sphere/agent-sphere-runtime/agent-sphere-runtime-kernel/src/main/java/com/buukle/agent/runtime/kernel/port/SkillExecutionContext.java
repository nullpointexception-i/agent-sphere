package com.buukle.agent.runtime.kernel.port;

import java.util.List;
import java.util.Set;

/**
 * Skill 嵌套执行的显式上下文（禁止经 ThreadLocal 传递）。
 * 主 Agent 创建 root（depth=0、stack 空、allowedToolRefs=null 表示无父级限制）；
 * 进入 skill 时创建 child。
 */
public class SkillExecutionContext {

    private final Long sessionId;
    private final Long runId;
    private final KernelContext kernelContext;
    /** 当前 skill 嵌套深度（主循环为 0）。 */
    private final int skillDepth;
    /** 当前 skill 调用链（skill id）。 */
    private final List<Long> skillStack;
    /** 父级继承的白名单；null = 无限制（仅根上下文允许 null），其余为交集结果。 */
    private final Set<String> inheritedAllowedToolRefs;
    private final String parentToolCallId;

    public SkillExecutionContext(Long sessionId, Long runId, KernelContext kernelContext,
                                 int skillDepth, List<Long> skillStack,
                                 Set<String> inheritedAllowedToolRefs, String parentToolCallId) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.kernelContext = kernelContext;
        this.skillDepth = skillDepth;
        this.skillStack = skillStack == null ? List.of() : List.copyOf(skillStack);
        this.inheritedAllowedToolRefs = inheritedAllowedToolRefs;
        this.parentToolCallId = parentToolCallId;
    }

    public static SkillExecutionContext root(Long sessionId, Long runId, KernelContext kernelContext) {
        return new SkillExecutionContext(sessionId, runId, kernelContext, 0, List.of(), null, null);
    }

    public SkillExecutionContext child(int depth, List<Long> stack, Set<String> allowed, String parentToolCallId) {
        return new SkillExecutionContext(sessionId, runId, kernelContext, depth, stack, allowed, parentToolCallId);
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

    public int getSkillDepth() {
        return skillDepth;
    }

    public List<Long> getSkillStack() {
        return skillStack;
    }

    public Set<String> getInheritedAllowedToolRefs() {
        return inheritedAllowedToolRefs;
    }

    public String getParentToolCallId() {
        return parentToolCallId;
    }
}