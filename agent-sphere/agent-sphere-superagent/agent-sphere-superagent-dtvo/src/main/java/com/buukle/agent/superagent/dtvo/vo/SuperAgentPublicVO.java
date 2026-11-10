package com.buukle.agent.superagent.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/** Display-only projection of a public avatar; never exposes prompts or instance ids. */
@Data
public class SuperAgentPublicVO implements Serializable {
    private Long id;
    private Long ownerUserId;
    private String agentType;
    private String name;
    private String description;
    private String positionCode;
    private String positionNameCn;
    private String positionNameEn;
    private String status;
}
