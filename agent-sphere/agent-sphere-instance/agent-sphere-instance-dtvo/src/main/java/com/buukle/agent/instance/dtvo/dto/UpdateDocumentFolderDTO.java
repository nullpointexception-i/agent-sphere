package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class UpdateDocumentFolderDTO implements Serializable {

    @Size(max = 128)
    private String name;

    /** Move the folder under another folder; null keeps the current parent. */
    private Long parentId;

    /** Explicitly move to the root when true. */
    private Boolean moveToRoot;
}
