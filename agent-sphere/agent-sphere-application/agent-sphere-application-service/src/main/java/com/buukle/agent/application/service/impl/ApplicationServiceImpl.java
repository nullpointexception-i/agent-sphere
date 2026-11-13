package com.buukle.agent.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.application.domain.BizApplication;
import com.buukle.agent.application.domain.BizApplicationMember;
import com.buukle.agent.application.domain.BizApplicationScene;
import com.buukle.agent.application.domain.SysI18nText;
import com.buukle.agent.application.dtvo.ApplicationMemberVO;
import com.buukle.agent.application.dtvo.ApplicationSceneVO;
import com.buukle.agent.application.dtvo.ApplicationVO;
import com.buukle.agent.application.dtvo.AddApplicationMemberDTO;
import com.buukle.agent.application.dtvo.CreateApplicationDTO;
import com.buukle.agent.application.dtvo.CreateApplicationSceneDTO;
import com.buukle.agent.application.repository.BizApplicationMapper;
import com.buukle.agent.application.repository.BizApplicationMemberMapper;
import com.buukle.agent.application.repository.BizApplicationSceneMapper;
import com.buukle.agent.application.repository.SysI18nTextMapper;
import com.buukle.agent.application.service.ApplicationService;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.LocaleContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ApplicationServiceImpl implements ApplicationService {

    private static final String APP_TYPE_SYSTEM = "SYSTEM";
    private static final String APP_TYPE_CUSTOM = "CUSTOM";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String VISIBILITY_TENANT = "TENANT";
    private static final String DEFAULT_LOCALE = "zh-CN";
    private static final String DEFAULT_MEMBER_ROLE = "MEMBER";
    private static final String CFG_WELCOME_KEY = "welcomeKey";
    private static final String CFG_SUGGESTION_KEYS = "suggestionKeys";
    private static final String CFG_CONTEXT_SCOPES = "contextScopes";

    private final BizApplicationMapper applicationMapper;
    private final BizApplicationSceneMapper sceneMapper;
    private final BizApplicationMemberMapper memberMapper;
    private final SysI18nTextMapper i18nTextMapper;
    private final UserSpi userSpi;

    @Override
    public PageResult<ApplicationVO> page(String keyword, int page, int pageSize) {
        Long tenantId = currentTenant();
        Long userId = currentUser();
        LambdaQueryWrapper<BizApplication> wrapper = visibleWrapper(tenantId, userId);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(BizApplication::getName, keyword)
                    .or().like(BizApplication::getAppKey, keyword)
                    .or().like(BizApplication::getDescription, keyword));
        }
        wrapper.orderByAsc(BizApplication::getSortOrder).orderByAsc(BizApplication::getId);
        Page<BizApplication> result = applicationMapper.selectPage(Page.of(page, pageSize), wrapper);
        Map<String, String> texts = resolveTexts(result.getRecords());
        List<ApplicationVO> records = result.getRecords().stream()
                .map(app -> toVO(app, texts)).toList();
        return PageResult.of(records, result.getTotal(), page, pageSize);
    }

    @Override
    public ApplicationVO detail(Long id) {
        BizApplication app = requireVisible(id);
        return toVO(app, resolveTexts(List.of(app)));
    }

    @Override
    public ApplicationVO detailSystem(Long id) {
        BizApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "application not found");
        }
        return toVO(app, resolveTexts(List.of(app)));
    }

    @Override
    @Transactional
    public Long create(CreateApplicationDTO dto) {
        Long tenantId = currentTenant();
        Long userId = currentUser();
        String appKey = dto.getAppKey().trim();
        if (appKey.startsWith("system-")) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "system- prefix is reserved");
        }
        Long existing = applicationMapper.selectCount(new LambdaQueryWrapper<BizApplication>()
                .eq(BizApplication::getTenantId, tenantId)
                .eq(BizApplication::getAppKey, appKey));
        if (existing > 0) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "application key already exists");
        }
        BizApplication app = new BizApplication();
        app.setAppKey(appKey);
        app.setName(dto.getName().trim());
        app.setDescription(dto.getDescription());
        app.setIcon(dto.getIcon());
        app.setDefaultLocale(DEFAULT_LOCALE);
        app.setAppType(APP_TYPE_CUSTOM);
        app.setEntryMode(StringUtils.hasText(dto.getEntryMode()) ? dto.getEntryMode() : "WORKBENCH");
        app.setEntryKey(dto.getEntryKey());
        app.setVisibility(StringUtils.hasText(dto.getVisibility()) ? dto.getVisibility() : VISIBILITY_TENANT);
        app.setOwnerUserId(userId);
        app.setSortOrder(100);
        app.setStatus(STATUS_ACTIVE);
        app.setTenantId(tenantId);
        applicationMapper.insert(app);
        return app.getId();
    }

    @Override
    @Transactional
    public void update(Long id, CreateApplicationDTO dto) {
        BizApplication app = requireOwned(id);
        if (APP_TYPE_SYSTEM.equals(app.getAppType())) {
            throw new BizException(CommonErrorCode.FORBIDDEN, "system application is immutable");
        }
        app.setName(dto.getName().trim());
        app.setDescription(dto.getDescription());
        app.setIcon(dto.getIcon());
        app.setEntryMode(StringUtils.hasText(dto.getEntryMode()) ? dto.getEntryMode() : app.getEntryMode());
        app.setEntryKey(dto.getEntryKey());
        app.setVisibility(StringUtils.hasText(dto.getVisibility()) ? dto.getVisibility() : app.getVisibility());
        applicationMapper.updateById(app);
    }

    @Override
    @Transactional
    public void disable(Long id) {
        BizApplication app = requireOwned(id);
        if (APP_TYPE_SYSTEM.equals(app.getAppType())) {
            throw new BizException(CommonErrorCode.FORBIDDEN, "system application cannot be disabled");
        }
        app.setStatus(STATUS_DISABLED);
        applicationMapper.updateById(app);
    }

    @Override
    public List<ApplicationSceneVO> scenes(Long applicationId) {
        requireVisible(applicationId);
        List<BizApplicationScene> rows = sceneMapper.selectList(
                new LambdaQueryWrapper<BizApplicationScene>()
                        .eq(BizApplicationScene::getApplicationId, applicationId)
                        .eq(BizApplicationScene::getStatus, STATUS_ACTIVE)
                        .orderByAsc(BizApplicationScene::getSortOrder));
        List<String> keys = rows.stream()
                .flatMap(scene -> sceneTextKeys(scene).stream())
                .distinct()
                .toList();
        Map<String, String> texts = resolveKeys(keys);
        return rows.stream().map(scene -> toSceneVO(scene, texts)).toList();
    }

    @Override
    public ApplicationSceneVO scene(Long sceneId) {
        BizApplicationScene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "scene not found");
        }
        requireVisible(scene.getApplicationId());
        return toSceneVO(scene, resolveKeys(sceneTextKeys(scene)));
    }

    @Override
    public ApplicationSceneVO sceneSystem(Long sceneId) {
        BizApplicationScene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "scene not found");
        }
        return toSceneVO(scene, resolveKeys(sceneTextKeys(scene)));
    }

    @Override
    @Transactional
    public Long createScene(Long applicationId, CreateApplicationSceneDTO dto) {        BizApplication app = requireOwned(applicationId);
        if (APP_TYPE_SYSTEM.equals(app.getAppType())) {
            throw new BizException(CommonErrorCode.FORBIDDEN, "system application is immutable");
        }
        Long existing = sceneMapper.selectCount(new LambdaQueryWrapper<BizApplicationScene>()
                .eq(BizApplicationScene::getApplicationId, applicationId)
                .eq(BizApplicationScene::getSceneKey, dto.getSceneKey().trim()));
        if (existing > 0) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "scene key already exists");
        }
        BizApplicationScene scene = new BizApplicationScene();
        scene.setApplicationId(applicationId);
        scene.setSceneKey(dto.getSceneKey().trim());
        scene.setName(dto.getName());
        scene.setDescription(null);
        scene.setDefaultLocale(app.getDefaultLocale());
        scene.setSceneType(dto.getSceneType());
        scene.setSortOrder(dto.getSortOrder() == null ? 100 : dto.getSortOrder());
        scene.setStatus(STATUS_ACTIVE);
        scene.setTenantId(currentTenant());
        sceneMapper.insert(scene);
        return scene.getId();
    }

    @Override
    @Transactional
    public void updateScene(Long sceneId, CreateApplicationSceneDTO dto) {
        BizApplicationScene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "scene not found");
        }
        BizApplication app = requireOwned(scene.getApplicationId());
        if (APP_TYPE_SYSTEM.equals(app.getAppType())) {
            throw new BizException(CommonErrorCode.FORBIDDEN, "system application is immutable");
        }
        scene.setName(dto.getName());
        scene.setSceneType(dto.getSceneType());
        scene.setSortOrder(dto.getSortOrder() == null ? scene.getSortOrder() : dto.getSortOrder());
        sceneMapper.updateById(scene);
    }

    @Override
    public void assertSceneVisible(Long sceneId) {
        BizApplicationScene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "scene not found");
        }
        requireVisible(scene.getApplicationId());
    }

    @Override
    public List<ApplicationMemberVO> members(Long applicationId) {
        requireVisible(applicationId);
        List<BizApplicationMember> members = memberMapper.selectList(
                new LambdaQueryWrapper<BizApplicationMember>()
                        .eq(BizApplicationMember::getApplicationId, applicationId)
                        .orderByAsc(BizApplicationMember::getId));
        Map<Long, UserIdentityVO> identities = identities(
                members.stream().map(BizApplicationMember::getUserId).toList());
        return members.stream().map(member -> toMemberVO(member, identities)).toList();
    }

    @Override
    @Transactional
    public void addMember(Long applicationId, AddApplicationMemberDTO dto) {
        requireOwned(applicationId);
        Long existing = memberMapper.selectCount(new LambdaQueryWrapper<BizApplicationMember>()
                .eq(BizApplicationMember::getApplicationId, applicationId)
                .eq(BizApplicationMember::getUserId, dto.getUserId()));
        if (existing > 0) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "member already exists");
        }
        BizApplicationMember member = new BizApplicationMember();
        member.setApplicationId(applicationId);
        member.setUserId(dto.getUserId());
        member.setMemberRole(StringUtils.hasText(dto.getMemberRole())
                ? dto.getMemberRole() : DEFAULT_MEMBER_ROLE);
        member.setTenantId(currentTenant());
        memberMapper.insert(member);
    }

    @Override
    @Transactional
    public void removeMember(Long applicationId, Long userId) {
        requireOwned(applicationId);
        BizApplicationMember member = memberMapper.selectOne(new LambdaQueryWrapper<BizApplicationMember>()
                .eq(BizApplicationMember::getApplicationId, applicationId)
                .eq(BizApplicationMember::getUserId, userId));
        if (member != null) {
            memberMapper.deleteById(member.getId());
        }
    }

    private BizApplication requireVisible(Long id) {
        BizApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "application not found");
        }
        Long tenantId = currentTenant();
        Long userId = currentUser();
        boolean sameTenant = tenantId.equals(app.getTenantId());
        boolean platform = Long.valueOf(0L).equals(app.getTenantId());
        boolean member = memberMapper.selectCount(new LambdaQueryWrapper<BizApplicationMember>()
                .eq(BizApplicationMember::getApplicationId, app.getId())
                .eq(BizApplicationMember::getUserId, userId)) > 0;
        boolean visible = VISIBILITY_TENANT.equals(app.getVisibility())
                || (userId != null && userId.equals(app.getOwnerUserId()))
                || member;
        if (!(platform || sameTenant) || !visible) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "application not found");
        }
        return app;
    }

    private BizApplication requireOwned(Long id) {
        BizApplication app = requireVisible(id);
        if (!currentUser().equals(app.getOwnerUserId())) {
            throw new BizException(CommonErrorCode.FORBIDDEN, "only the application owner can modify it");
        }
        return app;
    }

    private LambdaQueryWrapper<BizApplication> visibleWrapper(Long tenantId, Long userId) {
        return new LambdaQueryWrapper<BizApplication>()
                .eq(BizApplication::getStatus, STATUS_ACTIVE)
                .and(w -> w.eq(BizApplication::getTenantId, 0L)
                        .or().eq(BizApplication::getTenantId, tenantId))
                .and(w -> w.eq(BizApplication::getVisibility, VISIBILITY_TENANT)
                        .or().eq(BizApplication::getOwnerUserId, userId)
                        .or().inSql(BizApplication::getId,
                                "select application_id from biz_application_member where delete_flag = false and user_id = "
                                        + userId + " and tenant_id = " + tenantId));
    }

    private ApplicationVO toVO(BizApplication app, Map<String, String> texts) {
        ApplicationVO vo = new ApplicationVO();
        vo.setId(app.getId());
        vo.setAppKey(app.getAppKey());
        vo.setName(app.getName() != null ? app.getName() : texts.get(app.getNameKey()));
        vo.setNameKey(app.getNameKey());
        vo.setDescription(app.getDescription() != null
                ? app.getDescription() : texts.get(app.getDescriptionKey()));
        vo.setDescriptionKey(app.getDescriptionKey());
        vo.setDefaultLocale(app.getDefaultLocale());
        vo.setIcon(app.getIcon());
        vo.setAppType(app.getAppType());
        vo.setEntryMode(app.getEntryMode());
        vo.setEntryKey(app.getEntryKey());
        vo.setVisibility(app.getVisibility());
        vo.setOwnerUserId(app.getOwnerUserId());
        vo.setSortOrder(app.getSortOrder());
        vo.setStatus(app.getStatus());
        vo.setCreatedAt(app.getCreatedAt());
        return vo;
    }

    private ApplicationSceneVO toSceneVO(BizApplicationScene scene, Map<String, String> texts) {
        ApplicationSceneVO vo = new ApplicationSceneVO();
        vo.setId(scene.getId());
        vo.setApplicationId(scene.getApplicationId());
        vo.setSceneKey(scene.getSceneKey());
        vo.setName(scene.getName() != null ? scene.getName() : texts.get(scene.getNameKey()));
        vo.setNameKey(scene.getNameKey());
        vo.setDescription(scene.getDescription() != null
                ? scene.getDescription() : texts.get(scene.getDescriptionKey()));
        vo.setDescriptionKey(scene.getDescriptionKey());
        vo.setTenantId(scene.getTenantId());
        vo.setSceneType(scene.getSceneType());
        vo.setConfig(scene.getConfig());
        JsonNode config = parseConfig(scene.getConfig());
        if (config != null) {
            String welcomeKey = textValue(config, CFG_WELCOME_KEY);
            vo.setWelcome(welcomeKey == null ? null : texts.get(welcomeKey));
            vo.setSuggestions(resolveSuggestionKeys(config, texts));
            vo.setContextScopes(stringList(config, CFG_CONTEXT_SCOPES));
        }
        vo.setSortOrder(scene.getSortOrder());
        vo.setStatus(scene.getStatus());
        return vo;
    }

    private List<String> sceneTextKeys(BizApplicationScene scene) {
        List<String> keys = new ArrayList<>();
        if (StringUtils.hasText(scene.getNameKey())) {
            keys.add(scene.getNameKey());
        }
        if (StringUtils.hasText(scene.getDescriptionKey())) {
            keys.add(scene.getDescriptionKey());
        }
        JsonNode config = parseConfig(scene.getConfig());
        if (config != null) {
            String welcomeKey = textValue(config, CFG_WELCOME_KEY);
            if (welcomeKey != null && !welcomeKey.isBlank()) {
                keys.add(welcomeKey);
            }
            keys.addAll(stringList(config, CFG_SUGGESTION_KEYS));
        }
        return keys;
    }

    private JsonNode parseConfig(String config) {
        if (!StringUtils.hasText(config)) {
            return null;
        }
        try {
            return JsonUtils.getMapper().readTree(config);
        } catch (Exception e) {
            return null;
        }
    }

    private static String textValue(JsonNode config, String field) {
        return config.hasNonNull(field) ? config.get(field).asText() : null;
    }

    private static List<String> stringList(JsonNode config, String field) {
        JsonNode node = config.get(field);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (item != null && !item.isNull() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        });
        return values;
    }

    private static List<String> resolveSuggestionKeys(JsonNode config, Map<String, String> texts) {
        List<String> suggestions = new ArrayList<>();
        for (String key : stringList(config, CFG_SUGGESTION_KEYS)) {
            String text = texts.get(key);
            if (text != null && !text.isBlank()) {
                suggestions.add(text);
            }
        }
        return suggestions;
    }

    private Map<String, String> resolveTexts(List<BizApplication> apps) {
        List<String> keys = apps.stream()
                .flatMap(app -> java.util.stream.Stream.of(app.getNameKey(), app.getDescriptionKey()))
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return resolveKeys(keys);
    }

    private Map<String, String> resolveKeys(Collection<String> candidateKeys) {
        List<String> keys = candidateKeys.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (keys.isEmpty()) {
            return Map.of();
        }
        String locale = LocaleContext.get();
        Long tenantId = currentTenant();
        List<SysI18nText> rows = i18nTextMapper.selectList(new LambdaQueryWrapper<SysI18nText>()
                .in(SysI18nText::getMessageKey, keys)
                .in(SysI18nText::getLocale, locale, DEFAULT_LOCALE)
                .in(SysI18nText::getTenantId, 0L, tenantId)
                .eq(SysI18nText::getStatus, STATUS_ACTIVE)
                .orderByAsc(SysI18nText::getTenantId));
        Map<String, String> fallback = new HashMap<>();
        Map<String, String> exact = new HashMap<>();
        for (SysI18nText row : rows) {
            Map<String, String> target = locale.equals(row.getLocale()) ? exact : fallback;
            target.put(row.getMessageKey(), row.getText());
        }
        fallback.putAll(exact);
        return fallback;
    }

    private ApplicationMemberVO toMemberVO(BizApplicationMember member,
                                            Map<Long, UserIdentityVO> identities) {
        ApplicationMemberVO vo = new ApplicationMemberVO();
        vo.setId(member.getId());
        vo.setApplicationId(member.getApplicationId());
        vo.setUserId(member.getUserId());
        vo.setDisplayName(displayName(member.getUserId(), identities));
        vo.setMemberRole(member.getMemberRole());
        vo.setCreatedAt(member.getCreatedAt());
        return vo;
    }

    private Map<Long, UserIdentityVO> identities(Collection<Long> userIds) {
        List<Long> ids = userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return ids.isEmpty() ? Map.of() : userSpi.identityMap(ids);
    }

    private String displayName(Long userId, Map<Long, UserIdentityVO> identities) {
        if (userId == null) {
            return null;
        }
        UserIdentityVO identity = identities.get(userId);
        if (identity == null) {
            return null;
        }
        if (StringUtils.hasText(identity.getDisplayName())) {
            return identity.getDisplayName();
        }
        if (StringUtils.hasText(identity.getEnglishName())) {
            return identity.getEnglishName();
        }
        return identity.getUsername();
    }

    private Long currentTenant() {
        Long tenantId = TenantContext.get();
        return tenantId == null ? 0L : tenantId;
    }

    private Long currentUser() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
