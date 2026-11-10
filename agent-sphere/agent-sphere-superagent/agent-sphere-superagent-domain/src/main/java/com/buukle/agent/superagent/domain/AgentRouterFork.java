package com.buukle.agent.superagent.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One soft-forked Router execution. A fork is created only while the parent Router session is busy;
 * it runs one delivery batch in parallel and merges back strictly by event sequence.
 */
@Data
@TableName("agent_router_fork")
public class AgentRouterFork {

    public static final String KIND_ROUTER = "ROUTER_FORK";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_COMPLETED = "COMPLETED";
    public static final String STATE_WAITING_SEQ = "WAITING_SEQ";
    public static final String STATE_MERGING = "MERGING";
    public static final String STATE_MERGED = "MERGED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_TIMEOUT = "TIMEOUT";
    public static final String STATUS_ACTIVE = "ACTIVE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long parentSessionId;
    private Long childSessionId;
    private Long childRunId;
    private Long threadId;
    private Long superAgentId;
    private Long seqFrom;
    private Long seqTo;
    /** RUNNING / COMPLETED / WAITING_SEQ / MERGING / MERGED / FAILED / TIMEOUT */
    private String state;
    private String status;
    private String remark;
    private String resultSummary;
    private String replyText;
    private String mergeError;
    private LocalDateTime mergedAt;
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic(value = "false", delval = "true")
    private Boolean deleteFlag;
}
