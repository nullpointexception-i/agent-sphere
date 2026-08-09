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
    /** 执行日志（LLM 交互 + 工具调用，按时间正序，增量拉取）。 */
    private List<TaskExecutionLogVO> executionLogs;
    /** LLM 交互记录总条数（增量 offset 判断）。 */
    private Integer executionLogCount;
    /** 工具调用记录总条数（增量 offset 判断）。 */
    private Integer toolLogCount;
}

