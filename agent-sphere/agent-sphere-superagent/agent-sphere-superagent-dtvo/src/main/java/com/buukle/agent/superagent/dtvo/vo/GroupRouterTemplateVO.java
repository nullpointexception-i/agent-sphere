package com.buukle.agent.superagent.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/** Shared Group Router prompt template. */
@Data
public class GroupRouterTemplateVO implements Serializable {
    /** GROUP_ROUTER instance id; null when the instance has not been created yet. */
    private Long instanceId;
    /** Effective template (custom value, or the built-in default). */
    private String template;
    /** Built-in fallback shipped with the application. */
    private String defaultTemplate;
    /** Whether the instance has a custom system prompt. */
    private Boolean custom;
}
