package com.buukle.agent.tasks.dtvo;

import lombok.Data;

import java.io.Serializable;

/** 任务执行日志记录（合并 LLM 交互 + 工具调用，按时间正序）。 */
@Data
public class TaskExecutionLogVO implements Serializable {

    public static final String TYPE_LLM = "LLM";
    public static final String TYPE_TOOL = "TOOL";

    private Long id;
    private Long runId;
    /** 日志类型：LLM / TOOL。 */
    private String logType;
    private String createdAt;
    // LLM 交互字段
    private String interactionType;
    private String modelName;
    private String responseBody;
    private Boolean success;
    // 工具调用字段
    private String toolName;
    private String displayNameCn;
    private String argumentsJson;
    private String artifact;
    private String status;
    private String errorMessage;
}
