package com.buukle.agent.tasks.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_task")
public class AgentTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String goal;
    private String contextJson;
    private String expectedOutputJson;
    private String config;
    private Long instanceId;
    private Long sessionId;
    private Long runId;
    private String status;
    private String resultJson;
    /** 当前轮询阶段（execute → extract → refine），DB 化轮询用，防重复推进。 */
    private String pollPhase;
    /** 上次轮询时间（DB 化轮询认领条件）。 */
    private LocalDateTime polledAt;
    /** 任务开始时间（超时兜底基准）。 */
    private LocalDateTime startedAt;
    private String remark;
    private String callbackUrl;
    @TableLogic
    private Boolean deleteFlag;
    private Long tenantId;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
