package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.AgentUserInLoopRecordVO;

public interface AgentUserInLoopRecordSpi {
    AgentUserInLoopRecordVO createRecord(Long stepId, Long runId, Long sessionId,
                                         String interactionType, String prompt);
    AgentUserInLoopRecordVO getRecord(Long id);
    AgentUserInLoopRecordVO getByStepId(Long stepId);
    void respondRecord(Long id, String response, String respondedBy, String result, String comment);
}
