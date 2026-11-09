package com.buukle.agent.im.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class UpdateParticipantRoleDTO implements Serializable {
    /** ADMIN or MEMBER. */
    @NotBlank
    private String role;
}
