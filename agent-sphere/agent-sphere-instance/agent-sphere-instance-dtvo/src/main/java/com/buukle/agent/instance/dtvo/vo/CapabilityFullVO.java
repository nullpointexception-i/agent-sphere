package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class CapabilityFullVO extends CapabilityVO {
    private String name;
    private String description;
    private String endpoint;
    private String serverType;
    private String authConfig;
    private String toolSchema;
}
