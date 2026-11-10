package com.buukle.agent.im.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** Result of answering a Router clarification card. */
@Data
public class ClarificationAnswerVO implements Serializable {
    private String inputId;
    private String status;
    private String mode;
    private String answer;
    private List<String> selectedOptions;
    private String respondedBy;
    private String respondedAt;
}
