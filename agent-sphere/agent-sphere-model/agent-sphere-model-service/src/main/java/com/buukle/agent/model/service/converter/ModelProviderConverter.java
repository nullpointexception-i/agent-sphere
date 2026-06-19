package com.buukle.agent.model.service.converter;

import com.buukle.agent.model.domain.AgentModelProvider;
import com.buukle.agent.model.dtvo.dto.CreateModelProviderDTO;
import com.buukle.agent.model.dtvo.vo.ModelProviderVO;
import com.buukle.agent.model.dtvo.enums.ModelProviderEnum;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
public class ModelProviderConverter {
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public ModelProviderVO toVO(AgentModelProvider provider) {
        if (provider == null) return null;
        ModelProviderVO vo = new ModelProviderVO();
        vo.setId(provider.getId());
        vo.setName(provider.getName());
        vo.setBaseUrl(provider.getBaseUrl());
        vo.setApiKeyId(provider.getApiKeyId());
        vo.setConfig(provider.getConfig());
        vo.setStatus(provider.getStatus());
        vo.setCreatedAt(provider.getCreatedAt() != null ? provider.getCreatedAt().format(DTF) : null);
        vo.setCreatedBy(provider.getCreatedBy());
        vo.setUpdatedBy(provider.getUpdatedBy());
        vo.setUpdatedAt(provider.getUpdatedAt() != null ? provider.getUpdatedAt().format(DTF) : null);
        return vo;
    }

    public AgentModelProvider toDO(CreateModelProviderDTO dto) {
        AgentModelProvider provider = new AgentModelProvider();
        provider.setName(dto.getName());
        provider.setBaseUrl(dto.getBaseUrl());
        provider.setApiKeyId(dto.getApiKeyId());
        provider.setConfig(emptyToJson(dto.getConfig()));
        provider.setStatus(ModelProviderEnum.STATUS_ACTIVE);
        return provider;
    }

    private static String emptyToJson(String value) {
        return (value == null || value.isBlank()) ? "{}" : value;
    }
}
