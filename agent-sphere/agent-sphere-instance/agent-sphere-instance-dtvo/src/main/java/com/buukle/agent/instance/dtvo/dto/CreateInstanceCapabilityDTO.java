package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.io.Serializable;

@Data
public class CreateInstanceCapabilityDTO implements Serializable {
    @NotNull(message = "instanceId can't be null")
    private Long instanceId;
    @NotBlank(message = "capabilityType can't be blank")
    @Size(min = 1, max = 64)
    private String capabilityType;
    @NotNull(message = "capabilityId can't be null")
    private Long capabilityId;
    @NotBlank
    @Size(min = 1, max = 64)
    private String status;
}
