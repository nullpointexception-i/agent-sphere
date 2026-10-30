package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class ChatShareVO implements Serializable {
    private String shareToken;
    private String shareUrl;
}
