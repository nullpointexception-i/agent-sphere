package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;
import java.io.Serializable;

@Data
public class InstanceVO implements Serializable {
    private Long id;
    private String name;
    private String description;
    private String systemPrompt;
    private Long modelRouteId;
    private String customInstructions;
    private String image;
    private String status;
    private String createdAt;
    private String createdBy;
    private String updatedBy;
    private String updatedAt;
}
