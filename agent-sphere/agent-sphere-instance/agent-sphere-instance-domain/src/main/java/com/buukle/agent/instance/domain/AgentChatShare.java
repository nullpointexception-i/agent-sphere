package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_chat_share")
public class AgentChatShare {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String shareToken;
    private Long sessionId;
    private Long anchorRunId;
    private Integer runCount;
    private String createdBy;
    private String status;
    private Long tenantId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Boolean deleteFlag;
}
