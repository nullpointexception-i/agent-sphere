package com.buukle.agent.sso.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_sso_identity")
public class SsoIdentity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String providerCode;
    private String subject;
    /** Third-party login username (for example the IdP's preferred_username), shown as provider@username in the top-right corner; falls back to subject when empty */
    private String displaySubject;
    private Long agentUserId;
    private String status;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}