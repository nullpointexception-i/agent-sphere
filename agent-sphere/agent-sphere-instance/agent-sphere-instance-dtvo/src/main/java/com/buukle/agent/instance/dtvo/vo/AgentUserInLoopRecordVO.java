package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class AgentUserInLoopRecordVO implements Serializable {
    private Long id;
    private Long stepId;
    private Long runId;
    private Long sessionId;
    private String interactionType;
    private String status;
    private String prompt;
    private String response;
    private String respondedBy;
    private String result;
    private String comment;
    private String createdAt;
    private String updatedAt;
}
