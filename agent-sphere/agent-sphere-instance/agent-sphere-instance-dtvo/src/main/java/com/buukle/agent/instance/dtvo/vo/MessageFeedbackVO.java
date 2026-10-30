package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class MessageFeedbackVO implements Serializable {
    private Long interactionId;
    private String value;
}
