package com.buukle.agent.runtime.orchestration.chrome;

import com.buukle.agent.common.chrome.ChromeCallbackDTO;
import com.buukle.agent.common.chrome.ChromePendingStore;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.infrastructure.eventbus.RedisEventBus;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Chrome callback relay: when a cross-replica broadcast extension callback lands on this replica,
 * it completes the local pending future. Futures registered by tools on the **executing replica**
 * are completed here, with zero changes to the extension protocol or the tool side.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChromeCallbackRelay {

    private final RedisEventBus eventBus;
    private final AgentToolCallRecordSpi toolCallRecordSpi;

    @PostConstruct
    public void init() {
        eventBus.subscribe(DistributedRuntimeConstants.TOPIC_CHROME_CALLBACK,
                ChromeCallbackDTO.class, this::onCallback);
    }

    private void onCallback(ChromeCallbackDTO body) {
        if (body == null || body.getCommandId() == null) {
            return;
        }
        ChromePendingStore.complete(body.getCommandId(), body);
        recordExecutionDevice(body);
    }

    /** Record which browser executed this tool call; skipped when the extension omits device info (older versions). */
    private void recordExecutionDevice(ChromeCallbackDTO body) {
        if (body.getRunId() == null || body.getCallId() == null || body.getDeviceId() == null) {
            return;
        }
        try {
            toolCallRecordSpi.updateExecutionDevice(body.getRunId(), body.getCallId(),
                    body.getDeviceId(), body.getDeviceName(), body.getSource());
        } catch (Exception e) {
            log.warn("Record execution device failed: runId={}, callId={}, device={}",
                    body.getRunId(), body.getCallId(), body.getDeviceId(), e);
        }
    }
}
