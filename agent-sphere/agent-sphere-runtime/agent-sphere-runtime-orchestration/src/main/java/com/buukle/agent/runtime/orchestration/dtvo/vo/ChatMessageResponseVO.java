package com.buukle.agent.runtime.orchestration.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class ChatMessageResponseVO implements Serializable {
    private Long runId;
    private String status;
}
