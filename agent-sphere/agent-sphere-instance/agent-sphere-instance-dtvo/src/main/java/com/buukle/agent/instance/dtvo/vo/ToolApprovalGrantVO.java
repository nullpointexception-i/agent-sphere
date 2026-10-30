package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class ToolApprovalGrantVO implements Serializable {
    private String toolRef;
    private String displayName;
    private String securityLevel;
    private String scope;
}
