package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.infrastructure.config.PluginFileService;
import com.buukle.agent.infrastructure.config.SystemConfigServiceImpl;
import com.buukle.agent.infrastructure.file.GenericFileService;
import com.buukle.agent.infrastructure.file.StoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PluginFileServiceTest {

    @Mock
    GenericFileService genericFileService;
    @Mock
    SystemConfigSpi systemConfigSpi;
    @Mock
    SystemConfigServiceImpl systemConfigService;

    PluginFileService pluginFileService;

    @BeforeEach
    void setUp() {
        // SystemConfigServiceImpl 也实现 SystemConfigSpi：显式构造避免 @InjectMocks 类型注入歧义
        pluginFileService = new PluginFileService(genericFileService, systemConfigSpi, systemConfigService);
    }

    @Test
    void upload_storesFileAndSetsDownloadRoute() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "plugin.zip", "application/zip", new byte[]{1, 2, 3});

        pluginFileService.upload(file);

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(genericFileService).save(
                org.mockito.ArgumentMatchers.eq(PluginFileService.BIZ_KEY),
                org.mockito.ArgumentMatchers.eq(PluginFileService.FILE_KEY),
                org.mockito.ArgumentMatchers.eq(PluginFileService.FILE_NAME),
                org.mockito.ArgumentMatchers.eq("application/zip"),
                contentCaptor.capture());
        assertArrayEquals(new byte[]{1, 2, 3}, contentCaptor.getValue());
        verify(systemConfigSpi).set(SystemConfigKeys.PLUGIN_DOWNLOAD_URL, PluginFileService.DOWNLOAD_ROUTE);
        verify(systemConfigService).invalidateCache(SystemConfigKeys.PLUGIN_DOWNLOAD_URL);
    }

    @Test
    void upload_rejectsNonZip() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "plugin.exe", "application/octet-stream", new byte[]{1});

        assertThrows(IllegalArgumentException.class, () -> pluginFileService.upload(file));
    }

    @Test
    void upload_rejectsOversized() {
        byte[] huge = new byte[100 * 1024 * 1024 + 1];
        Arrays.fill(huge, (byte) 1);
        MockMultipartFile file = new MockMultipartFile(
                "file", "plugin.zip", "application/zip", huge);

        assertThrows(IllegalArgumentException.class, () -> pluginFileService.upload(file));
    }

    @Test
    void download_returnsBytesWhenStored() {
        StoredFile stored = new StoredFile("plugin-package", "plugin.zip",
                "agent-sphere-chrome-extension.zip", "application/zip", 3L, new byte[]{1, 2, 3});
        given(genericFileService.get(PluginFileService.BIZ_KEY, PluginFileService.FILE_KEY))
                .willReturn(stored);

        var response = pluginFileService.download();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(new byte[]{1, 2, 3}, response.getBody());
        assertEquals("attachment; filename=\"agent-sphere-chrome-extension.zip\"",
                response.getHeaders().getFirst("Content-Disposition"));
        assertEquals(3L, response.getHeaders().getContentLength());
    }

    @Test
    void download_returns404WhenAbsent() {
        given(genericFileService.get(anyString(), anyString())).willReturn(null);

        assertEquals(HttpStatus.NOT_FOUND, pluginFileService.download().getStatusCode());
    }

    @Test
    void delete_removesFileAndClearsConfig() {
        pluginFileService.delete();

        verify(genericFileService).delete(PluginFileService.BIZ_KEY, PluginFileService.FILE_KEY);
        verify(systemConfigSpi).set(SystemConfigKeys.PLUGIN_DOWNLOAD_URL, "");
        verify(systemConfigService).invalidateCache(SystemConfigKeys.PLUGIN_DOWNLOAD_URL);
    }

    @Test
    void hasPackage_requiresConfigAndStoredFile() {
        given(systemConfigSpi.get(SystemConfigKeys.PLUGIN_DOWNLOAD_URL, "")).willReturn(PluginFileService.DOWNLOAD_ROUTE);
        given(genericFileService.exists(PluginFileService.BIZ_KEY, PluginFileService.FILE_KEY)).willReturn(true);
        assertTrue(pluginFileService.hasPackage());

        given(genericFileService.exists(PluginFileService.BIZ_KEY, PluginFileService.FILE_KEY)).willReturn(false);
        assertFalse(pluginFileService.hasPackage());
    }
}