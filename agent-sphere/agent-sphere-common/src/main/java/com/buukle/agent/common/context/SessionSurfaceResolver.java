package com.buukle.agent.common.context;

/**
 * Parses the request header into a {@link SessionSurface}.
 *
 * <p>Extracted as a static method without a servlet dependency because two entry points need it but
 * sit on opposite sides of the dependency graph: {@code AuthInterceptor} lives in infrastructure and
 * the login/SSO controllers live in instance-controller, so neither may depend on the other. Each
 * caller reads the header from its own {@code HttpServletRequest} and hands it here.
 */
public final class SessionSurfaceResolver {

    private SessionSurfaceResolver() {
    }

    /**
     * Absent header (or no header at all) counts as the main site; only an explicit {@code widget}
     * is treated as the widget.
     *
     * <p>This is a policy signal rather than a security boundary, consistent with the
     * {@link SessionSurfaceHolder} convention.
     */
    public static SessionSurface fromHeader(String rawHeader) {
        return SessionSurface.WIDGET.name().equalsIgnoreCase(rawHeader == null ? null : rawHeader.trim())
                ? SessionSurface.WIDGET
                : SessionSurface.CONSOLE;
    }
}
