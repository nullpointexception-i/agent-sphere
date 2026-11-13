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
@TableName("agent_work_item")
public class AgentWorkItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
    private Long ownerUserId;
    private Long superAgentId;
    private Long threadId;
    private Long workerSessionId;
    private Long triggerEventId;
    private Integer attempt;
    private String title;
    private String goal;
    private Integer priority;
    private String status;
    private String runMode;
    private String requestSource;
    private String sourceRef;
    private String idempotencyKey;
    private String entityType;
    private Long entityId;
    private Long sceneId;
    private String summary;
    private String blockedReason;
    private String speculationKey;
    private LocalDateTime lastProgressAt;
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
