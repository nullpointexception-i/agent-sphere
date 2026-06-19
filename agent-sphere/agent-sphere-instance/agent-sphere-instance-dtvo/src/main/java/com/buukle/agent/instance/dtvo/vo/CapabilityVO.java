package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;
import java.io.Serializable;

@Data
public class CapabilityVO implements Serializable {
    private Long id;
    private String capabilityType;
    private Long capabilityId;
    private String status;
}
