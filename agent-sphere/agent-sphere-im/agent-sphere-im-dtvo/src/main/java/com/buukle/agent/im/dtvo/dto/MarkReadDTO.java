package com.buukle.agent.im.dtvo.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class MarkReadDTO implements Serializable {
    private Long lastReadSeq;
    private String deviceId;
}
