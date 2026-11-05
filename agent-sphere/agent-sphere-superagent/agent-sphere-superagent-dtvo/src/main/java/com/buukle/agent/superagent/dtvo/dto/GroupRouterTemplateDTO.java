package com.buukle.agent.superagent.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/** Request body for updating the shared Group Router prompt template. */
@Data
public class GroupRouterTemplateDTO implements Serializable {

    @NotBlank(message = "template can't be blank")
    @Size(max = 5000, message = "template must be <= 5000 chars")
    private String template;
}
