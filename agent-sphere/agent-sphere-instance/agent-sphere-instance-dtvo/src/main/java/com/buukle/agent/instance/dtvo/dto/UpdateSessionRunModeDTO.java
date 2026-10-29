package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class UpdateSessionRunModeDTO implements Serializable {
    @NotBlank(message = "runMode can't be blank")
    private String runMode;
}
