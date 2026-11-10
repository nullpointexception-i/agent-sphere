package com.buukle.agent.superagent.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/** Task failure reported by the desktop driver. */
@Data
public class LocalTaskFailDTO implements Serializable {

    @NotBlank
    private String leaseToken;

    private String errorCode;

    private String errorMessage;
}
