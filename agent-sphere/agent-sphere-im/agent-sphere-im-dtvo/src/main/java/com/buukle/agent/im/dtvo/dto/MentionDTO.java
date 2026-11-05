package com.buukle.agent.im.dtvo.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class MentionDTO implements Serializable {
    /** USER / AGENT */
    private String type;
    private Long id;
    private String name;
}
