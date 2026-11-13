package com.buukle.agent.superagent.spi;

/**
 * Ensures the single Super-Agent thread that represents one project.
 *
 * <p>The thread is shared by the canonical project-assistant session and every user fork.
 */
public interface ProjectThreadSpi {

    /**
     * Idempotently create the project thread.
     *
     * @return the thread id
     */
    Long ensureProjectThread(Long tenantId, Long projectId, String title, String createdBy);

    /** The project thread id, or null when it has not been created yet. */
    Long findProjectThread(Long tenantId, Long projectId);
}
