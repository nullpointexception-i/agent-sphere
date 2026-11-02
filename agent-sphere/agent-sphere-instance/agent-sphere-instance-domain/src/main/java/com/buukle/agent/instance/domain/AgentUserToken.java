package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录会话（一个用户可同时持有多个有效 token）。
 *
 * <p>替代 {@code agent_user.token} 单列：旧实现登录即覆盖，主站与 widget 会互相踢下线。
 * 这里只存 token 的 SHA-256 摘要，明文只在签发响应里出现一次。
 */
@Data
@TableName("agent_user_token")
public class AgentUserToken {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String tokenHash;
    /** token 前 8 位，仅供排查；不参与鉴权。 */
    private String tokenPrefix;
    /** 签发来源：CONSOLE / WIDGET / INTERNAL / LEGACY。 */
    private String source;
    private String userAgent;
    private String loginIp;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime revokedAt;
    private String status;
    private String remark;
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
