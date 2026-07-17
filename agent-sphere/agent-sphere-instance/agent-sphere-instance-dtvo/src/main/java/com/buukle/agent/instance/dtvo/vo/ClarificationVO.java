package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClarificationVO implements Serializable {
    private String clarificationId;
    private Long runId;
    private Long sessionId;
    private Long messageId;
    private String title;
    private String type;
    private String options;
    private String userResponse;
    private String status;
}
