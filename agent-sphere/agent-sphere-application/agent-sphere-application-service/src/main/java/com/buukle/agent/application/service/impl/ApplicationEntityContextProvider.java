package com.buukle.agent.application.service.impl;

import com.buukle.agent.application.dtvo.ApplicationSceneVO;
import com.buukle.agent.application.dtvo.ApplicationVO;
import com.buukle.agent.application.service.ApplicationService;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.superagent.spi.EntityContext;
import com.buukle.agent.superagent.spi.EntityContextSpi;
import com.buukle.agent.superagent.spi.EntityTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ApplicationEntityContextProvider implements EntityContextSpi {

    private static final String ATTR_APP_KEY = "app_key";
    private static final String ATTR_APP_NAME = "app_name";
    private static final String ATTR_APP_TYPE = "app_type";
    private static final String ATTR_ENTRY_MODE = "entry_mode";
    private static final String ATTR_DEFAULT_LOCALE = "default_locale";
    private static final String ATTR_SCENE_KEY = "scene_key";
    private static final String ATTR_SCENE_TYPE = "scene_type";
    private static final String ATTR_PAGE_SCHEMA = "page_schema";
    private static final int MAX_PAGE_SCHEMA_CHARS = 2000;

    private final ApplicationService applicationService;

    @Override
    public boolean supports(String entityType) {
        return EntityTypes.APPLICATION.equals(entityType)
                || EntityTypes.APPLICATION_SCENE.equals(entityType);
    }

    @Override
    public EntityContext describe(String entityType, Long entityId, Long userId) {
        if (entityId == null) {
            return null;
        }
        try {
            if (EntityTypes.APPLICATION.equals(entityType)) {
                return describeApplication(applicationService.detail(entityId));
            }
            if (EntityTypes.APPLICATION_SCENE.equals(entityType)) {
                ApplicationSceneVO scene = applicationService.scene(entityId);
                ApplicationVO app = applicationService.detail(scene.getApplicationId());
                return describeScene(scene, app);
            }
            return null;
        } catch (BizException e) {
            return null;
        }
    }

    private EntityContext describeApplication(ApplicationVO app) {
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, ATTR_APP_KEY, app.getAppKey());
        put(attributes, ATTR_APP_TYPE, app.getAppType());
        put(attributes, ATTR_ENTRY_MODE, app.getEntryMode());
        put(attributes, ATTR_DEFAULT_LOCALE, app.getDefaultLocale());
        return new EntityContext(EntityTypes.APPLICATION, app.getId(),
                app.getName() == null ? app.getAppKey() : app.getName(),
                app.getStatus(), app.getDescription(), attributes);
    }

    private EntityContext describeScene(ApplicationSceneVO scene, ApplicationVO app) {
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, ATTR_APP_KEY, app.getAppKey());
        put(attributes, ATTR_APP_NAME, app.getName());
        put(attributes, ATTR_SCENE_KEY, scene.getSceneKey());
        put(attributes, ATTR_SCENE_TYPE, scene.getSceneType());
        put(attributes, ATTR_PAGE_SCHEMA, truncate(scene.getConfig()));
        String title = scene.getName() == null ? scene.getSceneKey() : scene.getName();
        return new EntityContext(EntityTypes.APPLICATION_SCENE, scene.getId(),
                title, scene.getStatus(), scene.getDescription(), attributes);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_PAGE_SCHEMA_CHARS
                ? value : value.substring(0, MAX_PAGE_SCHEMA_CHARS);
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
