package com.buukle.agent.superagent.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** Task list row for the desktop status page. */
@Data
public class LocalTaskVO implements Serializable {
    private Long taskId;
    private Long agentId;
    private Long threadId;
    private String status;
    private String sessionRef;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
