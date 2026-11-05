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
@TableName("agent_watchdog_run")
public class AgentWatchdogRun {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String status;
    private Integer dispatchedStuck;
    private Integer deliveryStuck;
    private Integer outboundStuck;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
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
