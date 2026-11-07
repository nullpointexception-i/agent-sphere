package com.buukle.agent.tasks.dtvo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class TaskVO implements Serializable {
    private Long id;
    private String goal;
    private String status;
    private Long instanceId;
    private Long sessionId;
    private Long runId;
    private String contextJson;
    private String expectedOutputJson;
    private String config;
    private String resultJson;
    private String remark;
    private String callbackUrl;
    private String createdAt;
    private String updatedAt;
    /** Execution logs (LLM interactions + tool calls, ascending time order, incrementally fetched). */
    private List<TaskExecutionLogVO> executionLogs;
    /** Total LLM interaction records (for incremental offset checks). */
    private Integer executionLogCount;
    /** Total tool call records (for incremental offset checks). */
    private Integer toolLogCount;
}

