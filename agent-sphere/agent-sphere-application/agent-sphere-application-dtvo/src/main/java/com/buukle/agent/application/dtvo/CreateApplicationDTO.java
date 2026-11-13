package com.buukle.agent.application.dtvo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateApplicationDTO {
    @NotBlank
    @Size(max = 64)
    private String appKey;
    @NotBlank
    @Size(max = 128)
    private String name;
    @Size(max = 512)
    private String description;
    @Size(max = 256)
    private String icon;
    private String entryMode;
    @Size(max = 64)
    private String entryKey;
    private String visibility;
}
