package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.io.Serializable;

@Data
public class LoginDTO implements Serializable {
    @NotBlank
    @Size(min = 5, max = 32)
    private String username;
    @NotBlank
    @Size(min = 6, max = 32)
    private String password;
}
