package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.ClarificationVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface ClarificationSpi {
    Long createPending(Long sessionId, Long runId, Long messageId, String title, String type,
                       boolean multiple, String optionsJson, String description, String clarificationId);

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
}
