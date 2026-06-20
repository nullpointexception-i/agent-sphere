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
    private String createdAt;
    private String updatedAt;
}
