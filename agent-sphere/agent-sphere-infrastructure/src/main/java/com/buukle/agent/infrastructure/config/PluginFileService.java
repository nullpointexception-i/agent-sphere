package com.buukle.agent.infrastructure.config;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Chrome 插件安装包托管：zip 上传后存本地目录，并向 system config 写入托管下载路由
 * （plugin.download-url）。前端（主站登录页 / widget）经公开配置读取后展示下载入口。
 * 存储目录默认 /app/data/plugin，生产建议挂卷持久化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginFileService {

    public static final String DOWNLOAD_ROUTE = "/system/config/plugin/download";
    public static final String FILE_NAME = "agent-sphere-chrome-extension.zip";
    private static final long MAX_SIZE_BYTES = 100 * 1024 * 1024; // 100MB

    private final SystemConfigSpi systemConfigSpi;
    private final SystemConfigServiceImpl systemConfigService;

    @Value("${buukle.agent.plugin.storage-path:/app/data/plugin}")
    private String storagePath;

    /** 上传并托管 zip：校验类型/大小，写盘成功后刷新 plugin.download-url 为托管路由。 */
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
            Path dir = storageDir();
            Files.copy(file.getInputStream(), dir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
            systemConfigSpi.set(SystemConfigKeys.PLUGIN_DOWNLOAD_URL, DOWNLOAD_ROUTE);
            systemConfigService.invalidateCache(SystemConfigKeys.PLUGIN_DOWNLOAD_URL);
            log.info("Plugin package uploaded to {}", dir.resolve(FILE_NAME));
        } catch (IOException e) {
            throw new IllegalStateException("插件安装包保存失败: " + e.getMessage());
        }
    }

    /** 删除托管包并清空 plugin.download-url（前端据此隐藏下载入口）。 */
    public void delete() {
        try {
            Files.deleteIfExists(storageDir().resolve(FILE_NAME));
        } catch (IOException e) {
            log.warn("Delete plugin package failed", e);
            throw new IllegalStateException("插件安装包删除失败: " + e.getMessage());
        }
        systemConfigSpi.set(SystemConfigKeys.PLUGIN_DOWNLOAD_URL, "");
        systemConfigService.invalidateCache(SystemConfigKeys.PLUGIN_DOWNLOAD_URL);
        log.info("Plugin package removed");
    }

    /** 下载托管包：无文件返回 null。 */
    public ResponseEntity<org.springframework.core.io.Resource> download() {
        Path path = storageDir().resolve(FILE_NAME);
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        FileSystemResource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + FILE_NAME + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    public boolean hasPackage() {
        return StringUtils.hasText(systemConfigSpi.get(SystemConfigKeys.PLUGIN_DOWNLOAD_URL, ""))
                && Files.exists(storageDir().resolve(FILE_NAME));
    }

    private Path storageDir() {
        Path dir = Path.of(storagePath);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("插件存储目录创建失败: " + e.getMessage());
        }
        return dir;
    }
}