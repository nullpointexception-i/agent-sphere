package com.buukle.agent.capability.mcp.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/** Tenant MCP Hub row plus viewer-specific state. */
@Data
public class McpHubVO implements Serializable {

    private Long id;
    private Long tenantId;
    private Long sourceMcpId;
    private Long publisherUserId;
    private String publisherName;
    private String name;
    private String description;
    private String serverUrl;
    private String serverType;
    private String authConfig;
    private String toolDefinitions;
    private Boolean compressEnabled;
    private String accessMode;
    private String securityLevel;
    private String approvalScope;

    private Integer version;
    private Integer sourceVersion;
    private Integer installCount;
    private Integer followCount;

    private Boolean followed;
    private Long installedMcpId;
    /** True when the viewer published this entry or owns its source MCP. */
    private Boolean mine;

    private String createdAt;
    private String updatedAt;
}
