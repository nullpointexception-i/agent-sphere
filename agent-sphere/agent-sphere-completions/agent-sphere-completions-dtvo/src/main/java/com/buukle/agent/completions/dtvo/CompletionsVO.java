package com.buukle.agent.completions.dtvo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class CompletionsVO implements Serializable {
    private Long id;
    private String name;
    private String description;
    private Long modelRouteId;
    private Long activePromptId;
    private String inputSchema;
    private String outputSchema;
    private String config;
    private String status;
    private String remark;
    private String businessType;
    private String createdBy;
    private List<CompletionsPromptVO> prompts;
    private String createdAt;
    private String updatedAt;
}
