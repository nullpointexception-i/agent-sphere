package com.buukle.agent.runtime.kernel.constants;

public final class RuntimeEventTypeConstant {
    // ---- PublishId prefixes ----
    public static final String PUBLISH_ID_RUN = "run-";
    public static final String PUBLISH_ID_TOOL = "tool-";
    public static final String PUBLISH_ID_COMPACT = "compact-";

    // ---- Reasoning type ----
    public static final String REASONING_TYPE_LLM = "llm";
    public static final String REASONING_TYPE_SYSTEM = "system";

    // ---- Reasoning sub-type ---
    public static final String REASONING_SUB_TYPE_MODEL_REASON = "model_reason";
    public static final String REASONING_SUB_TYPE_RUN_PENDING = "run_pending";
    public static final String REASONING_SUB_TYPE_RUN_RUNNING = "run_running";
    public static final String REASONING_SUB_TYPE_RUN_COMPLETED = "run_completed";
    public static final String REASONING_SUB_TYPE_RUN_FAILED = "run_failed";
    public static final String REASONING_SUB_TYPE_RUN_CANCELLED = "run_cancelled";
    public static final String REASONING_SUB_TYPE_RUN_AWAITING_USER = "run_awaiting_user";
    /** wait suspension (not terminal: the same run continues when the timer fires, so the frontend must not unlock the input box based on it). */
    public static final String REASONING_SUB_TYPE_RUN_WAITING = "run_waiting";

    public static final String REASONING_SUB_TYPE_COMPACTION_RUNNING = "compaction_running";
    public static final String REASONING_SUB_TYPE_COMPACTION_COMPLETED = "compaction_completed";
    public static final String REASONING_SUB_TYPE_COMPACTION_FAILED = "compaction_failed";
    /** Streaming output of the compaction model (CONTENT_TOKEN): the frontend prints it into the compaction row body and does not merge it into assistant. */
    public static final String REASONING_SUB_TYPE_COMPACTION_OUTPUT = "compaction_output";

    /** Tool arguments are still streaming (the approval card is created only once the arguments are complete). */
    public static final String REASONING_SUB_TYPE_TOOL_INPUT_PREPARING = "tool_input_preparing";
    public static final String REASONING_SUB_TYPE_TOOL_CALL_STARTED = "tool_call_started";
    public static final String REASONING_SUB_TYPE_TOOL_CALL_IN_PROGRESS = "tool_call_in_progress";
    public static final String REASONING_SUB_TYPE_TOOL_CALL_SUCCEEDED = "tool_call_succeeded";
    public static final String REASONING_SUB_TYPE_TOOL_CALL_FAILED = "tool_call_failed";

    public static final String REASONING_SUB_TYPE_SESSION_UPDATED = "session_updated";

    private RuntimeEventTypeConstant() {
    }
}
