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
    /** LLM turn that produced this call; NULL=historical row from before the migration (recovery falls back to time-window pairing). */
    private Integer turnNo;
    /** Browser device that executed this call (reported back by the extension callback). */
    private String deviceId;
    private String deviceName;
    /** Executing surface: CONSOLE / WIDGET. */
    private String execSource;
    private String createdAt;
    /** Epoch milliseconds of createdAt (millisecond precision; only for historical rebuild pairing, not for display). */
    private Long createdAtMillis;
    private String updatedAt;
}
