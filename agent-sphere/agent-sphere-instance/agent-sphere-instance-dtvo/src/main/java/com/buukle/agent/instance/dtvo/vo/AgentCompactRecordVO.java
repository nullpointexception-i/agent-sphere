package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;
import java.io.Serializable;

@Data
public class AgentCompactRecordVO implements Serializable {
    private Long id;
    private Long sessionId;
    private String status;
    private String summaryBefore;
    private String summaryAfter;
    private Long tokenCount;
    private Long compactedUptoRunId;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;
}
