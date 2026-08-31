package com.buukle.agent.capability.skill.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class UpdateSkillStatusDTO implements Serializable {
    @NotBlank(message = "status can't be blank")
    private String status;
}