package com.buukle.agent.model.spi;

import com.buukle.agent.model.dtvo.dto.CreateApiKeyDTO;
import com.buukle.agent.model.dtvo.vo.ApiKeyVO;

import java.util.List;

public interface ApiKeySpi {
    ApiKeyVO createApiKey(CreateApiKeyDTO dto);

    ApiKeyVO getApiKey(Long id);

    List<ApiKeyVO> listApiKeysByProvider(Long providerId, String keyword);

    ApiKeyVO updateApiKey(Long id, CreateApiKeyDTO dto);

    void deleteApiKey(Long id);

    /**
     * Returns the raw (unmasked) API key value for LLM calls.
     */
    String getApiKeyValue(Long id);
}
