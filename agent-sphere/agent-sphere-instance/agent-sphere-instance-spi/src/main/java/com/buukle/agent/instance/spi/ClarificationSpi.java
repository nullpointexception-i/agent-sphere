package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.ClarificationVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface ClarificationSpi {
    Long createPending(Long sessionId, Long runId, Long messageId, String title, String type,
                       boolean multiple, String optionsJson, String description, String clarificationId);

    /**
     * Targeted clarification: when {@code targetUserId} is set the question is addressed to that
     * user (a project member) instead of the run's own user; {@code expiresAt} bounds the wait.
     */
    Long createPending(Long sessionId, Long runId, Long messageId, String title, String type,
                       boolean multiple, String optionsJson, String description, String clarificationId,
                       Long targetUserId, java.time.LocalDateTime expiresAt);

    /** L2 tool approval: one pending clarification carrying the batch of tool calls. */
    Long createToolApprovalPending(Long sessionId, Long runId, String title, String optionsJson,
                                   String toolCallsJson, String clarificationId);

    Map<Long, List<ClarificationVO>> mapByRunIdList(Collection<Long> runIds);

    /**
     * Mark a pending clarification as answered by sessionId + clarificationId (used by the AG-UI resume path).
     *
     * @return whether a pending record was found and updated
     */
    boolean respondToClarificationByClarificationId(Long sessionId, String clarificationId, String response);

    /** Pending targeted questions addressed to the user, newest first. */
    List<ClarificationVO> listPendingByTargetUser(Long tenantId, Long targetUserId);

    /** A pending record by clarification id, regardless of session (used by the targeted answer path). */
    ClarificationVO findPendingByClarificationId(String clarificationId);

    /** Any record by clarification id (used to verify a completed destructive confirmation). */
    ClarificationVO findByClarificationId(String clarificationId);

    /**
     * Answer a targeted question after checking the target user.
     *
     * @return true when the record was pending, targeted at {@code targetUserId}, and got the answer
     */
    boolean answerTargeted(Long id, Long targetUserId, String response, Long answeredBy);

    /** Targeted questions past their expiry. */
    List<ClarificationVO> listExpired(java.time.LocalDateTime now, int limit);

    /** Close an expired question (dismissed response). */
    void markExpired(Long id);

    /** Record who answered a targeted question (the resume path already stored the response). */
    void markAnsweredBy(String clarificationId, Long answeredBy);
}
