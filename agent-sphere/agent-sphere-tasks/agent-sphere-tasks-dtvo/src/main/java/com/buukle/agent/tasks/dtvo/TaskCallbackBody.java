package com.buukle.agent.tasks.dtvo;

import lombok.Data;

import java.io.Serializable;

/**
 * 任务终态回调请求体。
 */
@Data
public class TaskCallbackBody implements Serializable {
    private Long asTaskId;
    private String status;
    private String resultJson;
    private String remark;
}
