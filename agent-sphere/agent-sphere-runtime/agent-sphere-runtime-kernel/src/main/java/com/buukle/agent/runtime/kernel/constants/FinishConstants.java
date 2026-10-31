package com.buukle.agent.runtime.kernel.constants;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;

/**
 * 全局 finish 工具的常量 —— agentic run 的**唯一合法结束方式**。
 *
 * <p>为什么需要它：「模型没有调用工具」是一个二义信号 —— 既可能是「任务做完了」，也可能是
 * 「模型只说了几句就放弃」。旧循环把沉默当成完成，于是未完成被记成成功，属于静默失败。
 * finish 把「我完成了」变成一次显式工具调用，沉默因此只剩「异常」一种含义。
 *
 * <p>形态照 {@link WaitConstants} / {@link ChatClarification}：无独立 maven 模块、无 SPI bean，
 * 常量 + 手写 schema + {@code ContextPreparer} 显式注册 + {@code ToolExecutor} 拦截 +
 * {@code SessionRunner} 循环内收口。
 */
public final class FinishConstants {

    public static final String INTERNAL_NAME = "finish";
    public static final String DISPLAY_NAME = "Finish";
    public static final String DISPLAY_NAME_CN = "完成任务";

    public static final String TOOL_NAME = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.FINISH.getId();

    /** 工具返回的 status；仅供工具记录/历史展示，收口判定由 SessionRunner 直接读入参。 */
    public static final String STATUS_FINISHED = "finished";

    // ---- 入参/出参字段名 ----
    public static final String JSON_STATUS = "status";
    public static final String JSON_SUMMARY = "summary";
    /** 工具回执里回显模型声明的结束状态（工具返回的 status 固定为 finished）。 */
    public static final String JSON_FINISH_STATUS = "finish_status";

    // ---- 模型可声明的结束状态 ----
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
