package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class InstanceVO implements Serializable {
    private Long id;
    private String name;
    private String description;
    private String systemPrompt;
    private Long modelRouteId;
    private String customInstructions;
    private String image;
    private String status;
    private String businessType;

    /** Whether the console site may start/continue sessions (V89). */
    private Boolean allowConsoleSession;

    /** Whether the embedded widget may start/continue sessions (V89). */
    private Boolean allowWidgetSession;
    /** Maximum loop count for a single run (NULL = not configured, use the system default). */
    private Integer maxLoopCount;
    private String completionProtocol;
    /** CHAT / GROUP_ROUTER / ROUTER / WORKER / WATCHDOG。 */
    private String instanceRole;
    /** System-managed instance. */
    private Boolean systemManaged;
    /** Sampling/request parameter JSON configuration (NULL = not configured). */
    private String config;
    private String createdAt;
    private String createdBy;
    private String updatedBy;
    private String updatedAt;
}
