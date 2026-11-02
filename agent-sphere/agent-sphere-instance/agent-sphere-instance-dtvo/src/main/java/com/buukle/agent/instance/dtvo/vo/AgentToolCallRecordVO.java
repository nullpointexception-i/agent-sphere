package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class AgentToolCallRecordVO implements Serializable {
    private Long id;
    private Long stepId;
    private String callId;
    private Long runId;
    private Long sessionId;
    private String toolName;
    private String displayNameCn;
    private String displayNameEn;
    private String argumentsJson;
    private String compressedArguments;
    private String artifact;
    private String compressedArtifact;
    private String status;
    private String errorMessage;
    private Long subAgentRunId;
    private String createdAt;
    /** createdAt 的 epoch 毫秒（精确到毫秒，仅供历史重建配对使用，不参与展示）。 */
    private Long createdAtMillis;
    private String updatedAt;
}
