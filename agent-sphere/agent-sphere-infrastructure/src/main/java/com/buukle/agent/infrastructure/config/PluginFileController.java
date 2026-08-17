package com.buukle.agent.infrastructure.config;

import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.util.BaseController;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 插件安装包托管 API：
 * - POST /upload 管理员上传 .zip（写 plugin.download-url 为托管路由）
 * - DELETE /     管理员删除托管包
 * - GET  /download 公开下载（AuthInterceptor 白名单，登录页/widget 入口直达）
 */
@RestController
@RequestMapping("/api/v1/system/config/plugin")
@RequiredArgsConstructor
public class PluginFileController extends BaseController {

    private final PluginFileService pluginFileService;

    @RequirePermission("admin:settings:update")
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        pluginFileService.upload(file);
        return ok(java.util.Map.of("message", "上传成功"));
    }

    @RequirePermission("admin:settings:update")
    @DeleteMapping
    public ResponseEntity<?> delete() {
        pluginFileService.delete();
        return ok(java.util.Map.of("message", "已删除"));
    }

    @GetMapping("/download")
    public ResponseEntity<?> download() {
        return pluginFileService.download();
    }
}