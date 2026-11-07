package com.buukle.agent.infrastructure.controller;


import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.infrastructure.service.PluginFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Plugin installer hosting API:
 * - POST /upload — admin uploads a .zip (writes plugin.download-url to the hosted route)
 * - DELETE /     — admin deletes the hosted package
 * - GET  /download — public download (AuthInterceptor whitelist; reachable directly from the login page/widget entry)
 */
@RestController
@RequestMapping(ApiPrefix.V1 + "/system/config/plugin")
@RequiredArgsConstructor
public class PluginFileController extends BaseController {

    private final PluginFileService pluginFileService;

    @RequirePermission(PermissionCodes.ADMIN_SETTINGS_UPDATE)
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        pluginFileService.upload(file);
        return ok(java.util.Map.of("message", "Uploaded"));
    }

    @RequirePermission(PermissionCodes.ADMIN_SETTINGS_UPDATE)
    @DeleteMapping
    public ResponseEntity<?> delete() {
        pluginFileService.delete();
        return ok(java.util.Map.of("message", "Deleted"));
    }

    @GetMapping("/download")
    public ResponseEntity<?> download() {
        return pluginFileService.download();
    }
}