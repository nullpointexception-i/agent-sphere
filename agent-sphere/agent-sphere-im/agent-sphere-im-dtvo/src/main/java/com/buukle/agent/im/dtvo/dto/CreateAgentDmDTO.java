package com.buukle.agent.im.dtvo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/** Creates or resumes a direct chat between the current user and one Super-Agent. */
@Data
public class CreateAgentDmDTO implements Serializable {

    @NotNull
    private Long agentId;
}
