package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class DocumentVO implements Serializable {
    private Long id;
    private String title;
    private String content;
    private String contentType;
    private Long sessionId;
    private Long instanceId;
    private Long runId;
    private String shareToken;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
