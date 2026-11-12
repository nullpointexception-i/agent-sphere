package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/** One node of the personal wiki page tree. */
@Data
public class DocumentFolderVO implements Serializable {
    private Long id;
    private Long parentId;
    private String name;
    private Long instanceId;
    /** INSTANCE_ROOT for the system "avatar documents" folder. */
    private String systemKey;
    private Integer sortOrder;
    private Long documentCount;
}
