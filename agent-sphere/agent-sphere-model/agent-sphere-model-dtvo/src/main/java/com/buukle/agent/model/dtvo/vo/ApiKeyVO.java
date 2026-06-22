package com.buukle.agent.model.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class ApiKeyVO implements Serializable {
    private Long id;
    private Long providerId;
    private String alias;
    private String keyValue;
    private String expiresAt;
    private String status;
    private String createdAt;
    private String createdBy;
    private String updatedBy;
    private String updatedAt;
}
