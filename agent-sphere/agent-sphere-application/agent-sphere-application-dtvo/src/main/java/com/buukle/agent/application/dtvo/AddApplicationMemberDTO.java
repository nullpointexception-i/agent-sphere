package com.buukle.agent.application.dtvo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddApplicationMemberDTO {
    @NotNull
    private Long userId;
    private String memberRole;
}
