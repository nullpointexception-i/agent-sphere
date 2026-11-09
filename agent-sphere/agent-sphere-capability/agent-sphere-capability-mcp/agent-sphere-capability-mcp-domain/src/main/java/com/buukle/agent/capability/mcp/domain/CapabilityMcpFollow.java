package com.buukle.agent.capability.mcp.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** User subscription to a tenant MCP Hub entry. Following creates no local MCP copy. */
@Data
@TableName("capability_mcp_follow")
public class CapabilityMcpFollow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long userId;
    private Long hubMcpId;

    @TableLogic
    private Boolean deleteFlag;

    private LocalDateTime createdAt;
}
