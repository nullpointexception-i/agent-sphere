package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** Lightweight document row used by the workspace document grid. */
@Data
public class DocumentSummaryVO implements Serializable {
    private Long id;
    private String title;
    private String contentType;
    private Integer version;
    private Long sessionId;
    private Long instanceId;
    private Long folderId;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** Character count of the stored content. */
    private Integer contentLength;
    /** First characters of the content, for the card preview. */
    private String preview;
    /** Number of page comments, excluding deleted ones. */
    private Long commentCount;
}
