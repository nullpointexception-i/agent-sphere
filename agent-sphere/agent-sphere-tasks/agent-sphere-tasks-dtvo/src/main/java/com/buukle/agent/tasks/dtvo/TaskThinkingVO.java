package com.buukle.agent.tasks.dtvo;

import lombok.Data;

import java.io.Serializable;

/** 任务思考过程记录（run 的 LLM 交互记录，增量拉取）。 */
@Data
public class TaskThinkingVO implements Serializable {
    private Long id;
    private String interactionType;
    private String modelName;
    private String responseBody;
    private Boolean success;
    private String errorMessage;
    private String createdAt;
}
