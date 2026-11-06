package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.channel.spi.AgentStreamEvent;
import com.buukle.agent.channel.spi.AgentStreamPhase;
import com.buukle.agent.channel.spi.AgentStreamPort;
import com.buukle.agent.channel.spi.AgentStreamStatus;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.port.vo.FlowEventType;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.ToolCallStatus;
import com.buukle.agent.superagent.spi.AgentRouterReplyPort;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges Agent Router streaming fragments into the IM channel.
 *
 * <p>Display-only pipeline. Event threads only buffer fragments and enqueue lifecycle
 * signals; a dedicated flush thread drains each run in order, coalesces deltas and fans
 * them out through {@link AgentStreamPort}. Fragments are never persisted — the
 * authoritative reply still travels the normal outbound delivery path.
 *
 * <p>When a run finishes with plain text (instead of a {@code send_message} tool call),
 * the reply text accumulated after the last tool call is handed to
 * {@link AgentRouterReplyPort}, which appends the authoritative outbound message.
 */
@Slf4j
@Component
public class AgentRouterStreamBridge {

    private static final int MAX_SESSION_CACHE = 500;
    private static final int MAX_RUN_STATES = 500;
    private static final long NEGATIVE_SESSION_TTL_SECONDS = 30;
    private static final long FLUSH_INITIAL_DELAY_MS = 50;

    private final SessionSpi sessionSpi;
    private final ObjectProvider<AgentStreamPort> streamPortProvider;
    private final ObjectProvider<AgentRouterReplyPort> replyPortProvider;
    private final AgentMetrics metrics;

    @Value("${buukle.agent.super-agent.router.stream.enabled:true}")
    private boolean streamEnabled;
    @Value("${buukle.agent.super-agent.router.stream.thinking-enabled:true}")
    private boolean thinkingEnabled;
    @Value("${buukle.agent.super-agent.router.stream.coalesce-ms:50}")
    private long coalesceMs;
    @Value("${buukle.agent.super-agent.router.stream.max-chunk-chars:256}")
    private int maxChunkChars;
    @Value("${buukle.agent.super-agent.router.stream.session-cache-ttl-seconds:600}")
    private long sessionCacheTtlSeconds;
    @Value("${buukle.agent.super-agent.router.stream.buffer-ttl-seconds:600}")
    private long bufferTtlSeconds;

