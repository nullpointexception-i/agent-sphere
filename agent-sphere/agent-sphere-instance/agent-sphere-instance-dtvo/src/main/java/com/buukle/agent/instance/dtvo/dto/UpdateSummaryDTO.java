package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class UpdateSummaryDTO implements Serializable {
    @NotBlank(message = "summary can't be blank")
    @Size(min = 1, max = 5000)
    private String summary;
}
