package com.buukle.agent.capability.mcp.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class McpVO implements Serializable {
    private Long id;
    private String name;
    private String description;
    private String serverUrl;
    private String serverType;
    private String authConfig;
    private String toolDefinitions;
    private String status;
    private String createdAt;
    private String createdBy;
    private String updatedBy;
    private String updatedAt;
}
