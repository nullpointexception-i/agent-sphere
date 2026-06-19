package com.buukle.agent.capability.cli.dtvo.vo;

import lombok.Data;
import java.io.Serializable;

@Data
public class CliVO implements Serializable {
    private Long id;
    private String name;
    private String commandTemplate;
    private String paramSchema;
    private String workingDir;
    private String status;
    private String createdAt;
    private String createdBy;
    private String updatedBy;
    private String updatedAt;
}
