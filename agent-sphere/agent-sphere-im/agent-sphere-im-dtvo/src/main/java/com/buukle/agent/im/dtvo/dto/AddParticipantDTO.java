package com.buukle.agent.im.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class AddParticipantDTO implements Serializable {

    /** USER / AGENT */
    @NotBlank
    private String participantType;
    @NotNull
    private Long participantId;
    /** OWNER / ADMIN / MEMBER / BOT；默认 MEMBER。 */
    private String role;
    @Size(max = 128)
    private String displayName;
}
