package com.buukle.agent.runtime.kernel.constants;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;

/**
 * Constants for the global finish tool — the **only legal way** for an agentic run to end.
 *
 * <p>Why it exists: "the model did not call a tool" is an ambiguous signal — it may mean "the task is done", or
 * "the model gave up after a couple of sentences". The old loop treated silence as completion, so unfinished work was recorded as success — a silent failure.
 * finish turns "I am done" into an explicit tool call, so silence is left with only one meaning: abnormal.
 *
 * <p>Shaped after {@link WaitConstants} / {@link ChatClarification}: no separate Maven module, no SPI bean —
 * constants + hand-written schema + explicit registration in {@code ContextPreparer} + interception in {@code ToolExecutor} +
 * finalization inside the {@code SessionRunner} loop.
 */
public final class FinishConstants {

    public static final String INTERNAL_NAME = "finish";
    public static final String DISPLAY_NAME = "Finish";
    public static final String DISPLAY_NAME_CN = "完成任务";

    public static final String TOOL_NAME = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.FINISH.getId();

    /** Status returned by the tool; used only for tool records/history display — finalization is decided by SessionRunner reading the input directly. */
    public static final String STATUS_FINISHED = "finished";

    // ---- Input/output field names ----
    public static final String JSON_STATUS = "status";
    public static final String JSON_SUMMARY = "summary";
    /** Echoes the end state declared by the model in the tool receipt (the status returned by the tool is always finished). */
    public static final String JSON_FINISH_STATUS = "finish_status";

    // ---- End states the model can declare ----
    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAILED = "failed";
    public static final String RESULT_BLOCKED = "blocked";

    public static final String DESCRIPTION =
            "Declare that the task is COMPLETE and end the run. This is the ONLY legitimate way to finish an "
                    + "agentic task — ending your turn with plain text is treated as an error (the loop will ask "
                    + "you to continue or retry). For an intermediate milestone, call progress instead. "
                    + "Call finish once you have actually finished the work, not to ask a "
                    + "question and not to pause.\n\n"
                    + "- status=success: the requested work was done and verified.\n"
                    + "- status=failed: you tried but could not complete it (state what blocked you).\n"
                    + "- status=blocked: you cannot proceed without something external (login, permission, missing "
                    + "data) — describe exactly what is needed.\n\n"
                    + "`summary` must be a complete, self-contained answer for the user (the run's final reply is "
                    + "taken from it). Do NOT call any other tool in the same turn as finish.";

    private FinishConstants() {
    }
}
