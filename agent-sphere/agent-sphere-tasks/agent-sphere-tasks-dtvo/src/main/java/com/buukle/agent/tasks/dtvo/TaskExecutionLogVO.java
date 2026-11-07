package com.buukle.agent.tasks.dtvo;

import lombok.Data;

import java.io.Serializable;

/** Task execution log record (LLM interactions + tool calls merged, in ascending time order). */
@Data
public class TaskExecutionLogVO implements Serializable {

    public static final String TYPE_LLM = "LLM";
    public static final String TYPE_TOOL = "TOOL";

    private Long id;
    private Long runId;
    /** Log type: LLM / TOOL. */
    private String logType;
    private String createdAt;
    // LLM interaction fields
    private String interactionType;
    private String modelName;
    private String responseBody;
    private Boolean success;
    // Tool call fields
    private String toolName;
    private String displayNameCn;
    private String argumentsJson;
    private String artifact;
    private String status;
    private String errorMessage;
}
