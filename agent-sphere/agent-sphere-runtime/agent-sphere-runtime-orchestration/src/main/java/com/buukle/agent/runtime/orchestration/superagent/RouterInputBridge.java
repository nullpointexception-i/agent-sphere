package com.buukle.agent.runtime.orchestration.superagent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.domain.AgentPendingClarification;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.kernel.port.vo.ClarificationStatus;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.ToolApprovalStatus;
import com.buukle.agent.superagent.spi.SessionInputCommand;
import com.buukle.agent.superagent.spi.SessionInputResolveCommand;
import com.buukle.agent.superagent.spi.SessionInputSpi;
import com.buukle.agent.superagent.spi.SessionInputStatus;
import com.buukle.agent.superagent.spi.SessionInputType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges Router parked inputs (clarification / L2 tool approval) to the IM DM
 * through {@link SessionInputSpi}: pending inputs become card messages, and
 * resolutions cancel or mark the cards answered.
 *
 * <p>Worker/sub-agent clarify and approval events are ignored — only top-level
 * Router sessions bridge to a DM conversation.
 */
@Slf4j
@Component
public class RouterInputBridge {

    private static final int MAX_SESSION_CACHE = 500;
    private static final long SESSION_TTL_SECONDS = 600;
    private static final long NEGATIVE_SESSION_TTL_SECONDS = 30;

    private final SessionSpi sessionSpi;
    private final AgentPendingClarificationMapper clarificationMapper;
    private final ObjectProvider<SessionInputSpi> sessionInputSpiProvider;
    private final AgentMetrics metrics;

    @Value("${buukle.agent.super-agent.router.input.enabled:true}")
    private boolean enabled;

    /** sessionId → cached Router session info; unsure lookups are negatively cached briefly. */
    private final Map<Long, CachedSession> sessions = new ConcurrentHashMap<>();

    public RouterInputBridge(SessionSpi sessionSpi,
                             AgentPendingClarificationMapper clarificationMapper,
                             ObjectProvider<SessionInputSpi> sessionInputSpiProvider,
                             AgentMetrics metrics) {
        this.sessionSpi = sessionSpi;
        this.clarificationMapper = clarificationMapper;
        this.sessionInputSpiProvider = sessionInputSpiProvider;
        this.metrics = metrics;
    }

    @EventListener
    public void onEvent(RuntimeEventVO event) {
        if (!enabled || event == null) {
            return;
        }
        try {
            handle(event);
        } catch (Exception e) {
            log.debug("Router input bridge dropped event: {}", e.getMessage());
        }
    }

    private void handle(RuntimeEventVO event) {
        RuntimeEventDataVO data = event.getData();
        if (data == null || data.getSessionId() == null || data.getRunId() == null
                || data.getSubAgentRunId() != null) {
            return;
        }
        SessionInfo session = sessionInfo(data.getSessionId());
        if (session == null) {
            return;
        }
        if (event.getEventType() instanceof ClarificationStatus clarify) {
            handleClarification(clarify, data, session);
        } else if (event.getEventType() instanceof ToolApprovalStatus approval) {
            handleApproval(approval, data, session);
        } else if (event.getEventType() instanceof RunStatus runStatus && isTerminal(runStatus)) {
            spi().ifPresent(spi -> spi.projectRunTerminal(data.getRunId()));
        }
    }

    private void handleClarification(ClarificationStatus clarify, RuntimeEventDataVO data,
                                     SessionInfo session) {
        if (data.getClarificationId() == null) {
            return;
        }
        SessionInputSpi spi = spi().orElse(null);
        if (spi == null) {
            return;
        }
        switch (clarify) {
            case PENDING -> {
                AgentPendingClarification row = clarificationRow(data.getSessionId(),
                        data.getClarificationId());
                spi.projectPending(new SessionInputCommand(
                        session.agentId(), session.threadId(), data.getSessionId(), data.getRunId(),
                        SessionInputType.CLARIFICATION, data.getClarificationId(),
                        firstNonBlank(rowTitle(row), data.getPrompt()),
                        rowDescription(row),
                        firstNonBlank(rowOptions(row), data.getArgumentsJson()),
                        rowMultiple(row, data.getMultiple()),
                        null));
            }
            case RESPONDED -> spi.projectResolved(resolveCommand(
                    SessionInputType.CLARIFICATION, data.getClarificationId(),
                    SessionInputStatus.ANSWERED));
            case DISMISSED -> spi.projectResolved(resolveCommand(
                    SessionInputType.CLARIFICATION, data.getClarificationId(),
                    SessionInputStatus.CANCELLED));
        }
    }

