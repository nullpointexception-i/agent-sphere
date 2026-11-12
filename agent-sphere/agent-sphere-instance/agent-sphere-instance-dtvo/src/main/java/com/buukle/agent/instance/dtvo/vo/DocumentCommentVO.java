package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** One page-level wiki comment or a one-level reply. */
@Data
public class DocumentCommentVO implements Serializable {
    private Long id;
    private Long documentId;
    private Long parentId;
    private String authorName;
    private String content;
    private String quotedText;
    private String status;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** True when the current caller wrote the comment. */
    private boolean mine;
    /** True when the current caller may delete it or toggle resolved state. */
    private boolean canModerate;
}
