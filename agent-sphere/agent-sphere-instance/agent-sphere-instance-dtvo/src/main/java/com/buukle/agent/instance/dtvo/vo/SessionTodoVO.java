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

    public SessionTodoVO() {}

    public SessionTodoVO(String content, String status, String priority) {
        this.content = content;
        this.status = status;
        this.priority = priority;
    }
}
