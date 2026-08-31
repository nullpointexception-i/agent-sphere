package com.buukle.agent.capability.skill.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class BatchUpdateSkillStatusDTO implements Serializable {
    @NotEmpty(message = "ids can't be empty")
    private List<Long> ids;
    @NotBlank(message = "status can't be blank")
    private String status;
}