package com.buukle.agent.runtime.kernel.port.vo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class RuntimeEventDataVO {
    private Long sessionId;
    private Long runId;
    private Long taskId;
    private Long stepId;
    private String type;
    private String nodeName;
    private String toolName;
    private String displayNameCn;
    private String displayNameEn;
    private String argumentsJson;
    private String prompt;
    private String approvedBy;
    private String comment;
    private String response;
    private String errorMessage;
    private Boolean passed;
    private String status;
    private String assistantReply;
    private String artifact;
    private String reasoningType;
    private String reasoningSubType;
    private String publishId;
    private String screenshot;
    private String clarificationId;
}
