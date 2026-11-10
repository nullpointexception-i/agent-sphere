package com.buukle.agent.superagent.dtvo.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/** Update a local runtime name or lifecycle status. */
@Data
public class UpdateLocalRuntimeDTO implements Serializable {

    @Size(max = 128)
    private String name;

    /** ACTIVE or DISABLED. */
    @Size(max = 32)
    private String status;
}
