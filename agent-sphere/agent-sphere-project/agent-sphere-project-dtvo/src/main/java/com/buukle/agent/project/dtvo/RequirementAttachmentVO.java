package com.buukle.agent.project.dtvo;

import lombok.Data;

@Data
public class RequirementAttachmentVO {
    private String fileKey;
    private String fileName;
    private String contentType;
    private Long sizeBytes;
}
