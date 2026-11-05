package com.buukle.agent.superagent.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/** Serialized WORKER_REPORT event payload. */
@Data
public class WorkerReportPayloadVO implements Serializable {
    private String text;
    private Long workItemId;
    private String status;
    private String summary;
    private String errorMessage;
}
