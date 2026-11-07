package com.buukle.agent.common.context;

/**
 * Client surface of a session operation.
 *
 * <p>The same session endpoints (create session / send message) are shared by several entry
 * points, while an instance can allow or deny each surface separately
 * ({@code agent_instance.allow_console_session} / {@code allow_widget_session}), so the server must
 * know which surface a call came from.
 */
public enum SessionSurface {

    /** Embeddable widget (agent-sphere-copilot-widget). */
    WIDGET,

    /** Main console (agent-sphere-ui). */
    CONSOLE,

    /**
     * Server-internal calls — task submission, structured extraction, and so on.
     *
     * <p>This is the <b>default when no surface is set</b>: internal calls do not pass through the
     * HTTP interceptor ({@code /api/v1/api/*} is allowed early, and {@code pollSweep} / extraction
     * turns have no request thread at all), so a null value must be treated as internal — otherwise
     * task paths would be blocked by their own switch.
     */
    INTERNAL
}
