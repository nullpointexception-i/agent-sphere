package com.buukle.agent.application.dtvo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ApplicationVO {
    private Long id;
    private String appKey;
    private String name;
    private String nameKey;
    private String description;
    private String descriptionKey;
    private String defaultLocale;
    private String icon;
    private String appType;
    private String entryMode;
    private String entryKey;
    private String visibility;
    private Long ownerUserId;
    private Integer sortOrder;
    private String status;
    private LocalDateTime createdAt;
}
