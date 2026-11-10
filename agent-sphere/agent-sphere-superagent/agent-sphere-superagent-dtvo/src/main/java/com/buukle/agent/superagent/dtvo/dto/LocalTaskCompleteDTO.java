package com.buukle.agent.superagent.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/** Final reply from the desktop driver. */
@Data
public class LocalTaskCompleteDTO implements Serializable {

    @NotBlank
    private String leaseToken;

    @NotBlank
    private String text;

    private String sessionRef;
}
