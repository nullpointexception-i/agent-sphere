package com.buukle.agent.capability.builtin.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class BuiltinToolVO implements Serializable {
    private Long id;
    private String name;
    private String description;
    private String paramSchema;
    private String responseSchema;
    private boolean needConfig;
    private String displayNameCn;
    private String displayNameEn;
}
