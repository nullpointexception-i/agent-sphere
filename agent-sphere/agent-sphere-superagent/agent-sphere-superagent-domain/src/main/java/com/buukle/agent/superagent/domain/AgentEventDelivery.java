package com.buukle.agent.superagent.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_event_delivery")
public class AgentEventDelivery {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
    private Long eventId;
    private Long superAgentId;
    private Long targetSessionId;
    private Long routerRunId;
    private Long targetTaskId;
    private String decision;
    private String reason;
    private String status;
    private Integer attempts;
    private LocalDateTime nextRetryAt;
    private LocalDateTime claimedAt;
    private LocalDateTime deliveredAt;
    private String actorType;
    private Long actorId;
    private String remark;
    @TableLogic(value = "false", delval = "true")
    private Boolean deleteFlag;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
