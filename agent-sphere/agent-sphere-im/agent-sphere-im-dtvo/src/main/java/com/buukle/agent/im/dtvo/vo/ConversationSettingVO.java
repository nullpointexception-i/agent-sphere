package com.buukle.agent.im.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class ConversationSettingVO implements Serializable {
    private Long conversationId;
    private Boolean pinned;
    private Boolean muted;
    private Boolean hidden;
    private String remark;
}
