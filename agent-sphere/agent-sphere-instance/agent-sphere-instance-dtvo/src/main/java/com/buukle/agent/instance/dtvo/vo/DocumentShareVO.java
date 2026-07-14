package com.buukle.agent.instance.dtvo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

@Data
@AllArgsConstructor
public class DocumentShareVO implements Serializable {
    private String shareToken;
}
