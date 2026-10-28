package com.buukle.agent.runtime.kernel.contract;

/**
 * 一次压缩尝试的真实结果。
 *
 * <p>{@link #COMPLETED} 仅表示「摘要已生成且游标前进」；调用方只有在这种情况才应重新组装历史。
 * 旧实现把「触发过压缩」当成成功，导致无历史/无进展/失败时也反复按同一阈值重试。
 */
public enum CompactionOutcome {
    /** 摘要落库、游标前进。 */
    COMPLETED,
    /** 没有可压缩的已结束历史（例如只有当前 run）。 */
    NO_HISTORY,
    /** 有历史但没有写入任何内容（无法裁剪或模型无输出）。 */
    NO_PROGRESS,
    /** 压缩调用/落库异常。 */
    FAILED
}
