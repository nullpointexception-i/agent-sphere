package com.buukle.agent.runtime.orchestration.chrome;

import com.buukle.agent.common.chrome.ChromeCallbackDTO;
import com.buukle.agent.common.chrome.ChromePendingStore;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.infrastructure.eventbus.RedisEventBus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Chrome 回调 relay：跨副本广播的插件回调落到本副本后，完成本地 pending future。
 * 工具在**执行副本**注册的 future 由此完成，插件协议与工具侧零改动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChromeCallbackRelay {

    private final RedisEventBus eventBus;

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
    }
}
