package com.buukle.agent.channel.spi;

import lombok.Data;

import java.io.Serializable;

/** Typed card body embedded in an outbound CARD message. */
@Data
public class TaskCardPayload implements Serializable {
    private String cardType;
    private Long workItemId;
    private String title;
    private String status;
    private String summary;
    private Long superAgentId;

    /** Router input cards (ROUTER_CLARIFICATION / ROUTER_APPROVAL) only. */
    private String inputType;
    /** clarificationId / approvalId. */
    private String inputId;
    private Long sessionId;
    private Long runId;
    private String description;
    /** Clarification: whether multiple options may be selected. */
    private Boolean multiple;
    /** Clarification: options JSON passthrough. */
    private String options;
    /** Approval: pending tool calls JSON passthrough. */
    private String toolCallsJson;
    /** Resolved cards: the decisions (approval) or answer (clarification) that were submitted. */
    private String decisionsJson;
    /** Resolved cards: username that answered/approved the input. */
    private String respondedBy;
    /** Resolved cards: answer/approval time. */
    private String respondedAt;
    /** True when the card body is hidden from the current viewer (strict clarification visibility). */
    private Boolean hidden;
}
