package com.buukle.agent.superagent.dtvo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/** Rebind a local avatar to another desktop runtime. */
@Data
public class UpdateSuperAgentLocalRuntimeDTO implements Serializable {

    @NotNull
    private Long localRuntimeId;
}
