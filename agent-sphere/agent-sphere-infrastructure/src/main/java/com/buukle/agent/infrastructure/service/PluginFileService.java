package com.buukle.agent.infrastructure.service;

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
 * Chrome extension installer hosting: the uploaded zip is persisted via {@link GenericFileService}
 * (PG bytea, consistent across replicas sharing the DB), and plugin.download-url is written as an
 * apiBase-relative download route.
 * The frontend (console login page / widget) reads it via the public config and shows the download entry.
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

    /** Uploads and hosts the zip: validates type/size, and on successful persistence refreshes plugin.download-url to the hosted route. */
    public void upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("plugin package must not be empty");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("plugin package exceeds the 100 MB limit");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!name.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("only .zip plugin packages are supported");
        }
        try {
            genericFileService.save(BIZ_KEY, FILE_KEY, FILE_NAME, "application/zip", file.getBytes());
            systemConfigSpi.set(SystemConfigKeys.PLUGIN_DOWNLOAD_URL, DOWNLOAD_ROUTE);
            systemConfigService.invalidateCache(SystemConfigKeys.PLUGIN_DOWNLOAD_URL);
            log.info("Plugin package stored to DB: {} bytes", file.getSize());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to save plugin package: " + e.getMessage());
        }
    }

    /** Deletes the hosted package and clears plugin.download-url (the frontend hides the download entry based on it). */
    public void delete() {
        genericFileService.delete(BIZ_KEY, FILE_KEY);
        systemConfigSpi.set(SystemConfigKeys.PLUGIN_DOWNLOAD_URL, "");
        systemConfigService.invalidateCache(SystemConfigKeys.PLUGIN_DOWNLOAD_URL);
        log.info("Plugin package removed");
    }

    /** Downloads the hosted package: returns 404 if there is no file. */
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