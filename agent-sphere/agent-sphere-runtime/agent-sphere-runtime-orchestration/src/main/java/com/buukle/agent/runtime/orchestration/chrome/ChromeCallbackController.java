package com.buukle.agent.runtime.orchestration.chrome;

import com.buukle.agent.common.chrome.ChromeCallbackDTO;
import com.buukle.agent.common.chrome.ChromePendingStore;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.ScreenshotEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/chrome")
public class ChromeCallbackController extends BaseController {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @PostMapping("/callback")
    public ResponseEntity<?> callback(@RequestParam Long sessionId,
                                      @RequestBody ChromeCallbackDTO body) {
        ChromePendingStore.complete(body.getCommandId(), body);

        if (body.getScreenshot() != null) {
            RuntimeEventDataVO data = new RuntimeEventDataVO()
                .setSessionId(sessionId)
                .setScreenshot(body.getScreenshot())
                .setResponse(extractUrl(body.getData()));
            RuntimeEventVO event = new RuntimeEventVO(ScreenshotEventType.PAGE_SCREENSHOT, data);
            Thread.startVirtualThread(() -> eventPublisher.publishEvent(event));
        }

        if (!body.isSuccess()) {
            log.warn("Chrome operation failed: commandId={}, error={}", body.getCommandId(), body.getError());
        }
        return ok("ok");
    }

    private String extractUrl(Object data) {
        if (data instanceof Map<?, ?> map) {
            Object url = map.get("url");
            return url instanceof String ? (String) url : null;
        }
        return null;
    }
}
