package com.buukle.agent.infrastructure.config;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.infrastructure.file.GenericFileService;
import com.buukle.agent.infrastructure.file.StoredFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Chrome 插件安装包托管：上传的 zip 经 {@link GenericFileService} 落库（PG bytea，
 * 多副本共享库一致），并写 plugin.download-url 为 apiBase 相对下载路由。
 * 前端（主站登录页 / widget）经公开配置读取后展示下载入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginFileService {

    public static final String DOWNLOAD_ROUTE = "/system/config/plugin/download";
    public static final String BIZ_KEY = "plugin-package";
    public static final String FILE_KEY = "plugin.zip";
    public static final String FILE_NAME = "agent-sphere-chrome-extension.zip";
    private static final long MAX_SIZE_BYTES = 100 * 1024 * 1024; // 100MB

    private final GenericFileService genericFileService;
    private final SystemConfigSpi systemConfigSpi;
    private final SystemConfigServiceImpl systemConfigService;

    /** 上传并托管 zip：校验类型/大小，落库成功后刷新 plugin.download-url 为托管路由。 */
    public void upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("插件安装包不能为空");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("插件安装包超过 100MB 上限");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!name.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("仅支持 .zip 格式的插件安装包");
        }
        try {
            genericFileService.save(BIZ_KEY, FILE_KEY, FILE_NAME, "application/zip", file.getBytes());
            systemConfigSpi.set(SystemConfigKeys.PLUGIN_DOWNLOAD_URL, DOWNLOAD_ROUTE);
            systemConfigService.invalidateCache(SystemConfigKeys.PLUGIN_DOWNLOAD_URL);
            log.info("Plugin package stored to DB: {} bytes", file.getSize());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("插件安装包保存失败: " + e.getMessage());
        }
    }

    /** 删除托管包并清空 plugin.download-url（前端据此隐藏下载入口）。 */
    public void delete() {
        genericFileService.delete(BIZ_KEY, FILE_KEY);
        systemConfigSpi.set(SystemConfigKeys.PLUGIN_DOWNLOAD_URL, "");
        systemConfigService.invalidateCache(SystemConfigKeys.PLUGIN_DOWNLOAD_URL);
        log.info("Plugin package removed");
    }

    /** 下载托管包：无文件返回 404。 */
    public ResponseEntity<byte[]> download() {
        StoredFile stored = genericFileService.get(BIZ_KEY, FILE_KEY);
        if (stored == null || stored.content() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + stored.fileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(stored.sizeBytes())
                .body(stored.content());
    }

    public boolean hasPackage() {
        return StringUtils.hasText(systemConfigSpi.get(SystemConfigKeys.PLUGIN_DOWNLOAD_URL, ""))
                && genericFileService.exists(BIZ_KEY, FILE_KEY);
    }
}