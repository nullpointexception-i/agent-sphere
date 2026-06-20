package com.buukle.agent.capability.builtin.tool.spi.dtvo;

import lombok.Data;

import java.io.Serializable;

@Data
public class ToolInfoVO implements Serializable {
    private String name;
    private String description;
    private String paramSchema;
    private String responseSchema;
    private String displayNameCn;
    private String displayNameEn;
}
