package com.buukle.agent.instance.dtvo.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class FrontendTrackDTO implements Serializable {
    private String eventType;
    private String page;
    private Long durationMs;
    private String elementPath;
    private String elementTag;
    private String elementText;
    private String selectedText;
    private Integer positionX;
    private Integer positionY;
}
