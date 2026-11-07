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
@TableName("im_message")
public class ImMessage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long conversationId;
    private Long seq;
    /** USER / AGENT / SYSTEM */
    private String senderType;
    private Long senderId;
    private String clientMsgId;
    /** TEXT / MARKDOWN / CARD / FILE / IMAGE / SYSTEM */
    private String type;
    /** JSON：{text, fileKey, card...} */
    private String content;
    private Long replyToId;
    /** JSON array: mentioned participants */
    private String mentions;
    /** SENT / EDITED / RECALLED / DELETED */
    private String status;
    private String actorType;
    private Long actorId;
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
