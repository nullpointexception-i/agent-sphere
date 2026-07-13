package com.buukle.agent.model.service.config;

import com.buukle.agent.common.security.CryptoService;
import com.buukle.agent.model.domain.AgentApiKey;
import com.buukle.agent.model.repository.ApiKeyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyEncryptionRunner implements ApplicationRunner {

    private final ApiKeyMapper apiKeyMapper;
    private final CryptoService cryptoService;

    @Override
    public void run(ApplicationArguments args) {
        List<AgentApiKey> keys = apiKeyMapper.selectList(null);
        int encrypted = 0;
        for (AgentApiKey key : keys) {
            String value = key.getKeyValue();
            if (value == null || value.isBlank()) continue;
            try {
                cryptoService.decrypt(value);
            } catch (Exception e) {
                key.setKeyValue(cryptoService.encrypt(value));
                apiKeyMapper.updateById(key);
                encrypted++;
            }
        }
        if (encrypted > 0) {
            log.info("Encrypted {} existing API key(s) in database", encrypted);
        }
    }
}
