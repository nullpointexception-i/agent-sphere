package com.buukle.agent.channel.spi;

/** Task card variants rendered by channels. */
public final class TaskCardType {

    public static final String TASK = "TASK";
    public static final String TASK_RESULT = "TASK_RESULT";
    /** Router clarification card: title/options + answer buttons. */
    public static final String ROUTER_CLARIFICATION = "ROUTER_CLARIFICATION";
    /** L2 tool approval card: per-call decision buttons. */
    public static final String ROUTER_APPROVAL = "ROUTER_APPROVAL";

    private TaskCardType() {
    }
}
