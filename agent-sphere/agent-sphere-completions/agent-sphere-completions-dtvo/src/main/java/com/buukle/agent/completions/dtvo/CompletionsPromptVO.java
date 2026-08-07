package com.buukle.agent.completions.dtvo;

import lombok.Data;

import java.io.Serializable;

@Data
public class CompletionsPromptVO implements Serializable {
    private Long id;
    private Integer version;
    private String promptSystem;
    private String promptUser;
    private String remark;
    private String createdAt;
    private String updatedAt;
}
