package com.buukle.agent.application.dtvo.enums;

/** Lifecycle values for {@code biz_application_run.status}. */
public final class ApplicationRunStatus {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private ApplicationRunStatus() {
    }
}
