package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** Persisted user answer for a clarification request. */
@Data
public class ClarificationAnswerPayloadVO implements Serializable {
    private List<String> selected;
    private String text;
}
