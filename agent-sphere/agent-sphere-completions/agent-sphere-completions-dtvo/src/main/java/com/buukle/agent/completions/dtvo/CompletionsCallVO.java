package com.buukle.agent.completions.dtvo;

import lombok.Data;

import java.io.Serializable;

@Data
public class CompletionsCallVO implements Serializable {
    private Long id;
    private Long completionsId;
    private Long promptId;
    private String input;
    private String output;
    private String model;
    private String usage;
    private String status;
    private String caller;
    private String remark;
    private String createdAt;
}
