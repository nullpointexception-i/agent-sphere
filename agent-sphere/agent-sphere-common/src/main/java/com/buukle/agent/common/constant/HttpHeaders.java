package com.buukle.agent.common.constant;

/**
 * Shared HTTP protocol constants.
 *
 * <p>Keep these values in sync with the front-end clients ({@code agent-sphere-ui},
 * {@code agent-sphere-copilot-widget} and the Chrome extension). They are duplicated
 * intentionally across languages because the browser cannot import Java constants.
 */
public final class HttpHeaders {

    public static final String AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String USER_AGENT = "User-Agent";
    public static final String CLIENT_SURFACE = "X-Client-Surface";

    private HttpHeaders() {
    }
}
