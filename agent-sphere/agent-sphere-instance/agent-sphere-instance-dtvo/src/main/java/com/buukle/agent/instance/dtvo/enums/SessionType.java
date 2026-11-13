package com.buukle.agent.instance.dtvo.enums;

import com.buukle.agent.common.constant.SessionTypeCodes;

/** Session types used by Super-Agent and IM integrations. */
public final class SessionType {

    public static final String CONSOLE = SessionTypeCodes.CONSOLE;
    public static final String WIDGET = SessionTypeCodes.WIDGET;
    public static final String TASK = SessionTypeCodes.TASK;
    public static final String GROUP_ROUTER = SessionTypeCodes.GROUP_ROUTER;
    public static final String ROUTER = SessionTypeCodes.ROUTER;
    public static final String WORKER = SessionTypeCodes.WORKER;
    /** Owner-only console session bound directly to a Super-Agent Worker instance. */
    public static final String TRAINING = SessionTypeCodes.TRAINING;
    public static final String WATCHDOG = SessionTypeCodes.WATCHDOG;
    /** Tenant-level project assistant; one session per user per project. */
    public static final String PROJECT_ROUTER = SessionTypeCodes.PROJECT_ROUTER;

    private SessionType() {
    }
}
