package com.buukle.agent.sso.dtvo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class SetProviderEnabledDTO implements Serializable {
    @NotNull(message = "enabled can't be null")
    private Boolean enabled;
}