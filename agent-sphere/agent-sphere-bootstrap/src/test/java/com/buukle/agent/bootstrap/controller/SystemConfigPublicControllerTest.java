package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.security.CryptoService;
import com.buukle.agent.infrastructure.config.SystemConfigController;
import com.buukle.agent.infrastructure.config.SystemConfigServiceImpl;
import com.buukle.agent.model.repository.ApiKeyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SystemConfigPublicControllerTest {

    MockMvc mockMvc;

    @Mock
    SystemConfigSpi systemConfigSpi;
    @Mock
    SystemConfigServiceImpl systemConfigService;
    @Mock
    CryptoService cryptoService;
    @Mock
    ApiKeyMapper apiKeyMapper;

    @BeforeEach
    void setUp() {
        // SystemConfigServiceImpl 也实现 SystemConfigSpi：若用 @InjectMocks 类型注入会歧义
        // （同 JVM 先跑其他测试后，接口槽位可能错配到 impl mock），显式构造保证确定性。
        SystemConfigController controller = new SystemConfigController(
                systemConfigSpi, systemConfigService, cryptoService, apiKeyMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void publicConfig_returnsOnlyWhitelistedKeys() throws Exception {
        given(systemConfigSpi.get(SystemConfigKeys.PLUGIN_DOWNLOAD_URL, ""))
                .willReturn("/api/v1/system/config/plugin/download");
        given(systemConfigSpi.get(SystemConfigKeys.PLUGIN_STORE_URL, ""))
                .willReturn("https://chromewebstore.google.com/detail/agentsphere/cpjaeggemhjndbnepjifgckodnlfhfkg");

        mockMvc.perform(get("/api/v1/system/config/public")
                        .param("keys", "plugin.download-url,plugin.store-url,sso.base-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['plugin.download-url']")
                        .value("/api/v1/system/config/plugin/download"))
                .andExpect(jsonPath("$['plugin.store-url']")
                        .value("https://chromewebstore.google.com/detail/agentsphere/cpjaeggemhjndbnepjifgckodnlfhfkg"))
                .andExpect(jsonPath("$['sso.base-url']").doesNotExist());

        verify(systemConfigSpi).get(eq(SystemConfigKeys.PLUGIN_DOWNLOAD_URL), eq(""));
        verify(systemConfigSpi).get(eq(SystemConfigKeys.PLUGIN_STORE_URL), eq(""));
    }

    @Test
    void publicConfig_unknownKeysAreIgnored() throws Exception {
        mockMvc.perform(get("/api/v1/system/config/public")
                        .param("keys", "crypto.aes-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['crypto.aes-key']").doesNotExist());
    }
}