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
    /** Initial members (user IDs, excluding the creator). */
    private List<Long> participantUserIds;
    /** Group-level dispatch rules (JSON string). */
    private String gateConfig;
}
