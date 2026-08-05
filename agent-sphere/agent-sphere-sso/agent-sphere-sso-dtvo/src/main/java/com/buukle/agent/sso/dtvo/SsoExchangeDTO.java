package com.buukle.agent.sso.dtvo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class SsoExchangeDTO implements Serializable {
    @NotBlank(message = "otc 不能为空")
    private String otc;
}