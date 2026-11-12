package com.buukle.agent.instance.repository;

import lombok.Data;

/** Comment count per document, used by the document grid. */
@Data
public class DocumentCommentCount {
    private Long documentId;
    private Long commentCount;
}
