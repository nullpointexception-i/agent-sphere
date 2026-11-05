package com.buukle.agent.im.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class CreateConversationDTO implements Serializable {

    /** DM / GROUP */
    @NotBlank
    private String type;
    @Size(max = 256)
    private String title;
    @Size(max = 512)
    private String purpose;
    /** 初始成员（用户 ID，不含创建者）。 */
    private List<Long> participantUserIds;
    /** 群级分派规则（JSON 字符串）。 */
    private String gateConfig;
}
