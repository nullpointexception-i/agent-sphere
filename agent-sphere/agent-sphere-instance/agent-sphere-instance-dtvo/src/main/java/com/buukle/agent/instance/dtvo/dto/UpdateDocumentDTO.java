package com.buukle.agent.instance.dtvo.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class UpdateDocumentDTO implements Serializable {
    private String title;
    private String content;
}
