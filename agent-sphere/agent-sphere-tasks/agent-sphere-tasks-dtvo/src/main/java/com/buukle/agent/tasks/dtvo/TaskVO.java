package com.buukle.agent.tasks.dtvo;

import lombok.Data;

import java.io.Serializable;

@Data
public class TaskVO implements Serializable {
    private Long id;
    private String goal;
    private String status;
    private Long instanceId;
    private Long sessionId;
    private Long runId;
    private String resultJson;
    private String remark;
    private String createdAt;
    private String updatedAt;
}
