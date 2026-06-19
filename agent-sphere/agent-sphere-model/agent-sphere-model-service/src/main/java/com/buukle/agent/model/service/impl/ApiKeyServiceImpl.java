package com.buukle.agent.model.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.model.domain.AgentApiKey;
import com.buukle.agent.model.repository.ApiKeyMapper;
import com.buukle.agent.model.service.ApiKeyService;
import com.buukle.agent.model.service.converter.ApiKeyConverter;
import com.buukle.agent.model.dtvo.dto.CreateApiKeyDTO;
import com.buukle.agent.model.dtvo.vo.ApiKeyVO;
import com.buukle.agent.model.exception.ModelErrorCode;
import com.buukle.agent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ApiKeyServiceImpl extends ServiceImpl<ApiKeyMapper, AgentApiKey> implements ApiKeyService {
    private final ApiKeyConverter apiKeyConverter;

    @Override
    public ApiKeyVO createApiKey(CreateApiKeyDTO dto) {
        AgentApiKey apiKey = apiKeyConverter.toDO(dto);
        save(apiKey);
        return apiKeyConverter.toVO(apiKey);
    }

    @Override
    public ApiKeyVO getApiKey(Long id) {
        AgentApiKey apiKey = getById(id);
        if (apiKey == null) throw new BizException(ModelErrorCode.API_KEY_NOT_FOUND);
        return apiKeyConverter.toVO(apiKey);
    }

    @Override
    public List<ApiKeyVO> listApiKeysByProvider(Long providerId, String keyword) {
        List<AgentApiKey> apiKeys = lambdaQuery()
            .eq(AgentApiKey::getProviderId, providerId)
            .like(keyword != null && !keyword.isBlank(), AgentApiKey::getAlias, keyword)
            .orderByDesc(AgentApiKey::getCreatedAt)
            .list();
        return apiKeys.stream().map(apiKeyConverter::toVO).toList();
    }

    @Override
    public ApiKeyVO updateApiKey(Long id, CreateApiKeyDTO dto) {
        AgentApiKey existing = getById(id);
        if (existing == null) throw new BizException(ModelErrorCode.API_KEY_NOT_FOUND);
        AgentApiKey updated = apiKeyConverter.toDO(dto);
        // Preserve original key if the incoming value is already masked
        if (dto.getKeyValue() != null && dto.getKeyValue().contains("...")) {
            updated.setKeyValue(existing.getKeyValue());
        }
        updated.setId(id);
        updateById(updated);
        return apiKeyConverter.toVO(updated);
    }

    @Override
    public void deleteApiKey(Long id) {
        removeById(id);
    }

    @Override
    public String getApiKeyValue(Long id) {
        AgentApiKey entity = getById(id);
        return entity != null ? entity.getKeyValue() : null;
    }
}
