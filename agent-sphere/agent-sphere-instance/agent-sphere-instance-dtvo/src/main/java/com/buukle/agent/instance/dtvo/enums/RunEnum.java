package com.buukle.agent.instance.dtvo.enums;

public final class RunEnum {
    public static final String TYPE_AUTO = "auto";
    public static final String TYPE_CHAT = "chat";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_AWAITING_USER = "AWAITING_USER";
    /** Suspended by the wait tool: resumes automatically when the time arrives (distinct from AWAITING_USER "waiting for a person"; both are parked states). */
    public static final String STATUS_WAITING = "WAITING";
    public static final String DIRECTION_PREV = "prev";
    public static final String DIRECTION_NEXT = "next";
    private RunEnum() {
    }
}
