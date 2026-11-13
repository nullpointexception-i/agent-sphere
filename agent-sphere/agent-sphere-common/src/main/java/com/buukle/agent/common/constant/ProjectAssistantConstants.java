package com.buukle.agent.common.constant;

/** Shared constants for the tenant-level Project Assistant. */
public final class ProjectAssistantConstants {

    /** Session type of the per-user, per-project assistant conversation. */
    public static final String SESSION_TYPE = SessionTypeCodes.PROJECT_ROUTER;

    /** Thread kind / external thread id prefix of a project conversation. */
    public static final String THREAD_KIND_PROJECT = "PROJECT";
    public static final String THREAD_EXTERNAL_PREFIX = "project:";

    /** Keys stored in {@code agent_session.context_data}. */
    public static final String CONTEXT_PROJECT_ID = "projectId";
    public static final String CONTEXT_REQUESTER_USER_ID = "requesterUserId";

    /** Project config path of the auto-analysis switch. */
    // The switch lives in pm_project_assistant_config; kept out of the jsonb project config.

    /** Auto-analysis debounce (per project). */
    public static final String AUTO_ANALYZE_LOCK_PREFIX = "project:auto-analyze:";
    public static final long AUTO_ANALYZE_DEBOUNCE_MINUTES = 10;

    /** Targeted human questions. */
    public static final long QUESTION_EXPIRY_HOURS = 24;
    public static final long QUESTION_EXPIRY_SCAN_MINUTES = 10;

    /** Synchronous avatar consultation. */
    public static final long AVATAR_CONSULT_TIMEOUT_SECONDS = 25;

    /** Agent-authored content. */
    public static final String AUTHOR_TYPE_AGENT = "AGENT";
    public static final String AUTHOR_NAME = "Project Assistant";

    /** Hard cap of assistant comments per analysis run, so one batch cannot spam a project. */
    public static final int MAX_COMMENTS_PER_RUN = 3;

    /** Mention delivery retry policy. */
    public static final int MENTION_MAX_ATTEMPTS = 5;
    public static final long MENTION_RETRY_BASE_SECONDS = 15;
    public static final long MENTION_RETRY_MAX_SECONDS = 600;
    public static final long MENTION_PROCESSING_TIMEOUT_SECONDS = 600;

    /** Content row status used by project insights and context events. */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** Project-assistant run sources (persisted in request_source / source refs). */
    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String REQUEST_SOURCE = "PROJECT_ASSISTANT";

    private ProjectAssistantConstants() {
    }
}
