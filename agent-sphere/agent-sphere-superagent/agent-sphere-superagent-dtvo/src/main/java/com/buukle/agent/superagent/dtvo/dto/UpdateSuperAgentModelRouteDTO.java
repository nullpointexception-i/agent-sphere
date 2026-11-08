package com.buukle.agent.superagent.dtvo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/** Avatar-level model route used by both managed instances. */
@Data
public class UpdateSuperAgentModelRouteDTO implements Serializable {

    @NotNull
    private Long modelRouteId;
}
