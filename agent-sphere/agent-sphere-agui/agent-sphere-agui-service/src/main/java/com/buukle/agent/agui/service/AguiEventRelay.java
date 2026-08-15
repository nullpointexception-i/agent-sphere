package com.buukle.agent.agui.service;

import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.infrastructure.eventbus.RedisEventBus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AG-UI 事件 relay：订阅 `runtime.agui` → 投本副本 {@link AguiStreamManager}。
 * 跨副本场景：run 在 A 执行、SSE 连到 B，A 翻译发布，B 的 relay 投 B 本地 emitter。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AguiEventRelay {

    private final RedisEventBus eventBus;
    private final AguiStreamManager streamManager;

    @PostConstruct
    public void init() {
        eventBus.subscribe(DistributedRuntimeConstants.TOPIC_AGUI,
                AguiEventEnvelope.class, this::onEnvelope);
    }

    private void onEnvelope(AguiEventEnvelope envelope) {
        if (envelope == null || envelope.getSessionId() == null) {
            return;
        }
        if (envelope.isTerminal()) {
            streamManager.complete(envelope.getSessionId(), envelope.getRunId());
            return;
        }
        if (envelope.getEvent() != null) {
            streamManager.send(envelope.getSessionId(), envelope.getRunId(), envelope.getEvent());
        }
    }
}
