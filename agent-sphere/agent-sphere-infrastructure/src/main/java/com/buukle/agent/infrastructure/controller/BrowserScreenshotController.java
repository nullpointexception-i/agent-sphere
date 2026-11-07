package com.buukle.agent.infrastructure.controller;


import com.buukle.agent.common.constant.ApiPrefix;
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
 * Browser screenshot upload:
 * - POST /api/v1/browser/screenshot — a logged-in user/extension uploads a screenshot as base64 and gets
 *   back {fileKey, contentType, sizeBytes}; the runtime reads it back by fileKey to build a data URL
 *   injected as the model's visual observation.
 */
@RestController
@RequestMapping(ApiPrefix.V1 + "/browser")
@RequiredArgsConstructor
public class BrowserScreenshotController extends BaseController {

    private final ScreenshotFileService screenshotFileService;

    @PostMapping("/screenshot")
    public ResponseEntity<?> screenshot(@RequestBody ScreenshotUploadDTO body) {
        return ok(screenshotFileService.store(body.base64(), body.contentType()));
    }
}