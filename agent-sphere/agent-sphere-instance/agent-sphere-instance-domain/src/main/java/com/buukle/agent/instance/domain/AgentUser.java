package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("agent_user")
public class AgentUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String displayName;
    private String englishName;
    private String email;
    private String avatar;
    private String token;
    private String status;
    private String superAdmin;
    @TableLogic
    private Boolean deleteFlag;
    private Long tenantId;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
