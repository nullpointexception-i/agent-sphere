package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class BatchCreateInstanceCapabilityDTO implements Serializable {
    @NotEmpty(message = "capabilities list can't be empty")
    @Valid
    private List<CapabilityItem> capabilities;

    @Data
    public static class CapabilityItem implements Serializable {
        private Long instanceId;
        private String capabilityType;
        private Long capabilityId;
        private String status;
    }
}
