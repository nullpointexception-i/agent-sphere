package com.buukle.agent.instance.spi;

/**
 * Supplies the shared project context injected into project-assistant sessions.
 *
 * <p>The canonical project session and every user fork read the same bounded context block,
 * so project/requirement changes and assistant writes stay visible across conversations.
 */
public interface ProjectContextSpi {

    /**
     * Build a bounded plain-text context block for the project bound to the session.
     *
     * @return null/blank when the session is not a project assistant session or has no context
     */
    String buildContext(Long sessionId);
}
