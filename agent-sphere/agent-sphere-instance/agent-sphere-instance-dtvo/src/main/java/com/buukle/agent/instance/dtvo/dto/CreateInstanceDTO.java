package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateInstanceDTO implements Serializable {
    @NotBlank(message = "name can't be blank")
    @Size(min = 1, max = 64)
    private String name;
    @Size(max = 255)
    private String description;
    @Size(max = 5000)
    private String systemPrompt;
    private Long modelRouteId;
    @Size(max = 5000)
    private String customInstructions;
    @Size(max = 3000000)
    private String image;
    @Size(max = 64)
    private String businessType;

    /**
     * Whether the console site may start/continue sessions.
     *
     * <p>On create, leaving it empty uses the database default (allowed); on update, leaving it
     * empty means **do not change** (MyBatis-Plus ignores null fields).
     */
    private Boolean allowConsoleSession;

    /** Whether the embedded widget may start/continue sessions. Empty semantics are the same as {@link #allowConsoleSession} (database default is denied). */
    private Boolean allowWidgetSession;
    /** Maximum loop count for a single run; NULL = not configured, 0 = clear the override, >0 overrides the system default. */
    @Min(value = 0, message = "maxLoopCount must be >= 0 when configured")
    private Integer maxLoopCount;
    /** STRICT_FINISH by default; IMPLICIT_TEXT is for compatible conversational instances. */
    private String completionProtocol;
    /** CHAT / GROUP_ROUTER / ROUTER / WORKER / WATCHDOG; empty defaults to CHAT. */
    private String instanceRole;
    /** USER (default) or TENANT; set by the controller/initializer, never trusted from the client. */
    private String ownerScope;
    /** Sampling/request parameter JSON configuration (same shape as LlmSamplingConfigDTO); NULL/empty = not configured, an empty string on update = clear. */
    @Size(max = 8000, message = "config must be <= 8000 chars")
    private String config;
}
