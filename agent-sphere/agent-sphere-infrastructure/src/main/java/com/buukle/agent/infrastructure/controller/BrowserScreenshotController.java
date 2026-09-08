package com.buukle.agent.infrastructure.controller;

import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.infrastructure.controller.dtvo.ScreenshotUploadDTO;
import com.buukle.agent.infrastructure.service.ScreenshotFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 浏览器截图上报：
 * - POST /api/v1/browser/screenshot 登录用户/扩展以 base64 上报截图，返回 {fileKey, contentType, sizeBytes}；
 *   运行时按 fileKey 回读拼 data URL 注入模型视觉观察。
 */
@RestController
@RequestMapping("/api/v1/browser")
@RequiredArgsConstructor
public class BrowserScreenshotController extends BaseController {

    private final ScreenshotFileService screenshotFileService;

    @PostMapping("/screenshot")
    public ResponseEntity<?> screenshot(@RequestBody ScreenshotUploadDTO body) {
        return ok(screenshotFileService.store(body.base64(), body.contentType()));
    }
}