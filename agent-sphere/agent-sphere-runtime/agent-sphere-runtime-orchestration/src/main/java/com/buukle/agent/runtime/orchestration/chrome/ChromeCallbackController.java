package com.buukle.agent.runtime.orchestration.chrome;

import com.buukle.agent.common.chrome.ChromeCallbackDTO;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.infrastructure.eventbus.RedisEventBus;
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
    private final RedisEventBus eventBus;

    @PostMapping("/callback")
    public ResponseEntity<?> callback(@RequestParam Long sessionId,
                                      @RequestBody ChromeCallbackDTO body,
                                      HttpServletRequest request) {
        String token = systemConfigSpi.get(SystemConfigKeys.CHROME_EXTENSION_TOKEN, "");
        if (!token.isEmpty()
                && !Objects.equals(token, request.getHeader("X-Extension-Token"))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 多副本：执行副本的 pending future 可能在别的副本上，经事件总线广播完成（去掉本地 contains 门禁）
        eventBus.publish(DistributedRuntimeConstants.TOPIC_CHROME_CALLBACK, body);

        if (!body.isSuccess()) {
            log.warn("Chrome operation failed: commandId={}, error={}", body.getCommandId(), body.getError());
        }
        return ok("ok");
    }
}
