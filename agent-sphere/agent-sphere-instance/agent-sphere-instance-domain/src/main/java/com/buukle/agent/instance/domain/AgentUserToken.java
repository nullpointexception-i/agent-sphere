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
 * Login session (a single user may hold several valid tokens at once).
 *
 * <p>Replaces the single {@code agent_user.token} column: the old implementation overwrote it on
 * login, so the console site and the widget kept kicking each other offline.
 * Only the SHA-256 digest of the token is stored here; the plaintext appears only once in the
 * issuance response.
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
    /** First 8 characters of the token, for troubleshooting only; not used for authentication. */
    private String tokenPrefix;
    /** Issuing surface: CONSOLE / WIDGET / INTERNAL / LEGACY. */
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
