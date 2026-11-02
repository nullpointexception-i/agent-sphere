package com.buukle.agent.runtime.orchestration.service;

import com.buukle.agent.runtime.kernel.port.vo.RunStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * 发送锁判定：全系统唯一入口。
 *
 * <p>时间线（{@code agent_timeline}）只负责展示，不参与这里的任何判断。
 * 前端、widget、未来的其它入口都必须调用同一套规则，避免各自实现漂移。
 */
public final class SessionActionPolicy {

    public static final String BLOCK_RUNNING = "RUNNING";
    public static final String BLOCK_CLARIFICATION = "CLARIFICATION";
    public static final String BLOCK_TOOL_APPROVAL = "TOOL_APPROVAL";

    private SessionActionPolicy() {
    }

    public record Decision(boolean canSend, List<String> blockReasons) {
    }

    /**
     * @param runStatus             活动的 run 状态，可为 null
     * @param pendingClarifications 未回答的澄清数
     * @param pendingToolApprovals  未处理的审批数
     */
    public static Decision decide(String runStatus, int pendingClarifications, int pendingToolApprovals) {
        List<String> reasons = new ArrayList<>(3);
        if (RunStatus.PENDING.name().equals(runStatus)
                || RunStatus.RUNNING.name().equals(runStatus)
                || RunStatus.WAITING.name().equals(runStatus)) {
            reasons.add(BLOCK_RUNNING);
        }
        if (pendingClarifications > 0) {
            reasons.add(BLOCK_CLARIFICATION);
        }
        if (pendingToolApprovals > 0) {
            reasons.add(BLOCK_TOOL_APPROVAL);
        }
        return new Decision(reasons.isEmpty(), List.copyOf(reasons));
    }
}
