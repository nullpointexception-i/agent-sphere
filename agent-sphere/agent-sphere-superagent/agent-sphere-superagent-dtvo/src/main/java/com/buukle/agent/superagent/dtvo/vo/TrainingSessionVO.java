package com.buukle.agent.superagent.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class TrainingSessionVO implements Serializable {
    private Long sessionId;
    private Long superAgentId;
    private Long workerInstanceId;
    private String status;
}
