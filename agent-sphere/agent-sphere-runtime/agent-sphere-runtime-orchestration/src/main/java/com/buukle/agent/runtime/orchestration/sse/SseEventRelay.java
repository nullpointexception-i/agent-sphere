package com.buukle.agent.runtime.orchestration.sse;

import com.buukle.agent.common.chrome.ChromeCommandDTO;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.infrastructure.eventbus.RedisEventBus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.orchestration.chrome.ChromeCommandEnvelope;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 事件总线 → 本地 SSE 投递 relay。
 * 各副本（含发布者自己）订阅：session 事件投 {@link SseManager#sendBySession}，
 * 浏览器指令投 {@link SseManager#sendByUser}。单路径投递，无重复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseEventRelay {

    private final RedisEventBus eventBus;
    private final SseManager sseManager;

    @PostConstruct
    public void init() {
        subscribe();
    }

    public void subscribe() {
        eventBus.subscribe(DistributedRuntimeConstants.TOPIC_EVENTS, RuntimeEventVO.class, this::onSessionEvent);
        eventBus.subscribe(DistributedRuntimeConstants.TOPIC_CHROME_COMMAND, ChromeCommandEnvelope.class, this::onChromeCommand);
    }

    private void onSessionEvent(RuntimeEventVO event) {
        if (event == null || event.getData() == null || event.getData().getSessionId() == null) {
            return;
        }
        sseManager.sendBySession(event.getData().getSessionId(), event);
    }

    private void onChromeCommand(ChromeCommandEnvelope envelope) {
        if (envelope == null || envelope.getUsername() == null) {
            return;
        }
        ChromeCommandDTO command = envelope.getCommand();
        if (command != null) {
            sseManager.sendByUser(envelope.getUsername(), command);
        }
    }
}
