package com.buukle.agent.common.constant;

/**
 * {@code agent_pending_wait.kind} 的取值。
 *
 * <p>放在 common 是因为三处都要用：kernel（触发挂起）、instance-service（落库/查询）、
 * orchestration（唤醒分支），而它们只在 common 上汇合。
 */
public final class PendingWaitKind {

    /** wait 内置工具：唤醒时补记工具结果 + 追加「等完了」合成 user。 */
    public static final String WAIT = "WAIT";

    /** LLM 瞬时失败后的梯度重试：唤醒时直接重跑同一轮，不追加任何合成消息。 */
    public static final String LLM_RETRY = "LLM_RETRY";

    private PendingWaitKind() {
    }
}
