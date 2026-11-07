package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class SessionTodoVO implements Serializable {
    private Long id;
    private Long sessionId;
    private Long runId;
    private String content;
    private String status;
    private String priority;
    private Integer sortOrder;
    /** Source skill id (null for non-skill items). */
    private Long skillId;
    /** Source skill step id, e.g. step1 / step1.1 (null for non-skill items). */
    private String stepId;

    public SessionTodoVO() {
    }

    public SessionTodoVO(String content, String status, String priority) {
        this.content = content;
        this.status = status;
        this.priority = priority;
    }
}
