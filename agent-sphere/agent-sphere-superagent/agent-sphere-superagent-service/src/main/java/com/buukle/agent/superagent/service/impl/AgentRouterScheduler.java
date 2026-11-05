package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.dtvo.enums.AgentEventDeliveryStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentRouterRunStatus;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.spi.AgentRouterRunCommand;
import com.buukle.agent.superagent.spi.AgentRouterRunPort;
import com.buukle.agent.superagent.spi.AgentRouterRunResult;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Agent Router consumer.
 *
 * <p>Each IM thread gets its own Router session. The legacy JSON decision mode and the
 * per-agent guardian session have been retired.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRouterScheduler {

    private static final int MAX_EVENT_SUMMARY = 200;
    private static final int MAX_TITLE = 200;
    private static final int RETRY_DELAY_SECONDS = 30;
    private static final long GROUP_LOCK_LEASE_SECONDS = 60;
    private static final String GROUP_LOCK_PREFIX = "superagent:agent-router:";

    private final AgentEventDeliveryMapper deliveryMapper;
    private final AgentEventMapper eventMapper;
    private final AgentSuperAgentMapper superAgentMapper;
    private final AgentThreadMapper threadMapper;
    private final SessionSpi sessionSpi;
    private final ObjectProvider<AgentRouterRunPort> agentRouterRunPortProvider;
    private final AgentMetrics metrics;
    private final RedissonClient redissonClient;

    @Value("${buukle.agent.super-agent.enabled:false}")
    private boolean superAgentEnabled;
    @Value("${buukle.agent.super-agent.agent-router.enabled:false}")
    private boolean enabled;
    @Value("${buukle.agent.super-agent.agent-router.batch-size:20}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${buukle.agent.super-agent.agent-router.interval-ms:3000}")
    public void run() {
        if (!superAgentEnabled || !enabled) {
            return;
        }
        TenantUtil.stop();
        AuthContext.clear();
        TenantContext.clear();
        processSessionBatch();
    }

    private void processSessionBatch() {
        List<DeliveryContext> contexts = new ArrayList<>();
        for (AgentEventDelivery delivery : deliveryMapper.selectPending(Math.max(1, batchSize))) {
            AgentEvent event = eventMapper.selectById(delivery.getEventId());
            AgentSuperAgent agent = superAgentMapper.selectById(delivery.getSuperAgentId());
            if (event == null || agent == null || !SuperAgentStatus.ACTIVE.equals(agent.getStatus())
                    || agent.getRouterInstanceId() == null || event.getThreadId() == null) {
                continue;
            }
            contexts.add(new DeliveryContext(delivery, event, agent));
        }
        Map<String, List<DeliveryContext>> groups = new java.util.LinkedHashMap<>();
        for (DeliveryContext context : contexts) {
            String key = context.agent.getId() + ":" + context.event.getThreadId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(context);
        }
        AgentRouterRunPort port = agentRouterRunPortProvider.getIfAvailable();
        if (port == null) {
            return;
        }
        for (List<DeliveryContext> group : groups.values()) {
            DeliveryContext head = group.get(0);
            RLock lock = redissonClient.getLock(
                    GROUP_LOCK_PREFIX + head.agent.getId() + ":" + head.event.getThreadId());
            boolean locked = false;
            try {
                locked = lock.tryLock(0, GROUP_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
                if (!locked) {
                    metrics.increment(AgentMetrics.AGENT_ROUTER_GROUP_LOCK_SKIPPED);
                    continue;
                }
                List<DeliveryContext> claimed = new ArrayList<>();
                for (DeliveryContext context : group) {
                    if (deliveryMapper.claim(context.delivery.getId()) > 0) {
                        context.delivery.setStatus(AgentEventDeliveryStatus.CLAIMED);
                        claimed.add(context);
                    }
                }
                if (claimed.isEmpty()) {
                    continue;
                }
                DeliveryContext first = claimed.get(0);
                TenantContext.set(first.agent.getTenantId());
                Long routerSessionId = ensureRouterSession(first.agent, first.event.getThreadId());
                if (routerSessionId == null) {
                    for (DeliveryContext context : claimed) {
                        deliveryMapper.release(context.delivery.getId(),
                                LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS));
                    }
                    continue;
                }
                StringBuilder input = new StringBuilder("[DELIVERIES]\n");
                for (DeliveryContext context : claimed) {
                    input.append("deliveryId=").append(context.delivery.getId())
                            .append(", eventId=").append(context.event.getId())
                            .append(", decision=").append(context.delivery.getDecision())
                            .append(", reason=").append(context.delivery.getReason())
                            .append(", text=").append(summary(eventText(context.event)))
                            .append('\n');
                }
                input.append("[INSTRUCTION] Use recall_event for full text when needed. ")
                        .append("Reply once or dispatch one worker for the whole batch.");
                AgentRouterRunResult result;
                try {
                    result = port.start(new AgentRouterRunCommand(
                            routerSessionId, first.agent.getId(),
                            first.event.getThreadId(), input.toString(), "WRITE"));
                } catch (Exception e) {
                    for (DeliveryContext context : claimed) {
                        deliveryMapper.release(context.delivery.getId(),
                                LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS));
                    }
                    metrics.increment(AgentMetrics.AGENT_ROUTER_START_FAILED);
                    log.warn("Agent router start failed: agent={}, thread={}, error={}",
                            first.agent.getId(), first.event.getThreadId(), e.getMessage());
                    continue;
                }
                if (result.accepted()) {
                    for (DeliveryContext context : claimed) {
                        context.delivery.setStatus(result.runId() == null
                                ? AgentEventDeliveryStatus.PROCESSED
                                : AgentEventDeliveryStatus.RUNNING);
                        context.delivery.setRouterRunId(result.runId());
                        context.delivery.setDeliveredAt(LocalDateTime.now());
                        deliveryMapper.updateById(context.delivery);
                    }
                    metrics.increment(AgentMetrics.AGENT_ROUTER_DELIVERY, "status",
                            AgentRouterRunStatus.ACCEPTED);
                } else {
                    for (DeliveryContext context : claimed) {
                        deliveryMapper.release(context.delivery.getId(),
                                LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS));
                    }
                    metrics.increment(AgentMetrics.AGENT_ROUTER_DELIVERY, "status",
                            AgentRouterRunStatus.REJECTED);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
                TenantContext.clear();
            }
        }
    }

    private record DeliveryContext(AgentEventDelivery delivery, AgentEvent event, AgentSuperAgent agent) {
    }

    /** One Router session per thread; no guardian fallback. */
    private Long ensureRouterSession(AgentSuperAgent agent, Long threadId) {
        if (threadId == null) {
            return null;
        }
        AgentThread thread = threadMapper.selectById(threadId);
        if (thread != null && thread.getRouterSessionId() != null) {
            return thread.getRouterSessionId();
        }
        if (agent.getRouterInstanceId() == null) {
            return null;
        }
        SessionSurface previous = SessionSurfaceHolder.get();
        SessionSurfaceHolder.set(SessionSurface.INTERNAL);
        try {
            CreateSessionDTO dto = new CreateSessionDTO();
            dto.setAgentInstanceId(agent.getRouterInstanceId());
            dto.setTitle(routerSessionTitle(agent, thread));
            dto.setSessionType(SessionType.ROUTER);
            dto.setSuperAgentId(agent.getId());
            dto.setThreadId(threadId);
            dto.setCreatedBy(thread == null ? null : thread.getCreatedBy());
            SessionVO session = sessionSpi.createSession(dto);
            if (session == null || session.getId() == null) {
                return null;
            }
            if (thread != null) {
                thread.setRouterSessionId(session.getId());
                threadMapper.updateById(thread);
            }
            return session.getId();
        } catch (Exception e) {
            log.warn("Router session create failed: agent={}, thread={}, error={}",
                    agent.getId(), threadId, e.getMessage());
            return null;
        } finally {
            if (previous == null) {
                SessionSurfaceHolder.clear();
            } else {
                SessionSurfaceHolder.set(previous);
            }
        }
    }

    private static String routerSessionTitle(AgentSuperAgent agent, AgentThread thread) {
        String suffix = thread == null || thread.getTitle() == null || thread.getTitle().isBlank()
                ? "对话" : thread.getTitle();
        String title = (agent.getName() == null ? "" : agent.getName()) + " · " + suffix;
        return title.length() <= MAX_TITLE ? title : title.substring(0, MAX_TITLE);
    }

    private static String eventText(AgentEvent event) {
        try {
            JsonNode root = JsonUtils.getMapper().readTree(event.getPayload());
            JsonNode text = root == null ? null : root.get("text");
            return text == null ? "" : text.asText();
        } catch (Exception e) {
            return "";
        }
    }

    private static String summary(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_EVENT_SUMMARY ? text : text.substring(0, MAX_EVENT_SUMMARY);
    }
}
