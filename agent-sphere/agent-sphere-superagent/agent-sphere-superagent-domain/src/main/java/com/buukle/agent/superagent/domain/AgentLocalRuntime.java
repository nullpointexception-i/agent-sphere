package com.buukle.agent.superagent.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** A desktop-managed local agent runtime. */
@Data
@TableName("agent_local_runtime")
public class AgentLocalRuntime {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
    private Long ownerUserId;
    private String name;
    private String runtimeKind;
    private String deviceKey;
    private String tokenHash;
    private String tokenPrefix;
    private String os;
    private String arch;
    private String appVersion;
    private String runtimeVersion;
    private String capabilities;
    private String config;
    private String status;
    private LocalDateTime lastSeenAt;
    private String lastIp;
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
