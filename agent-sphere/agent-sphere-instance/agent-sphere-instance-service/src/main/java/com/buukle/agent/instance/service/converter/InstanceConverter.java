package com.buukle.agent.instance.service.converter;


import com.buukle.agent.common.constant.CompletionProtocol;
import com.buukle.agent.common.constant.GroupRouterPrompt;
import com.buukle.agent.common.constant.OwnerScope;
import com.buukle.agent.instance.dtvo.enums.InstanceRole;
import com.buukle.agent.instance.domain.AgentInstance;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.enums.InstanceEnum;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
public class InstanceConverter {
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public InstanceVO toVO(AgentInstance instance) {
        if (instance == null) return null;
        InstanceVO vo = new InstanceVO();
        vo.setId(instance.getId());
        vo.setName(instance.getName());
        vo.setDescription(instance.getDescription());
        vo.setSystemPrompt(instance.getSystemPrompt());
        if (InstanceRole.GROUP_ROUTER.value().equals(instance.getInstanceRole())
                && (instance.getSystemPrompt() == null || instance.getSystemPrompt().isBlank())) {
            // Echo the effective prompt so the editor/detail page and the runtime share one value.
            vo.setSystemPrompt(GroupRouterPrompt.DEFAULT);
            vo.setSystemPromptDefaulted(true);
        } else {
            vo.setSystemPromptDefaulted(false);
        }
        if (InstanceRole.GROUP_ROUTER.value().equals(instance.getInstanceRole())) {
            vo.setDefaultSystemPrompt(GroupRouterPrompt.DEFAULT);
        }
        vo.setModelRouteId(instance.getModelRouteId());
        vo.setCustomInstructions(instance.getCustomInstructions());
        vo.setImage(instance.getImage());
        vo.setStatus(instance.getStatus());
        vo.setBusinessType(instance.getBusinessType());
        vo.setTenantId(instance.getTenantId());
        vo.setAllowConsoleSession(instance.getAllowConsoleSession());
        vo.setAllowWidgetSession(instance.getAllowWidgetSession());
        vo.setMaxLoopCount(instance.getMaxLoopCount());
        vo.setCompletionProtocol(instance.getCompletionProtocol());
        vo.setInstanceRole(instance.getInstanceRole());
        vo.setOwnerScope(instance.getOwnerScope());
        vo.setConfig(instance.getConfig());
        vo.setCreatedAt(instance.getCreatedAt() != null ? instance.getCreatedAt().format(DTF) : null);
        vo.setCreatedBy(instance.getCreatedBy());
        vo.setUpdatedBy(instance.getUpdatedBy());
        vo.setUpdatedAt(instance.getUpdatedAt() != null ? instance.getUpdatedAt().format(DTF) : null);
        return vo;
    }

    public AgentInstance toDO(CreateInstanceDTO dto) {
        AgentInstance instance = new AgentInstance();
        instance.setName(dto.getName());
        instance.setDescription(dto.getDescription());
        instance.setSystemPrompt(dto.getSystemPrompt());
        instance.setModelRouteId(dto.getModelRouteId());
        instance.setCustomInstructions(dto.getCustomInstructions());
        instance.setImage(dto.getImage());
        instance.setBusinessType(dto.getBusinessType());
        // The two surface switches deliberately have **no default value**: when left null, create
        // uses the database defaults (console allowed / widget denied), and update makes
        // MyBatis-Plus ignore them, meaning "no change". Filling in defaults here would silently
        // reset the switches back to the defaults on any update that does not carry these two
        // fields, wiping out the administrator's choice.
        instance.setAllowConsoleSession(dto.getAllowConsoleSession());
        instance.setAllowWidgetSession(dto.getAllowWidgetSession());
        instance.setMaxLoopCount(normalizeLoop(dto.getMaxLoopCount()));
        // null / MODEL_DEFAULT = follow the model route default (stored as NULL).
        instance.setCompletionProtocol(CompletionProtocol.isInherit(dto.getCompletionProtocol())
                ? null : CompletionProtocol.from(dto.getCompletionProtocol()).name());
        instance.setConfig(normalizeConfig(dto.getConfig()));
        instance.setInstanceRole(dto.getInstanceRole() == null || dto.getInstanceRole().isBlank()
                ? InstanceRole.CHAT.value() : InstanceRole.normalize(dto.getInstanceRole()));
        instance.setOwnerScope(dto.getOwnerScope() == null || dto.getOwnerScope().isBlank()
                ? OwnerScope.USER
                : OwnerScope.normalize(dto.getOwnerScope()));
        instance.setStatus(InstanceEnum.STATUS_ENABLED);
        return instance;
    }

    /** Normalize a blank config to null (never store an empty string). */
    private String normalizeConfig(String config) {
        return config != null && !config.isBlank() ? config : null;
    }

    /** Normalize ≤0 / null to null (null=not configured; 0 is only an update "clear" signal handled separately in the service layer). */
    private Integer normalizeLoop(Integer maxLoopCount) {
        return maxLoopCount != null && maxLoopCount > 0 ? maxLoopCount : null;
    }
}
