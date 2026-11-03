package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_tool_call_record")
public class AgentToolCallRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long stepId;
    private String callId;
    private Long runId;
    private Long sessionId;
    private String toolName;
    @TableField("display_name_cn")
    private String displayNameCn;
    @TableField("display_name_en")
    private String displayNameEn;
    private String argumentsJson;
    private String compressedArguments;
    private String artifact;
    private String compressedArtifact;
    private String status;
    private String errorMessage;
    /** 归属的子 Agent 运行（NULL=主 Agent）。 */
    private Long subAgentRunId;
    /** 产生该调用的 LLM 轮次（= agent_llm_interaction_record.turn_no / run.loop_count）；NULL=迁移前的历史行。 */
    private Integer turnNo;
    /** 执行该调用的浏览器设备（扩展回调回传）。 */
    private String deviceId;
    private String deviceName;
    /** 执行来源端：CONSOLE / WIDGET。 */
    private String execSource;
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
