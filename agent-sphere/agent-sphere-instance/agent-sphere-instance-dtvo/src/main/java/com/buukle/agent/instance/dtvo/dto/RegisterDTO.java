package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class RegisterDTO implements Serializable {
    @NotBlank
    @Size(min = 5, max = 32)
    @Pattern(regexp = "^[a-zA-Z0-9]+$", message = "Username must be letters or digits only")
    private String username;
    @NotBlank
    @Size(min = 6, max = 32)
    private String password;
    @NotBlank
    @Size(min = 6, max = 32)
    private String repeatPassword;
}
