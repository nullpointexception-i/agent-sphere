package com.buukle.agent.common.context;

import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;

/**
 * The <b>single</b> decision rule for the session surface switch.
 *
 * <p>Shared by session creation ({@code createSession}) and messaging ({@code chat}) so the truth
 * table is not duplicated in two places and left to drift. The two per-instance switches come from
 * {@code agent_instance.allow_console_session} / {@code allow_widget_session}.
 */
public final class SessionSurfacePolicy {

    private SessionSurfacePolicy() {
    }

    /**
     * Whether this surface is allowed.
     *
     * <p>Null values follow the column defaults: a null {@code allowConsole} counts as allowed and
     * a null {@code allowWidget} as denied — matching {@code DEFAULT TRUE} / {@code DEFAULT FALSE}
     * in the migration, so the null semantics never contradict the database default.
     */
    public static boolean allows(SessionSurface surface, Boolean allowConsole, Boolean allowWidget) {
        return switch (surface) {
            // Internal calls (task submission / extraction turns) do not enter through HTTP and are not bound by the instance switches
            case INTERNAL -> true;
            case WIDGET -> Boolean.TRUE.equals(allowWidget);
            case CONSOLE -> !Boolean.FALSE.equals(allowConsole);
        };
    }

    /** Throws {@link CommonErrorCode#SESSION_SURFACE_DISABLED} when not allowed. */
    public static void assertAllowed(SessionSurface surface, Boolean allowConsole, Boolean allowWidget) {
        if (!allows(surface, allowConsole, allowWidget)) {
            throw new BizException(CommonErrorCode.SESSION_SURFACE_DISABLED,
                    "surface=" + surface);
        }
    }
}
