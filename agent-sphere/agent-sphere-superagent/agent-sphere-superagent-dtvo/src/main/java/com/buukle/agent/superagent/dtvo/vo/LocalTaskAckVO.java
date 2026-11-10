package com.buukle.agent.superagent.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/** Task accepted by the desktop runtime. */
@Data
public class LocalTaskAckVO implements Serializable {
    private LocalTaskPayloadVO task;
    private String leaseToken;
}
