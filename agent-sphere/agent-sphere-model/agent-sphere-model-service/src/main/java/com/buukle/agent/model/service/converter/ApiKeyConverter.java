package com.buukle.agent.model.service.converter;

import com.buukle.agent.common.security.CryptoService;
import com.buukle.agent.model.domain.AgentApiKey;
import com.buukle.agent.model.dtvo.dto.CreateApiKeyDTO;
import com.buukle.agent.model.dtvo.enums.ApiKeyEnum;
import com.buukle.agent.model.dtvo.vo.ApiKeyVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class ApiKeyConverter {
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CryptoService cryptoService;

    private static String maskApiKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    public ApiKeyVO toVO(AgentApiKey apiKey) {
        if (apiKey == null) return null;
        ApiKeyVO vo = new ApiKeyVO();
        vo.setId(apiKey.getId());
        vo.setProviderId(apiKey.getProviderId());
        vo.setAlias(apiKey.getAlias());
        vo.setKeyValue(maskApiKey(apiKey.getKeyValue()));
        vo.setExpiresAt(apiKey.getExpiresAt() != null ? apiKey.getExpiresAt().format(DTF) : null);
        vo.setStatus(apiKey.getStatus());
        vo.setCreatedAt(apiKey.getCreatedAt() != null ? apiKey.getCreatedAt().format(DTF) : null);
        vo.setCreatedBy(apiKey.getCreatedBy());
        vo.setUpdatedBy(apiKey.getUpdatedBy());
        vo.setUpdatedAt(apiKey.getUpdatedAt() != null ? apiKey.getUpdatedAt().format(DTF) : null);
        return vo;
    }

    public AgentApiKey toDO(CreateApiKeyDTO dto) {
        AgentApiKey apiKey = new AgentApiKey();
        apiKey.setProviderId(dto.getProviderId());
        apiKey.setAlias(dto.getAlias());
        apiKey.setKeyValue(cryptoService.encrypt(dto.getKeyValue()));
        apiKey.setExpiresAt(dto.getExpiresAt());
        apiKey.setStatus(ApiKeyEnum.STATUS_ACTIVE);
        return apiKey;
    }
}
