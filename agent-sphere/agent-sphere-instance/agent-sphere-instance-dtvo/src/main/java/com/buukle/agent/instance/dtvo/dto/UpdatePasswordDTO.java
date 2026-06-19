package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdatePasswordDTO {
    @NotBlank
    @Size(min = 6, max = 32)
    private String oldPassword;
    @NotBlank
    @Size(min = 6, max = 32)
    private String newPassword;
}
