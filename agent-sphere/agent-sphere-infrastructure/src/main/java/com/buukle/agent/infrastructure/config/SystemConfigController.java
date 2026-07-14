package com.buukle.agent.infrastructure.config;

import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.security.CryptoService;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.model.domain.AgentApiKey;
import com.buukle.agent.model.repository.ApiKeyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/system/config")
@RequiredArgsConstructor
public class SystemConfigController extends BaseController {

    private final SystemConfigSpi systemConfigSpi;
    private final SystemConfigServiceImpl systemConfigService;
    private final CryptoService cryptoService;
    private final ApiKeyMapper apiKeyMapper;

    @RequirePermission("admin:settings:read")
    @GetMapping
    public ResponseEntity<?> listConfigs() {
        var configs = systemConfigService.listAll().stream()
                .map(c -> {
                    var masked = c.getIsSecret() != null && c.getIsSecret()
                            ? "****" : c.getConfigValue();
                    return Map.of(
                            "configGroup", c.getConfigGroup(),
                            "configKey", c.getConfigKey(),
                            "configValue", masked,
                            "isSecret", c.getIsSecret() != null && c.getIsSecret(),
                            "description", c.getDescription()
                    );
                }).toList();
        return ok(configs);
    }

    @AuditLog(action = "UPDATE", resourceType = "Settings", resourceId = "#key")
    @RequirePermission("admin:settings:update")
    @PutMapping("/{key}")
    public ResponseEntity<?> updateConfig(@PathVariable String key, @RequestBody Map<String, String> body) {
        String value = body.get("value");
        systemConfigSpi.set(key, value);
        systemConfigService.invalidateCache(key);
        return ok(Map.of("message", "已更新"));
    }

    @AuditLog(action = "REGENERATE", resourceType = "Settings", resourceId = "'aes-key'")
    @RequirePermission("admin:settings:regenerate-aes")
    @PostMapping("/crypto.aes-key/regenerate")
    public ResponseEntity<?> regenerateAesKey() {
        String oldBase64Key = systemConfigSpi.get(SystemConfigKeys.AES_KEY);

        // 2. Generate new key
        java.security.SecureRandom secureRandom = new java.security.SecureRandom();
        byte[] newKeyBytes = new byte[32];
        secureRandom.nextBytes(newKeyBytes);
        String newBase64Key = java.util.Base64.getEncoder().encodeToString(newKeyBytes);

        // 3. Re-encrypt all existing API keys
        List<AgentApiKey> allKeys = apiKeyMapper.selectList(null);
        int reEncrypted = 0;
        for (AgentApiKey key : allKeys) {
            String rawValue = key.getKeyValue();
            if (rawValue == null || rawValue.isBlank()) continue;
            try {
                // Decrypt with old key
                String decrypted = cryptoService.decrypt(rawValue);
                if (decrypted != null) {
                    // Set new key in crypto service
                    cryptoService.setKey(newBase64Key);
                    // Re-encrypt with new key
                    key.setKeyValue(cryptoService.encrypt(decrypted));
                    apiKeyMapper.updateById(key);
                    reEncrypted++;
                    // Restore old key for remaining keys
                    cryptoService.setKey(oldBase64Key);
                }
            } catch (Exception e) {
                // Key wasn't encrypted with old key, try direct encrypt with new key
                cryptoService.setKey(newBase64Key);
                key.setKeyValue(cryptoService.encrypt(rawValue));
                apiKeyMapper.updateById(key);
                reEncrypted++;
                cryptoService.setKey(oldBase64Key);
            }
        }

        systemConfigSpi.set(SystemConfigKeys.AES_KEY, newBase64Key);
        systemConfigService.invalidateCache(SystemConfigKeys.AES_KEY);

        // 5. Set new key in crypto service
        cryptoService.setKey(newBase64Key);

        log.info("AES key regenerated, re-encrypted {} API key(s)", reEncrypted);

        return ok(Map.of("message", "AES 密钥已重新生成，已重新加密 " + reEncrypted + " 个 API Key"));
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SystemConfigController.class);
}