    private void handleApproval(ToolApprovalStatus approval, RuntimeEventDataVO data,
                                SessionInfo session) {
        if (data.getApprovalId() == null) {
            return;
        }
        SessionInputSpi spi = spi().orElse(null);
        if (spi == null) {
            return;
        }
        switch (approval) {
            case TOOL_APPROVAL_PENDING -> spi.projectPending(new SessionInputCommand(
                    session.agentId(), session.threadId(), data.getSessionId(), data.getRunId(),
                    SessionInputType.APPROVAL, data.getApprovalId(),
                    data.getPrompt(), null, null, null,
                    data.getToolCallsJson()));
            case TOOL_APPROVAL_RESPONDED -> spi.projectResolved(resolveCommand(
                    SessionInputType.APPROVAL, data.getApprovalId(),
                    SessionInputStatus.ANSWERED));
            case TOOL_APPROVAL_DISMISSED -> spi.projectResolved(resolveCommand(
                    SessionInputType.APPROVAL, data.getApprovalId(),
                    SessionInputStatus.CANCELLED));
        }
    }

    private SessionInputResolveCommand resolveCommand(String inputType, String inputId, String status) {
        return new SessionInputResolveCommand(inputType, inputId, status);
    }

    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED;
    }

    private AgentPendingClarification clarificationRow(Long sessionId, String clarificationId) {
        try {
            return clarificationMapper.selectOne(Wrappers.<AgentPendingClarification>lambdaQuery()
                    .eq(AgentPendingClarification::getSessionId, sessionId)
                    .eq(AgentPendingClarification::getClarificationId, clarificationId)
                    .orderByDesc(AgentPendingClarification::getId)
                    .last("LIMIT 1"));
        } catch (Exception e) {
            log.debug("Clarification row lookup failed: clarificationId={}", clarificationId);
            return null;
        }
    }

    private SessionInfo sessionInfo(Long sessionId) {
        long now = System.currentTimeMillis();
        CachedSession cached = sessions.get(sessionId);
        if (cached != null && cached.expiresAt > now) {
            return cached.info;
        }
        SessionInfo info = null;
        try {
            SessionVO session = sessionSpi.getSession(sessionId);
            if (session != null && SessionType.ROUTER.equals(session.getSessionType())
                    && session.getSuperAgentId() != null && session.getThreadId() != null) {
                info = new SessionInfo(session.getThreadId(), session.getSuperAgentId());
            }
        } catch (Exception e) {
            log.debug("Router session lookup failed: session={}, error={}", sessionId, e.getMessage());
        }
        long ttl = info != null ? SESSION_TTL_SECONDS * 1000 : NEGATIVE_SESSION_TTL_SECONDS * 1000;
        sessions.put(sessionId, new CachedSession(info, now + ttl));
        if (sessions.size() > MAX_SESSION_CACHE) {
            sessions.clear();
        }
        return info;
    }

    private java.util.Optional<SessionInputSpi> spi() {
        return java.util.Optional.ofNullable(sessionInputSpiProvider.getIfAvailable());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static String rowTitle(AgentPendingClarification row) {
        return row == null ? null : row.getTitle();
    }

    private static String rowDescription(AgentPendingClarification row) {
        return row == null ? null : row.getDescription();
    }

    private static String rowOptions(AgentPendingClarification row) {
        return row == null ? null : row.getOptions();
    }

    private static Boolean rowMultiple(AgentPendingClarification row, Boolean fallback) {
        if (row != null && row.getMultiple() != null) {
            return row.getMultiple();
        }
        return fallback;
    }

    private record SessionInfo(Long threadId, Long agentId) {
    }

    private record CachedSession(SessionInfo info, long expiresAt) {
    }
}
