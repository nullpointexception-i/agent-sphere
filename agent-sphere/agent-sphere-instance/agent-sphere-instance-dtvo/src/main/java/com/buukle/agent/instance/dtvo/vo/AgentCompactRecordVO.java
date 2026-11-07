package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class AgentCompactRecordVO implements Serializable {
    private Long id;
    private Long sessionId;
    /** NULL = session level; non-null = run-local. */
    private Long runId;
    private String status;
    private String summaryBefore;
    private String summaryAfter;
    private Long tokenCount;
    private Long compactedUptoRunId;
    /** Tail messages JSON retained by run-local compaction. */
    private String tailJson;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;
}
