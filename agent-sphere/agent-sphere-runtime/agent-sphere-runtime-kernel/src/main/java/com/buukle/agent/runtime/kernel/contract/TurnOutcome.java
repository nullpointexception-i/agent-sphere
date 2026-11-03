package com.buukle.agent.runtime.kernel.contract;

public enum TurnOutcome {
    COMPLETE,
    TOOL_CALLS,
    COMPACTED,
    /** 当前 run 的 run-local 上下文已压缩，调用方替换内存消息后继续。 */
    RUN_COMPACTED,
    ERROR,
    /** LLM 瞬时失败：本轮挂起，到点重跑同一轮（V115）。 */
    RETRY_LATER,
    CANCELLED
}
