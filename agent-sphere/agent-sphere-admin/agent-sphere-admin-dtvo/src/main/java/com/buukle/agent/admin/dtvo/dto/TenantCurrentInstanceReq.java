package com.buukle.agent.admin.dtvo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/** Sets the tenant's current GROUP_ROUTER instance (group chat routing). */
@Data
public class TenantCurrentInstanceReq implements Serializable {
    @NotNull
    private Long instanceId;
}
