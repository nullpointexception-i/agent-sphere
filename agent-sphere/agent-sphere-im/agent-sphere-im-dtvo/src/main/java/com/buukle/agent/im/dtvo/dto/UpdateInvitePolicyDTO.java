package com.buukle.agent.im.dtvo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class UpdateInvitePolicyDTO implements Serializable {
    @NotNull
    private Boolean allowMemberInvite;
}
