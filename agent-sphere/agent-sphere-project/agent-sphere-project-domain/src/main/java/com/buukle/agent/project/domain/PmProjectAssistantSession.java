package com.buukle.agent.project.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Binds a canonical or fork PROJECT_ROUTER session to its project and requester. */
@Data
@TableName("pm_project_assistant_session")
public class PmProjectAssistantSession {
    public static final String ROLE_CANONICAL = "CANONICAL";
    public static final String ROLE_FORK = "FORK";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long sessionId;
    private Long canonicalSessionId;
    private Long requesterUserId;
    private String sessionRole;
    private String status;
    private String remark;
    @TableLogic(value = "false", delval = "true")
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
