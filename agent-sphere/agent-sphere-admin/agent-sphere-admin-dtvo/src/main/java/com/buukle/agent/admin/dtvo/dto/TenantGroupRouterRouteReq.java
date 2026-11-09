package com.buukle.agent.admin.dtvo.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * Group-router model route of a tenant.
 *
 * <p>A {@code null} route clears the configuration; group creation then fails
 * with an explicit "route is not configured" error instead of silently using a
 * platform default.
 */
@Data
public class TenantGroupRouterRouteReq implements Serializable {
    private Long modelRouteId;
}
