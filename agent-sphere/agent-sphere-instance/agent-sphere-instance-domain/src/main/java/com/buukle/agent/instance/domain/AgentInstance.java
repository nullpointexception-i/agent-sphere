package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_instance")
public class AgentInstance {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String systemPrompt;
    private Long modelRouteId;
    private String customInstructions;
    private String image;
    private String status;
    private String businessType;

    /**
     * Whether the **console site** may start/continue sessions against this instance (V89).
     *
     * <p>Together with {@link #allowWidgetSession} it controls access by client surface; the decision
     * is made centrally by {@code SessionSurfacePolicy}.
     */
    private Boolean allowConsoleSession;

    /** Whether the **embedded widget** may start/continue sessions against this instance (V89). */
    private Boolean allowWidgetSession;
    /** Maximum loop count for a single run (overrides the system runner/task limits; NULL/0 = not configured, use the system default). */
    private Integer maxLoopCount;
    /** Completion contract for tool-using runs; defaults to STRICT_FINISH. */
    private String completionProtocol;
    /** Super-Agent role: CHAT / GROUP_ROUTER / ROUTER / WORKER / WATCHDOG. */
    private String instanceRole;
    /** USER: private to the creator; TENANT: tenant-level resource (tenant administrators). */
    private String ownerScope;
    /** Sampling/request parameter configuration (JSON, same shape as LlmSamplingConfigDTO; NULL = use the global fallback). */
    private String config;
    @TableLogic
    private Boolean deleteFlag;
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
}
