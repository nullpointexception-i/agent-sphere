package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class MemoryVO implements Serializable {
    private Long id;
    private String type;
    private Long sessionId;
    private Long runId;
    private Long taskId;
    private String summary;
    private String content;
    private String status;
    private String createdAt;
}
