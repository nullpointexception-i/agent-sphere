package com.buukle.agent.sso.dtvo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class SsoExchangeDTO implements Serializable {
    @NotBlank(message = "otc must not be blank")
    private String otc;
}