package com.buukle.agent.common.context;

import com.buukle.agent.common.constant.HttpHeaders;

/**
 * Session surface of the current thread (propagated via ThreadLocal).
 *
 * <p>{@code AuthInterceptor} parses it from the request header at the HTTP entry point and sets it;
 * it must be cleared when the request ends — Tomcat threads are pooled, and failing to clear would
 * carry the previous request's surface into the next one.
 *
 * <p>Same convention as {@code AuthContext} / {@code TenantUtil} / {@code TaskLoopLimitHolder}.
 */
public final class SessionSurfaceHolder {

    /**
     * Request header in which the client declares its surface. Absent = {@link SessionSurface#CONSOLE}
     * (main site).
     *
     * <p>This is a <b>policy signal, not a security boundary</b>: forging it achieves nothing — the
     * user could simply use the main site directly. The real boundary is the instance switch
     * itself, which only decides "which surface may be used" and grants no extra privilege.
     */
    public static final String HEADER = HttpHeaders.CLIENT_SURFACE;

    private static final ThreadLocal<SessionSurface> SURFACE = new ThreadLocal<>();

    private SessionSurfaceHolder() {
    }

    public static void set(SessionSurface surface) {
        SURFACE.set(surface);
    }

    /** Raw value; {@code null} when not set. */
    public static SessionSurface get() {
        return SURFACE.get();
    }

    /**
     * Current surface; when unset it is treated as {@link SessionSurface#INTERNAL}.
     *
     * <p>Internal calls (task paths) bypass the interceptor and always see null — use this method so
     * they are allowed safely.
     */
    public static SessionSurface current() {
        SessionSurface s = SURFACE.get();
        return s == null ? SessionSurface.INTERNAL : s;
    }

    public static void clear() {
        SURFACE.remove();
    }
}
