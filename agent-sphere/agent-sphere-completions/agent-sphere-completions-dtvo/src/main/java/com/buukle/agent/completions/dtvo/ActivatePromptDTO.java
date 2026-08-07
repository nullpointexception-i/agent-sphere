package com.buukle.agent.completions.dtvo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class ActivatePromptDTO implements Serializable {
    @NotNull(message = "promptId can't be null")
    private Long promptId;
}
