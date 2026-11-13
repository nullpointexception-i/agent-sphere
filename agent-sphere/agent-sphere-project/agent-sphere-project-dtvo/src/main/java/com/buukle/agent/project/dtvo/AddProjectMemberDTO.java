package com.buukle.agent.project.dtvo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddProjectMemberDTO {
    @NotNull
    private Long userId;
    private String memberRole;
}
