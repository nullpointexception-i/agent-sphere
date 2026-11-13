package com.buukle.agent.application.dtvo.enums;

/** Trigger values for {@code biz_application_run.trigger_type}. */
public final class ApplicationTriggerType {

    public static final String MANUAL = "MANUAL";
    public static final String CRON = "CRON";
    public static final String EVENT = "EVENT";
    public static final String PAGE = "PAGE";

    private ApplicationTriggerType() {
    }
}
