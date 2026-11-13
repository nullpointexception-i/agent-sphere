package com.buukle.agent.superagent.spi;

/** Result of queueing one project mention on a local runtime. */
public record LocalProjectMentionDispatchResult(
        boolean accepted,
        Long taskId,
        String message,
        boolean retryable
) {

    public static LocalProjectMentionDispatchResult accepted(Long taskId) {
        return new LocalProjectMentionDispatchResult(true, taskId, "queued", false);
    }

    public static LocalProjectMentionDispatchResult retry(String message) {
        return new LocalProjectMentionDispatchResult(false, null, message, true);
    }

    public static LocalProjectMentionDispatchResult rejected(String message) {
        return new LocalProjectMentionDispatchResult(false, null, message, false);
    }
}
