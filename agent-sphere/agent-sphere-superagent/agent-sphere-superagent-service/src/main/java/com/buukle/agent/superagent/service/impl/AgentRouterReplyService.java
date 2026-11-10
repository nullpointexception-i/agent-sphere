package com.buukle.agent.superagent.service.impl;


import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.superagent.domain.AgentRouterFork;
import com.buukle.agent.superagent.repository.AgentRouterForkMapper;
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
    private final SessionSpi sessionSpi;
    private final AgentRouterForkMapper forkMapper;

    @Value("${buukle.agent.super-agent.router.reply.auto-final-enabled:true}")
    private boolean autoFinalEnabled;

    public AgentRouterReplyService(AgentRouterToolService toolService,
                                   SessionSpi sessionSpi,
                                   AgentRouterForkMapper forkMapper) {
        this.toolService = toolService;
        this.sessionSpi = sessionSpi;
        this.forkMapper = forkMapper;
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
            if (captureForkReply(sessionId, runId, text)) {
                return;
            }
            toolService.sendFinalReply(sessionId, runId, threadId, text);
        } catch (Exception e) {
            log.warn("Agent router final reply enqueue failed: session={}, run={}, error={}",
                    sessionId, runId, e.getMessage());
        }
    }

    /**
     * Fork replies must not post to the group directly: they wait for strict sequence merge.
     */
    private boolean captureForkReply(Long sessionId, Long runId, String text) {
        if (sessionId == null) {
            return false;
        }
        SessionVO session = sessionSpi.getSession(sessionId);
        if (session == null || session.getParentSessionId() == null) {
            return false;
        }
        AgentRouterFork fork = forkMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<AgentRouterFork>lambdaQuery()
                        .eq(AgentRouterFork::getChildSessionId, sessionId)
                        .last("LIMIT 1"));
        if (fork == null) {
            return false;
        }
        fork.setChildRunId(runId);
        fork.setReplyText(text);
        fork.setState(AgentRouterFork.STATE_COMPLETED);
        forkMapper.updateById(fork);
        return true;
    }
}
