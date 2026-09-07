package com.buukle.agent.runtime.kernel.port;

import com.buukle.agent.common.sub.agent.InvalidSubRunDefinition;
import com.buukle.agent.runtime.kernel.model.invoke.LlmInteractionType;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 子 Agent ReAct 执行策略（skill / mcp / 其它 sub-run 各自的宿主配置与语义）。
 * {@code SessionSubRunner} 只关心通用 ReAct 骨架，命中策略的差异全部收敛到该接口。
 * 子类需保证返回的命名/文案与协议（前端路由、事件总线）一致。
 */
public interface SubRunPolicy {

    boolean isEnabled();

    int maxNestedDepth();

    int maxSubLoopCount();

    Duration executionTimeout();

    int maxPromptChars();

    int maxResultChars();

    /** 工具标识（分组/去重用），如 skill:5 → 5。 */
    long toolId(RuntimeTool tool);

    String nodeNamePrefix();

    String markerPrefix();

    String publishIdPrefix();

    String agentType();

    String toolRef(long toolId);

    LlmInteractionType interactionType();

    String displayName(RuntimeTool tool);

    boolean hasPromptTemplate(RuntimeTool tool);

    /** 渲染子 Agent 首轮用户 prompt（含模板占位符/入参注入）。 */
    String renderPrompt(RuntimeTool tool, String argsJson, SubRunExecutionContext ctx) throws InvalidSubRunDefinition;

    /** 可用工具白名单（与父链交集）。 */
    Set<String> allowedRefs(RuntimeTool tool, SubRunExecutionContext parentCtx);

    /**
     * 一次性完成“深度/递归校验 + 子上下文构建”（depth + SkillStack 语义由策略负责，
     * runner 不再触碰 {@code getSkillDepth()/getSkillStack()}）。返回错误或就绪的子上下文。
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