package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class SendMessageDTO implements Serializable {
    /** 纯图片发送时可为空；与 attachmentKeys 同时为空由服务层校验。 */
    @Size(max = 5000)
    private String message;
    private Long modelRouteId;
    private String delivery;
    private Boolean noClarification;
    private List<String> attachmentKeys;
}
