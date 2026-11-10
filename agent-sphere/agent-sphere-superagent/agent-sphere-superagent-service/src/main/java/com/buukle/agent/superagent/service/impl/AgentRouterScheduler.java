package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.TenantScopeHolder;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.UserSpi;
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
import com.buukle.agent.superagent.service.event.AgentDeliveryCreatedEvent;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    /** Metric tag value for deliveries terminally failed because nothing can consume them. */
    private static final String DELIVERY_STATUS_TAG = "status";
    private static final String DELIVERY_STATUS_UNDELIVERABLE = "UNDELIVERABLE";

    private final AgentEventDeliveryMapper deliveryMapper;
    private final AgentEventMapper eventMapper;
    private final AgentSuperAgentMapper superAgentMapper;
    private final AgentThreadMapper threadMapper;
    private final SessionSpi sessionSpi;
    private final ObjectProvider<AgentRouterRunPort> agentRouterRunPortProvider;
    private final ObjectProvider<UserSpi> userSpiProvider;
    private final AgentMetrics metrics;
    private final RedissonClient redissonClient;

    @Qualifier("runtimeAsyncExecutor")
    private final Executor wakeExecutor;

    private final AtomicBoolean waking = new AtomicBoolean(false);
    private final AtomicBoolean wakeAgain = new AtomicBoolean(false);

    @Value("${buukle.agent.super-agent.enabled:false}")
    private boolean superAgentEnabled;
    @Value("${buukle.agent.super-agent.agent-router.enabled:false}")
    private boolean enabled;
    @Value("${buukle.agent.super-agent.agent-router.batch-size:20}")
    private int batchSize;

    /** Event-driven wake: deliveries are consumed milliseconds after commit; polling is a fallback. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeliveryCreated(AgentDeliveryCreatedEvent event) {
        wakeAsync();
    }

    @Scheduled(fixedDelayString = "${buukle.agent.super-agent.agent-router.interval-ms:3000}")
    public void run() {
        if (!superAgentEnabled || !enabled) {
            return;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        AuthContext.clear();
        TenantContext.clear();
        processSessionBatch();
    }

    private void wakeAsync() {
        if (!superAgentEnabled || !enabled) {
            return;
        }
        if (!waking.compareAndSet(false, true)) {
            wakeAgain.set(true);
            metrics.increment(AgentMetrics.AGENT_ROUTER_WAKE_SKIPPED);
            return;
        }
        try {
            wakeExecutor.execute(() -> {
                try {
                    do {
                        wakeAgain.set(false);
                        run();
                    } while (wakeAgain.get());
                } finally {
                    waking.set(false);
                    if (wakeAgain.get()) {
                        wakeAsync();
                    }
                }
            });
        } catch (Exception e) {
            waking.set(false);
            log.warn("Agent router wake rejected: {}", e.getMessage());
        }
    }

    private void processSessionBatch() {
        List<DeliveryContext> contexts = new ArrayList<>();
        for (AgentEventDelivery delivery : deliveryMapper.selectPending(Math.max(1, batchSize))) {
            AgentEvent event = delivery.getEventId() == null
                    ? null : eventMapper.selectById(delivery.getEventId());
            AgentSuperAgent agent = event == null
                    ? null : superAgentMapper.selectById(delivery.getSuperAgentId());
            if (event == null || agent == null) {
                failUndeliverable(delivery, event == null ? "event missing" : "agent missing");
                continue;
            }
            if (!SuperAgentStatus.ACTIVE.equals(agent.getStatus())) {
                failUndeliverable(delivery, "agent " + agent.getStatus());
                continue;
            }
            if (agent.getRouterInstanceId() == null) {
                failUndeliverable(delivery, "agent has no router instance");
                continue;
            }
            if (event.getThreadId() == null) {
                failUndeliverable(delivery, "event has no thread");
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
                StringBuilder input = new StringBuilder(identityBlock(first.agent, first.event));
                input.append("[DELIVERIES]\n");
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

    /**
     * Terminally fails a delivery its target can never consume. A later recovery
     * (archive undone, router instance bound) re-runs the ingest pipeline with a
     * new event, so keeping the row pending forever only makes every fallback
     * poll spend its batch on dead rows — the exact busy loop this method ends.
     */
    private void failUndeliverable(AgentEventDelivery delivery, String cause) {
        String remark = "UNDELIVERABLE: " + cause
                + "; superAgentId=" + delivery.getSuperAgentId()
                + ", eventId=" + delivery.getEventId();
        if (deliveryMapper.failPending(delivery.getId(), remark) > 0) {
            metrics.increment(AgentMetrics.AGENT_ROUTER_DELIVERY,
                    DELIVERY_STATUS_TAG, DELIVERY_STATUS_UNDELIVERABLE);
            log.warn("Agent delivery terminally failed ({}): deliveryId={}", cause, delivery.getId());
        }
    }

    /** One Router session per (thread, agent); different avatars in one group must not share it. */
    private Long ensureRouterSession(AgentSuperAgent agent, Long threadId) {
        if (threadId == null) {
            return null;
        }
        SessionVO existing = sessionSpi.findRouterSession(threadId, agent.getId());
        if (existing != null && existing.getId() != null) {
            return existing.getId();
        }
        AgentThread thread = threadMapper.selectById(threadId);
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
            // Legacy single-agent pointer only; routing always resolves by (thread, agent).
            if (thread != null && thread.getRouterSessionId() == null) {
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
                ? "Conversation" : thread.getTitle();
        String title = (agent.getName() == null ? "" : agent.getName()) + " · " + suffix;
        return title.length() <= MAX_TITLE ? title : title.substring(0, MAX_TITLE);
    }

    /**
     * Tells an avatar who it belongs to and who is speaking, so a mention of
     * someone else's avatar is not answered as if the owner had spoken.
     */
    private String identityBlock(AgentSuperAgent agent, AgentEvent event) {
        JsonNode meta = rawMeta(event);
        Long ownerId = agent.getOwnerUserId();
        List<Long> memberIds = numberList(meta, "memberUserIds");
        Long speakerId = meta != null && meta.hasNonNull("speakerId")
                ? meta.get("speakerId").asLong() : event.getActorId();
        String speakerName = meta != null && meta.hasNonNull("speakerName")
                ? meta.get("speakerName").asText() : null;
        boolean group = meta != null && meta.hasNonNull("conversationType")
                && "GROUP".equalsIgnoreCase(meta.get("conversationType").asText());
        Set<Long> userIds = new LinkedHashSet<>();
        if (ownerId != null) {
            userIds.add(ownerId);
        }
        userIds.addAll(memberIds);
        if (speakerId != null) {
            userIds.add(speakerId);
        }
        UserSpi userSpi = userSpiProvider.getIfAvailable();
        Map<Long, UserIdentityVO> identities = userSpi == null || userIds.isEmpty()
                ? Map.of() : userSpi.identityMap(userIds);
        String ownerName = userLabel(ownerId == null ? null : identities.get(ownerId));
        if (speakerName == null || speakerName.isBlank()) {
            speakerName = userLabel(speakerId == null ? null : identities.get(speakerId));
        }
        String agentName = agent.getName() == null ? "" : agent.getName();
        StringBuilder block = new StringBuilder("[IDENTITY]\nYou are \"").append(agentName).append("\"");
        if (ownerName != null) {
            block.append(", the digital avatar of ").append(ownerName)
                    .append(". You are not an independent account");
        }
        block.append(".\n");
        List<String> memberNames = new ArrayList<>();
        for (Long memberId : memberIds) {
            String name = userLabel(identities.get(memberId));
            if (name != null) {
                memberNames.add(name);
            }
        }
        if (!memberNames.isEmpty()) {
            block.append(group ? "Group members: " : "Conversation members: ")
                    .append(String.join(", ", memberNames)).append(".\n");
        }
        if (speakerName != null) {
            boolean ownerSpeaking = ownerId != null && ownerId.equals(speakerId);
            block.append("This message is from ").append(speakerName);
            if (!ownerSpeaking && ownerName != null) {
                block.append(", not your owner ").append(ownerName);
            }
            block.append(".\n");
        }
        return block.toString();
    }

    private static List<Long> numberList(JsonNode meta, String field) {
        JsonNode node = meta == null ? null : meta.get(field);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(node.size());
        for (JsonNode value : node) {
            if (value.isNumber()) {
                ids.add(value.asLong());
            }
        }
        return ids;
    }

    /** Mirrors the console label rule: provider@subject for SSO users. */
    private static String userLabel(UserIdentityVO identity) {
        if (identity == null) {
            return null;
        }
        if (identity.getSsoProviderCode() != null && identity.getSsoSubject() != null) {
            return identity.getSsoProviderCode() + "@" + identity.getSsoSubject();
        }
        if (identity.getDisplayName() != null && !identity.getDisplayName().isBlank()) {
            return identity.getDisplayName();
        }
        if (identity.getEnglishName() != null && !identity.getEnglishName().isBlank()) {
            return identity.getEnglishName();
        }
        return identity.getUsername();
    }

    private static JsonNode rawMeta(AgentEvent event) {
        try {
            JsonNode root = JsonUtils.getMapper().readTree(event.getPayload());
            if (root == null || !root.has("rawMeta") || !root.get("rawMeta").isTextual()) {
                return null;
            }
            return JsonUtils.getMapper().readTree(root.get("rawMeta").asText());
        } catch (Exception e) {
            return null;
        }
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
