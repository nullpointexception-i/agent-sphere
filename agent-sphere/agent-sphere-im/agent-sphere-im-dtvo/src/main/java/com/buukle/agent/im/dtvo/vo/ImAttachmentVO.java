package com.buukle.agent.im.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/** Uploaded IM attachment metadata. */
@Data
public class ImAttachmentVO implements Serializable {
    private String fileKey;
    private String fileName;
    private String contentType;
    private Long sizeBytes;
    /** Relative download URL scoped to the conversation. */
    private String url;
}
