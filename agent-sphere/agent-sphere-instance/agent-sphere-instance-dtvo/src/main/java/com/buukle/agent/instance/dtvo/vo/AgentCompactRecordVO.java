package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class AgentCompactRecordVO implements Serializable {
    private Long id;
    private Long sessionId;
    /** NULL = session 级；非空 = run-local。 */
    private Long runId;
    private String status;
    private String summaryBefore;
    private String summaryAfter;
    private Long tokenCount;
    private Long compactedUptoRunId;
    /** run-local 压缩保留的尾部消息 JSON。 */
    private String tailJson;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;
}
