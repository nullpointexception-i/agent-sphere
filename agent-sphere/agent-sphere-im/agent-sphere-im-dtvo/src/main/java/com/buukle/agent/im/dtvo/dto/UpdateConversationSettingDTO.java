package com.buukle.agent.im.dtvo.dto;

import lombok.Data;

import java.io.Serializable;

/** Per-user conversation preferences that can be patched independently. */
@Data
public class UpdateConversationSettingDTO implements Serializable {
    private Boolean pinned;
    private Boolean muted;
    private Boolean hidden;
    private String remark;
}
