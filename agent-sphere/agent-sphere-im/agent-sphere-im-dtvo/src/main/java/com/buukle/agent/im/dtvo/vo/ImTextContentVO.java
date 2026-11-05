package com.buukle.agent.im.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/** Typed TEXT message content stored in {@code im_message.content}. */
@Data
public class ImTextContentVO implements Serializable {
    private String text;

    public ImTextContentVO() {
    }

    public ImTextContentVO(String text) {
        this.text = text;
    }
}
