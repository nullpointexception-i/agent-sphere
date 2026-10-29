package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class ToolApprovalVO implements Serializable {
    private Long id;
    private String approvalId;
    private Long sessionId;
    private Long runId;
    private String toolCallsJson;
    private String decisionsJson;
    private String status;
}
