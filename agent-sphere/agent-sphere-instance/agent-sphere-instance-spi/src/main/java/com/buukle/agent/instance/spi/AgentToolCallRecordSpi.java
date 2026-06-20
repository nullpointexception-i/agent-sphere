package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;

import java.util.List;

public interface AgentToolCallRecordSpi {
    AgentToolCallRecordVO createRecord(Long stepId, String callId, Long runId, Long sessionId,
                                       String toolName, String displayNameCn, String displayNameEn, String argumentsJson);
    void updateStatus(Long id, String status, String artifact, String errorMessage);
    void updateCompressedArguments(Long id, String compressedArguments);
    void updateCompressedArtifact(Long id, String compressedArtifact);
    AgentToolCallRecordVO getLatestByStepId(Long stepId);
    AgentToolCallRecordVO getLatestBySessionAndToolName(Long sessionId, String toolName);
    List<AgentToolCallRecordVO> listBySessionId(Long sessionId, Long runId);
}
