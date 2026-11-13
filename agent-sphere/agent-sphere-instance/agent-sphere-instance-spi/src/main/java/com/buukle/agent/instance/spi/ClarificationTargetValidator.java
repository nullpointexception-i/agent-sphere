package com.buukle.agent.instance.spi;

/**
 * Guards {@code ask_clarification.targetUserId}: only callers that can prove the target may
 * receive the question should allow it. The default is permissive so existing deployments keep
 * working; the project module narrows it to project members.
 */
public interface ClarificationTargetValidator {

    default boolean canTarget(Long sessionId, Long targetUserId) {
        return true;
    }
}
