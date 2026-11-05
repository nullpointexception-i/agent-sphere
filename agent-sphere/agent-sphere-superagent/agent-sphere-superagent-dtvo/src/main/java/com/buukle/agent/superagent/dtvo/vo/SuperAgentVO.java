package com.buukle.agent.superagent.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class SuperAgentVO implements Serializable {
    private Long id;
    private Long ownerUserId;
    private String agentKey;
    private String name;
    private String description;
    private Long routerInstanceId;
    private Long routerSessionId;
    private Long workerInstanceId;
    private String positionCode;
    private String positionNameCn;
    private String positionNameEn;
    private String config;
    private String status;
    private String createdAt;
}
