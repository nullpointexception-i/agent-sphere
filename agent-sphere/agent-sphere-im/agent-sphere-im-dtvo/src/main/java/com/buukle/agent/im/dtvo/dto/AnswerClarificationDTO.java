package com.buukle.agent.im.dtvo.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** Answer submitted from a Router clarification card. */
@Data
public class AnswerClarificationDTO implements Serializable {

    public static final String MODE_SINGLE = "SINGLE_OPTION";
    public static final String MODE_MULTI = "MULTI_OPTION";
    public static final String MODE_FREE_TEXT = "FREE_TEXT";
    /** Same cap as the router reply text. */
    public static final int MAX_TEXT_CHARS = 8000;

    /** SINGLE_OPTION / MULTI_OPTION / FREE_TEXT */
    private String mode;
    private List<String> selectedOptions;
    private String text;
}
