package com.buukle.agent.capability.builtin.dtvo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.io.Serializable;

@Data
@AllArgsConstructor
public class ParameterVerifyResultVO implements Serializable {
    private boolean valid;
    private String errorMessage;
}
