package com.buukle.agent.superagent.dtvo.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Bridged Router input (clarification / tool approval) projected to the IM DM. */
@Data
@NoArgsConstructor
public class SessionInputVO implements Serializable {
    private Long id;
    private Long superAgentId;
    private Long threadId;
    private Long sessionId;
    private Long runId;
    private String inputType;
    private String inputId;
    private String title;
    private String description;
    private String options;
    private Boolean multiple;
    private String toolCallsJson;
    private String status;
}
