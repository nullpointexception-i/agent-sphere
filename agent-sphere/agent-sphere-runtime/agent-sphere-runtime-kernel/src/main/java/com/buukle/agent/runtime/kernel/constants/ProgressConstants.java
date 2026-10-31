package com.buukle.agent.runtime.kernel.constants;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;

/**
 * Agent progress-report protocol.
 *
 * <p>Plain text after an agent has used tools is ambiguous: it may be a final
 * answer or an intermediate update. This tool makes an intermediate update
 * explicit without ending the run.
 */
public final class ProgressConstants {

    public static final String INTERNAL_NAME = "progress";
    public static final String DISPLAY_NAME = "Progress";
    public static final String DISPLAY_NAME_CN = "报告进度";
    public static final String TOOL_NAME = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN
            + BuiltinToolEnum.PROGRESS.getId();

    public static final String JSON_SUMMARY = "summary";
    public static final String JSON_NEXT_ACTION = "nextAction";
    public static final String JSON_PERCENT = "percent";
    public static final String STATUS_REPORTED = "progress_reported";

    public static final int MAX_SUMMARY_CHARS = 1000;
    public static final int MAX_NEXT_ACTION_CHARS = 500;

    public static final String DESCRIPTION =
            "Report a meaningful intermediate milestone without ending the run. "
                    + "Use this when work is still in progress. Do not use it as a final answer; "
                    + "call finish when the task is complete.";

    private ProgressConstants() {
    }
}
