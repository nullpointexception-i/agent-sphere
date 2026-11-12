package com.buukle.agent.instance.dtvo.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class MoveDocumentDTO implements Serializable {
    /** Target folder; null or 0 moves the document to "unfiled". */
    private Long folderId;
}
