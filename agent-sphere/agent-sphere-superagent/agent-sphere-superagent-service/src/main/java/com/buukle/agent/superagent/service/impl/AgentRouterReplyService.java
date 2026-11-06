package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.superagent.spi.AgentRouterReplyPort;
import com.buukle.agent.superagent.tool.AgentRouterToolService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Bridges {@code RunStatus.COMPLETED} fragments into the IM outbound path.
 *
 * <p>Called from the stream bridge on the flush thread; inherits no request context.
 */
@Slf4j
@Service
public class AgentRouterReplyService implements AgentRouterReplyPort {

    private final AgentRouterToolService toolService;

    @Value("${buukle.agent.super-agent.router.reply.auto-final-enabled:true}")
    private boolean autoFinalEnabled;

    public AgentRouterReplyService(AgentRouterToolService toolService) {
        this.toolService = toolService;
    }

    @Override
    public void sendFinalReply(Long sessionId, Long runId, Long threadId, String text) {
        if (!autoFinalEnabled) {
            return;
        }
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            toolService.sendFinalReply(sessionId, runId, threadId, text);
        } catch (Exception e) {
            log.warn("Agent router final reply enqueue failed: session={}, run={}, error={}",
                    sessionId, runId, e.getMessage());
        }
    }
}
