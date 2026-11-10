package com.buukle.agent.superagent.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** Local runtime summary for the console and the desktop. */
@Data
public class LocalRuntimeVO implements Serializable {
    private Long id;
    private String name;
    private String runtimeKind;
    private String status;
    private boolean online;
    private String appVersion;
    private String runtimeVersion;
    private String os;
    private String arch;
    private String tokenPrefix;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;
}
