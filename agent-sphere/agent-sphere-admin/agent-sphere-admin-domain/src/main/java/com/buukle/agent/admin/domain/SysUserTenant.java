package com.buukle.agent.admin.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** User <-> tenant assignment. One user may belong to several tenants. */
@Data
@TableName("sys_user_tenant")
public class SysUserTenant {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    /** SELF_ONLY / TENANT / TENANT_SUBTREE. */
    private String visibility;

    /** The tenant that new rows belong to and the UI shows. At most one per user. */
    private Boolean isCurrent;

    private String status;

    private String remark;

    @TableLogic
    private Integer deleteFlag;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
