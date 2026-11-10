package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.superagent.service.event.GroupRouterWakeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Real-time Group Router wake-up.
 *
 * <p>Wakes are coalesced by {@link GroupRouterWakeDebouncer} so a burst of chat lines becomes one
 * router batch instead of one run per message. The scheduled group router remains the compensation
 * path when a run is already in flight.
 */
@Slf4j
@Component
public class GroupRouterRealtimeTrigger {

    private final GroupRouterWakeDebouncer debouncer;

    @Value("${buukle.agent.super-agent.enabled:false}")
    private boolean superAgentEnabled;
    @Value("${buukle.agent.super-agent.group-router.enabled:false}")
    private boolean groupRouterEnabled;

    public GroupRouterRealtimeTrigger(GroupRouterWakeDebouncer debouncer) {
        this.debouncer = debouncer;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWake(GroupRouterWakeEvent event) {
        if (!superAgentEnabled || !groupRouterEnabled
                || event == null || event.threadId() == null) {
            return;
        }
        debouncer.schedule(event.threadId());
    }
}
