package com.buukle.agent.im.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class NotificationVO implements Serializable {
    private Long id;
    private String type;
    private String titleKey;
    private String bodyKey;
    /** Raw JSON object; the client parses it for rendering parameters. */
    private String payload;
    private String sourceType;
    private Long sourceId;
    private Boolean read;
    private LocalDateTime createdAt;
}
