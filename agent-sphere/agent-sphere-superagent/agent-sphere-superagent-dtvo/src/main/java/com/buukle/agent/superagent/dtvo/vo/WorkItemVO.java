package com.buukle.agent.superagent.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class WorkItemVO implements Serializable {
    private Long id;
    private Long superAgentId;
    private Long threadId;
    private Long workerSessionId;
    private Long triggerEventId;
    private Integer attempt;
    private String title;
    private String goal;
    private Integer priority;
    private String status;
    private String summary;
    private String lastProgressAt;
    private String createdAt;
}
