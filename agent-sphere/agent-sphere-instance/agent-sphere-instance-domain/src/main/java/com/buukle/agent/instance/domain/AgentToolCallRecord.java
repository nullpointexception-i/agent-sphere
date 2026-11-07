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
    /** Owning sub-agent run (NULL=main Agent). */
    private Long subAgentRunId;
    /** LLM turn that produced this call (= agent_llm_interaction_record.turn_no / run.loop_count); NULL=historical row from before the migration. */
    private Integer turnNo;
    /** Browser device that executed this call (reported back by the extension callback). */
    private String deviceId;
    private String deviceName;
    /** Executing surface: CONSOLE / WIDGET. */
    private String execSource;
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
