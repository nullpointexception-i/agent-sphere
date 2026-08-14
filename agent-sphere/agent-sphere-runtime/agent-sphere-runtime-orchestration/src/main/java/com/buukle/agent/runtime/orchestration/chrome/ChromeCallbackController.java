package com.buukle.agent.runtime.orchestration.chrome;

import com.buukle.agent.common.chrome.ChromeCallbackDTO;
import com.buukle.agent.common.chrome.ChromePendingStore;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.util.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/api/v1/chrome")
@RequiredArgsConstructor
public class ChromeCallbackController extends BaseController {

    private final SystemConfigSpi systemConfigSpi;

    @PostMapping("/callback")
    public ResponseEntity<?> callback(@RequestParam Long sessionId,
                                      @RequestBody ChromeCallbackDTO body,
                                      HttpServletRequest request) {
        if (!ChromePendingStore.contains(body.getCommandId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String token = systemConfigSpi.get(SystemConfigKeys.CHROME_EXTENSION_TOKEN, "");
        if (!token.isEmpty()
                && !Objects.equals(token, request.getHeader("X-Extension-Token"))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        ChromePendingStore.complete(body.getCommandId(), body);

        if (!body.isSuccess()) {
            log.warn("Chrome operation failed: commandId={}, error={}", body.getCommandId(), body.getError());
        }
        return ok("ok");
    }
}
