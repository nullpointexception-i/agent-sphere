package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;

import java.util.List;

public interface AgentToolCallRecordSpi {
    AgentToolCallRecordVO createRecord(Long stepId, String callId, Long runId, Long sessionId,
                                       String toolName, String displayNameCn, String displayNameEn, String argumentsJson, Long subAgentRunId);

    void updateStatus(Long id, String status, String artifact, String errorMessage);

    void updateCompressedArguments(Long id, String compressedArguments);

    void updateCompressedArtifact(Long id, String compressedArtifact);

    AgentToolCallRecordVO getLatestByStepId(Long stepId);

    /**
     * 按 runId + 模型侧 tool call id 精确定位一条工具调用记录。
     *
     * <p>供 wait 挂起恢复时改写 artifact 使用。刻意不提供「先按 callId 反推 stepId
     * ({@code (PUBLISH_ID_TOOL + callId).hashCode() & 0x7FFFFFFF})} 再 getLatestByStepId}
     * 的路径：那个哈希算法散落在 {@code RuntimeEventListener} 里，复制一份会在
     * publishId 前缀变更时静默错位到别的记录上。
     *
     * @return 命中的记录；无匹配返回 {@code null}
     */
    AgentToolCallRecordVO getByRunAndCallId(Long runId, String callId);

    /** 按 sessionId + 模型侧 tool call id 定位（read_tool_result 回读历史 artifact 用）。 */
    AgentToolCallRecordVO getBySessionAndCallId(Long sessionId, String callId);

    AgentToolCallRecordVO getLatestBySessionAndToolName(Long sessionId, String toolName);

    List<AgentToolCallRecordVO> listBySessionId(Long sessionId, Long runId);

    List<AgentToolCallRecordVO> listByRunId(Long runId, int offset, int limit);

    long countByRunId(Long runId);

    long countBySessionId(Long sessionId);

    Long getLatestRunIdWithToolCalls(Long sessionId);
}
