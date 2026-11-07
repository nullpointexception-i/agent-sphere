package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;

import java.util.List;

public interface AgentToolCallRecordSpi {
    AgentToolCallRecordVO createRecord(Long stepId, String callId, Long runId, Long sessionId,
                                       String toolName, String displayNameCn, String displayNameEn, String argumentsJson,
                                       Long subAgentRunId, Integer turnNo);

    void updateStatus(Long id, String status, String artifact, String errorMessage);

    void updateCompressedArguments(Long id, String compressedArguments);

    void updateCompressedArtifact(Long id, String compressedArtifact);

    /**
     * Record which browser device executed this tool call (device info reported back by the extension callback).
     *
     * @return number of matched and updated rows
     */
    int updateExecutionDevice(Long runId, String callId, String deviceId, String deviceName, String source);

    AgentToolCallRecordVO getLatestByStepId(Long stepId);

    /**
     * Locate a tool call record precisely by runId + model-side tool call id.
     *
     * <p>Used to rewrite the artifact when resuming from a wait suspension. Deliberately does not
     * offer the "derive stepId from callId
     * ({@code (PUBLISH_ID_TOOL + callId).hashCode() & 0x7FFFFFFF}) then call getLatestByStepId}"
     * path: that hash algorithm is scattered inside {@code RuntimeEventListener}, and a copy of it
     * would silently land on the wrong record when the publishId prefix changes.
     *
     * @return the matched record; {@code null} when there is no match
     */
    AgentToolCallRecordVO getByRunAndCallId(Long runId, String callId);

    /** Locate by sessionId + model-side tool call id (used by read_tool_result to read back a historical artifact). */
    AgentToolCallRecordVO getBySessionAndCallId(Long sessionId, String callId);

    AgentToolCallRecordVO getLatestBySessionAndToolName(Long sessionId, String toolName);

    List<AgentToolCallRecordVO> listBySessionId(Long sessionId, Long runId);

    List<AgentToolCallRecordVO> listByRunId(Long runId, int offset, int limit);

    long countByRunId(Long runId);

    int failRunningByRunId(Long runId, String errorMessage);

    long countBySessionId(Long sessionId);

    Long getLatestRunIdWithToolCalls(Long sessionId);
}
