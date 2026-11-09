package com.buukle.agent.im.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_participant")
public class ImParticipant {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
    private Long conversationId;
    /** USER / AGENT / SYSTEM */
    private String participantType;
    /** USER: agent_user.id；AGENT: agent_super_agent.id */
    private Long participantId;
    /** OWNER / ADMIN / MEMBER / BOT */
    private String role;
    private String displayName;
    private Boolean muted;
    /** True when this participant was auto-added from a member's public avatar catalogue. */
    private Boolean autoAdded;
    private LocalDateTime joinedAt;
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
