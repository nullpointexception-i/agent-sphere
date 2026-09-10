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
    /** 单次 run 最大循环次数（覆盖系统 runner/task 上限；NULL/0=未配置走系统默认）。 */
    private Integer maxLoopCount;
    /** 采样/请求参数配置（JSON，与 LlmSamplingConfigDTO 同形状；NULL=走全局兜底）。 */
    private String config;
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
