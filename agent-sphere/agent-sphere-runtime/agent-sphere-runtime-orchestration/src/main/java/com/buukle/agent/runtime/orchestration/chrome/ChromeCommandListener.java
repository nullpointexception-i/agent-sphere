package com.buukle.agent.runtime.orchestration.chrome;

import com.buukle.agent.common.chrome.ChromeCommandDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChromeCommandListener {

    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void onChromeCommand(ChromeCommandDTO cmd) {
        eventPublisher.publishEvent(new ChromeCommandEvent(cmd));
    }
}
