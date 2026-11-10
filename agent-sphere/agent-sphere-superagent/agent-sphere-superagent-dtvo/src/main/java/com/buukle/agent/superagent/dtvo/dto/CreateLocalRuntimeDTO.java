package com.buukle.agent.superagent.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/** Create a desktop-managed local runtime. */
@Data
public class CreateLocalRuntimeDTO implements Serializable {

    @NotBlank
    @Size(max = 128)
    private String name;

    /** OPENCODE or CODEX. */
    @NotBlank
    @Size(max = 32)
    private String runtimeKind;

    @Size(max = 64)
    private String deviceKey;

    @Size(max = 32)
    private String os;

    @Size(max = 32)
    private String arch;

    @Size(max = 32)
    private String appVersion;

    @Size(max = 32)
    private String runtimeVersion;
}
