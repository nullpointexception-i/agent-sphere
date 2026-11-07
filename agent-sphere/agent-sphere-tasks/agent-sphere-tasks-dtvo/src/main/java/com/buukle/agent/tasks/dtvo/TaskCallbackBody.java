package com.buukle.agent.tasks.dtvo;

import lombok.Data;

import java.io.Serializable;

/**
 * Task terminal-state callback request body.
 */
@Data
public class TaskCallbackBody implements Serializable {
    private Long asTaskId;
    private String status;
    private String resultJson;
    private String remark;
}
