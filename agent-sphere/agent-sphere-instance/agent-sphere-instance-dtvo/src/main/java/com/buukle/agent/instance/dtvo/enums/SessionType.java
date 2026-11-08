package com.buukle.agent.instance.dtvo.enums;

/** Session types used by Super-Agent and IM integrations. */
public final class SessionType {

    public static final String CONSOLE = "CONSOLE";
    public static final String WIDGET = "WIDGET";
    public static final String TASK = "TASK";
    public static final String GROUP_ROUTER = "GROUP_ROUTER";
    public static final String ROUTER = "ROUTER";
    public static final String WORKER = "WORKER";
    /** Owner-only console session bound directly to a Super-Agent Worker instance. */
    public static final String TRAINING = "TRAINING";
    public static final String WATCHDOG = "WATCHDOG";

    private SessionType() {
    }
}
