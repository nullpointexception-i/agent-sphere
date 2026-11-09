package com.buukle.agent.capability.mcp.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Tenant-owned MCP Hub entry: a snapshot published from one local MCP. */
@Data
@TableName("capability_mcp_hub")
public class CapabilityMcpHub {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long sourceMcpId;
    private Long publisherUserId;
    private String name;
    private String description;
    private String serverUrl;
    private String serverType;
    /** Shared with the tenant and ancestor-visible child tenants. */
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
    private String status;

    @TableLogic
    private Boolean deleteFlag;

    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
