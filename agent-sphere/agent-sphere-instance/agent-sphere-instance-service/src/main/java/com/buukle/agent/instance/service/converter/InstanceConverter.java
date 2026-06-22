package com.buukle.agent.instance.service.converter;

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
        instance.setStatus(InstanceEnum.STATUS_ENABLED);
        return instance;
    }
}
