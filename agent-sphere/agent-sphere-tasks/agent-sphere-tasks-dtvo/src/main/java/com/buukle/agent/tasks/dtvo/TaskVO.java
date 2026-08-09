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
    /** 思考过程（run 的 LLM 交互记录，增量拉取）。 */
    private List<TaskThinkingVO> thinkingRecords;
    /** 思考过程总条数（供调用方增量 offset 判断）。 */
    private Integer thinkingCount;
}

