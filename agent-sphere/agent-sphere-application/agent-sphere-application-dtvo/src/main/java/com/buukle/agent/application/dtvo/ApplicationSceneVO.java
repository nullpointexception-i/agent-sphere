package com.buukle.agent.application.dtvo;

import lombok.Data;

import java.util.List;

@Data
public class ApplicationSceneVO {
    private Long id;
    private Long applicationId;
    private String sceneKey;
    private String name;
    private String nameKey;
    private String description;
    private String descriptionKey;
    private Long tenantId;
    private String sceneType;
    private String config;
    /** Localized welcome text resolved from {@code config.welcomeKey}. */
    private String welcome;
    /** Localized suggested questions resolved from {@code config.suggestionKeys}. */
    private List<String> suggestions;
    /** Entity scopes the agent may read for this scene. */
    private List<String> contextScopes;
    private Integer sortOrder;
    private String status;
}
