package com.buukle.agent.instance.service.converter;

import com.buukle.agent.common.constant.CompletionProtocol;
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
        vo.setModelRouteId(instance.getModelRouteId());
        vo.setCustomInstructions(instance.getCustomInstructions());
        vo.setImage(instance.getImage());
        vo.setStatus(instance.getStatus());
        vo.setBusinessType(instance.getBusinessType());
        vo.setAllowConsoleSession(instance.getAllowConsoleSession());
        vo.setAllowWidgetSession(instance.getAllowWidgetSession());
        vo.setMaxLoopCount(instance.getMaxLoopCount());
        vo.setCompletionProtocol(instance.getCompletionProtocol());
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
        // 两个来源开关刻意**不设默认值**：留 null 时 create 走库默认（主站放行/widget 禁止），
        // update 则被 MyBatis-Plus 忽略、表示"不改动"。若在这里补默认值，任何一次不携带这两个字段的
        // 更新都会把开关静默重置回默认，把管理员的选择抹掉。
        instance.setAllowConsoleSession(dto.getAllowConsoleSession());
        instance.setAllowWidgetSession(dto.getAllowWidgetSession());
        instance.setMaxLoopCount(normalizeLoop(dto.getMaxLoopCount()));
        // null / MODEL_DEFAULT = follow the model route default (stored as NULL).
        instance.setCompletionProtocol(CompletionProtocol.isInherit(dto.getCompletionProtocol())
                ? null : CompletionProtocol.from(dto.getCompletionProtocol()).name());
        instance.setConfig(normalizeConfig(dto.getConfig()));
        instance.setStatus(InstanceEnum.STATUS_ENABLED);
        return instance;
    }

    /** 空白 config 归一到 null（不落空串）。 */
    private String normalizeConfig(String config) {
        return config != null && !config.isBlank() ? config : null;
    }

    /** ≤0 / null 归一到 null（null=未配置，0 仅作为 update 的“清除”信号在 service 层单独处理）。 */
    private Integer normalizeLoop(Integer maxLoopCount) {
        return maxLoopCount != null && maxLoopCount > 0 ? maxLoopCount : null;
    }
}
