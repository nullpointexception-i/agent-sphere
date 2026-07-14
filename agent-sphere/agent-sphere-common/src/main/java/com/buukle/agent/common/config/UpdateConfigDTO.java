package com.buukle.agent.common.config;

import lombok.Data;

import java.io.Serializable;

@Data
public class UpdateConfigDTO implements Serializable {
    private String value;
}
