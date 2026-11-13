package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClarificationVO implements Serializable {
    private Long id;
    private String clarificationId;
    private Long runId;
    private Long sessionId;
    private Long messageId;
    private String title;
    private String type;
    private Boolean multiple;
    private String options;
    private String description;
    private String toolCallsJson;
    private String userResponse;
    private String status;
    private Long targetUserId;
    private String expiresAt;
    private Long answeredBy;
    private String createdAt;
}