    /** sessionId → cached Router session info; unsure lookups are negatively cached briefly. */
    private final Map<Long, CachedSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, RunState> runs = new ConcurrentHashMap<>();
    private final Map<RunPhaseKey, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "agent-router-stream-flusher");
        thread.setDaemon(true);
        return thread;
    });

    public AgentRouterStreamBridge(SessionSpi sessionSpi,
                                   ObjectProvider<AgentStreamPort> streamPortProvider,
                                   ObjectProvider<AgentRouterReplyPort> replyPortProvider,
                                   AgentMetrics metrics) {
        this.sessionSpi = sessionSpi;
        this.streamPortProvider = streamPortProvider;
        this.replyPortProvider = replyPortProvider;
        this.metrics = metrics;
        this.flusher.scheduleWithFixedDelay(this::flushTick, FLUSH_INITIAL_DELAY_MS,
                Math.max(10, coalesceMs), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        flusher.shutdownNow();
    }

    @EventListener
    public void onEvent(RuntimeEventVO event) {
        if (event == null) {
            return;
        }
        try {
            handle(event);
        } catch (Exception e) {
            log.debug("Agent stream bridge dropped event: {}", e.getMessage());
        }
    }

    private void handle(RuntimeEventVO event) {
        RuntimeEventDataVO data = event.getData();
        if (data == null || data.getSessionId() == null || data.getRunId() == null) {
            return;
        }
        // Sub-Agent and compaction streams belong to other timelines, never the Router reply.
        if (data.getSubAgentRunId() != null) {
            return;
        }
        SessionInfo session = sessionInfo(data.getSessionId());
        if (session == null) {
            return;
        }
        RunState state = runs.computeIfAbsent(data.getRunId(), runId -> new RunState(runId, session));

        if (event.getEventType() instanceof FlowEventType flow) {
            classifyFlow(state, data, flow);
        } else if (event.getEventType() instanceof ToolCallStatus status) {
            classifyToolCall(state, data, status);
        } else if (event.getEventType() instanceof RunStatus runStatus) {
            classifyRunStatus(state, data, runStatus);
        }
    }

    private void classifyFlow(RunState state, RuntimeEventDataVO data, FlowEventType flow) {
        if (flow == FlowEventType.REASONING_TOKEN) {
            if (thinkingUnavailable(data)) {
                return;
            }
            state.append(AgentStreamPhase.THINKING, data.getResponse());
            return;
        }
        if (flow == FlowEventType.CONTENT_TOKEN) {
            if (RuntimeEventTypeConstant.REASONING_SUB_TYPE_COMPACTION_OUTPUT
                    .equals(data.getReasoningSubType())) {
                return;
            }
            state.append(AgentStreamPhase.REPLY, data.getResponse());
        }
    }

    private boolean thinkingUnavailable(RuntimeEventDataVO data) {
        return !thinkingEnabled
                || !RuntimeEventTypeConstant.REASONING_TYPE_LLM.equals(data.getReasoningType());
    }

    private void classifyToolCall(RunState state, RuntimeEventDataVO data, ToolCallStatus status) {
        if (!streamEnabled) {
            return;
        }
        String toolStatus = switch (status) {
            case PENDING, RUNNING -> AgentStreamStatus.TOOL_PENDING;
            case SUCCEEDED -> AgentStreamStatus.TOOL_SUCCEEDED;
            case FAILED -> AgentStreamStatus.TOOL_FAILED;
        };
        state.enqueueSignal(new Signal(toolStatus, data.getToolName(), data.getDisplayNameCn(),
                null, null));
        if (status == ToolCallStatus.PENDING) {
            // Entering a tool turn: content streamed afterwards is the final answer text.
            state.clearReply();
        }
    }

    private void classifyRunStatus(RunState state, RuntimeEventDataVO data, RunStatus status) {
        switch (status) {
            case PENDING, RUNNING -> {
                if (!streamEnabled || state.started) {
                    return;
                }
                state.started = true;
                state.enqueueSignal(new Signal(AgentStreamStatus.STARTED, null, null, null, null));
            }
            case COMPLETED, FAILED, CANCELLED -> {
                String terminal = switch (status) {
                    case COMPLETED -> AgentStreamStatus.COMPLETED;
                    case FAILED -> AgentStreamStatus.FAILED;
                    default -> AgentStreamStatus.CANCELLED;
                };
                state.finished = true;
                state.enqueueSignal(new Signal(terminal, null, null,
                        data.getErrorMessage(), data.getAssistantReply()));
            }
            default -> {
                // WAITING / AWAITING_USER: non-terminal park states; the run continues later.
            }
        }
    }

    /** Flusher thread only: drains run state serially so fragments and signals keep order. */
    void flushTick() {
        clearRuntimeContext();
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<Long, RunState> entry : runs.entrySet()) {
                if (entry.getValue().drain(now)) {
                    runs.remove(entry.getKey());
                }
            }
            if (runs.size() > MAX_RUN_STATES) {
                runs.values().removeIf(state -> now - state.lastActivity > bufferTtlSeconds * 1000L);
            }
        } catch (Exception e) {
            log.debug("Agent stream flush tick failed: {}", e.getMessage());
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
                    && session.getSuperAgentId() != null) {
                info = new SessionInfo(session.getId(), session.getThreadId(), session.getSuperAgentId());
            }
        } catch (Exception e) {
            log.debug("Router session lookup failed: session={}, error={}", sessionId, e.getMessage());
        }
        long ttl = info != null ? sessionCacheTtlSeconds * 1000L
                : NEGATIVE_SESSION_TTL_SECONDS * 1000L;
        CachedSession next = new CachedSession(info, now + ttl);
        if (sessions.size() >= MAX_SESSION_CACHE) {
            sessions.clear();
        }
        sessions.put(sessionId, next);
        return info;
    }

    private long nextSeq(Long runId, String phase) {
        RunPhaseKey key = new RunPhaseKey(runId, phase == null ? "" : phase);
        return counters.computeIfAbsent(key, k -> new AtomicLong()).getAndIncrement();
    }

    private static void clearRuntimeContext() {
        TenantUtil.stop();
        AuthContext.clear();
        TenantContext.clear();
    }

    private record SessionInfo(Long sessionId, Long threadId, Long agentId) {
    }

    private record CachedSession(SessionInfo info, long expiresAt) {
    }

    private record RunPhaseKey(Long runId, String phase) {
    }

    private record Signal(String streamStatus, String toolName, String displayName,
                          String errorMessage, String assistantReply) {
    }

    /** Mutable run state. Appends come from event threads; drain comes from the flusher only. */
    private final class RunState {
        private final long runId;
        private final SessionInfo session;
        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder reply = new StringBuilder();
        /** Reply text accumulated after the last tool call; feeds the auto final reply. */
        private final StringBuilder finalReply = new StringBuilder();
        private final ConcurrentLinkedQueue<Signal> signals = new ConcurrentLinkedQueue<>();
        private volatile boolean started;
        private volatile boolean finished;
        private volatile long lastActivity = System.currentTimeMillis();

        private RunState(long runId, SessionInfo session) {
            this.runId = runId;
            this.session = session;
        }

        void append(String phase, String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            StringBuilder target = AgentStreamPhase.THINKING.equals(phase) ? thinking : reply;
            synchronized (target) {
                target.append(delta);
            }
            if (AgentStreamPhase.REPLY.equals(phase)) {
                synchronized (finalReply) {
                    finalReply.append(delta);
                }
            }
            lastActivity = System.currentTimeMillis();
        }

        void clearReply() {
            synchronized (finalReply) {
                finalReply.setLength(0);
            }
            lastActivity = System.currentTimeMillis();
        }

        void enqueueSignal(Signal signal) {
            signals.add(signal);
            lastActivity = System.currentTimeMillis();
        }

        /** @return true when the run is terminal and its state can be dropped. */
        boolean drain(long now) {
            Signal signal;
            while ((signal = signals.poll()) != null) {
                flushBuffers(false, now);
                if (signal.streamStatus() != null
                        && AgentStreamStatus.STARTED.equals(signal.streamStatus())) {
                    publishStatus(signal);
                    continue;
                }
                if (isTerminal(signal)) {
                    finish(signal);
                    return true;
                }
                if (streamEnabled) {
                    publishStatus(signal);
                }
            }
            flushBuffers(true, now);
            return false;
        }

        private boolean isTerminal(Signal signal) {
            return AgentStreamStatus.COMPLETED.equals(signal.streamStatus())
                    || AgentStreamStatus.FAILED.equals(signal.streamStatus())
                    || AgentStreamStatus.CANCELLED.equals(signal.streamStatus());
        }

        /** @param dueOnly when false the buffers are always flushed (before a lifecycle signal). */
        private void flushBuffers(boolean dueOnly, long now) {
            if (!streamEnabled) {
                return;
            }
            AgentStreamPort port = streamPortProvider.getIfAvailable();
            if (port == null) {
                return;
            }
            flushBuffer(port, thinking, AgentStreamPhase.THINKING, dueOnly);
            flushBuffer(port, reply, AgentStreamPhase.REPLY, dueOnly);
        }

        private void flushBuffer(AgentStreamPort port, StringBuilder buffer, String phase,
                                 boolean dueOnly) {
            synchronized (buffer) {
                if (buffer.length() == 0) {
                    return;
                }
                if (dueOnly && buffer.length() < maxChunkChars
                        && System.currentTimeMillis() - lastActivity < coalesceMs) {
                    return;
                }
                String delta = buffer.toString();
                buffer.setLength(0);
                port.publish(new AgentStreamEvent(
                        session.threadId(), session.sessionId(), runId, session.agentId(),
                        phase, delta, null, null, null, null,
                        nextSeq(runId, phase), System.currentTimeMillis()));
            }
        }

        private void publishStatus(Signal signal) {
            AgentStreamPort port = streamPortProvider.getIfAvailable();
            if (port == null) {
                return;
            }
            port.publish(new AgentStreamEvent(
                    session.threadId(), session.sessionId(), runId, session.agentId(),
                    AgentStreamPhase.STATUS, null, signal.streamStatus(), signal.toolName(),
                    signal.displayName(), signal.errorMessage(),
                    nextSeq(runId, AgentStreamPhase.STATUS), System.currentTimeMillis()));
        }

        private void finish(Signal signal) {
            if (streamEnabled) {
                AgentStreamPort port = streamPortProvider.getIfAvailable();
                if (port != null) {
                    flushBuffer(port, thinking, AgentStreamPhase.THINKING, false);
                    flushBuffer(port, reply, AgentStreamPhase.REPLY, false);
                    publishStatus(signal);
                }
            }
            if (AgentStreamStatus.COMPLETED.equals(signal.streamStatus())) {
                deliverFinalReply(signal);
            }
            metrics.increment(AgentMetrics.AGENT_ROUTER_DELIVERY, "streamStatus",
                    AgentStreamStatus.COMPLETED.equals(signal.streamStatus())
                            ? "completed" : "lifecycle");
            synchronized (thinking) {
                thinking.setLength(0);
            }
            synchronized (reply) {
                reply.setLength(0);
            }
            synchronized (finalReply) {
                finalReply.setLength(0);
            }
            counters.keySet().removeIf(key -> key.runId() == runId);
        }

        private void deliverFinalReply(Signal signal) {
            AgentRouterReplyPort port = replyPortProvider.getIfAvailable();
            if (port == null) {
                return;
            }
            if (session.threadId() == null) {
                return;
            }
            String text;
            synchronized (finalReply) {
                text = finalReply.toString();
            }
            text = text == null ? "" : text.strip();
            if (text.isEmpty()) {
                text = signal.assistantReply() == null ? "" : signal.assistantReply().strip();
            }
            if (text.isEmpty()) {
                return;
            }
            try {
                port.sendFinalReply(session.sessionId(), runId, session.threadId(), text);
            } catch (Exception e) {
                log.debug("Agent final reply enqueue failed: run={}, error={}", runId, e.getMessage());
            }
        }
    }
}
