package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.TenantScopeHolder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

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
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentRouterFork;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.dtvo.enums.AgentEventDeliveryStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentRouterRunStatus;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentRouterForkMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.repository.PendingDeliveryGroup;
import com.buukle.agent.superagent.spi.AgentRouterRunCommand;
import com.buukle.agent.superagent.spi.AgentRouterRunPort;
import com.buukle.agent.superagent.spi.AgentRouterRunResult;
import com.buukle.agent.superagent.service.event.AgentDeliveryReadyEvent;
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

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    private static final int MAX_FORK_SUMMARY = 4000;
    private static final String FORKED_STATUS_TAG = "FORKED";
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
    private final RunSpi runSpi;
    private final AgentRouterForkMapper forkMapper;
    private final ObjectProvider<AgentRouterRunPort> agentRouterRunPortProvider;
    private final ObjectProvider<UserSpi> userSpiProvider;
    private final AgentMetrics metrics;
    private final RedissonClient redissonClient;

    @Qualifier("runtimeAsyncExecutor")
    private final Executor wakeExecutor;

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final ExecutorService dispatchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ScheduledFuture<?>> kickTimers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService kickTimer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "agent-router-kick");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${buukle.agent.super-agent.enabled:false}")
    private boolean superAgentEnabled;
    @Value("${buukle.agent.super-agent.agent-router.enabled:false}")
    private boolean enabled;
    @Value("${buukle.agent.super-agent.agent-router.batch-per-group:20}")
    private int batchPerGroup;
    @Value("${buukle.agent.super-agent.agent-router.groups-per-scan:50}")
    private int groupsPerScan;
    @Value("${buukle.agent.super-agent.agent-router.kick-debounce-ms:200}")
    private long kickDebounceMs;
    @Value("${buukle.agent.super-agent.agent-router.claim-timeout-seconds:120}")
    private long claimTimeoutSeconds;
    @Value("${buukle.agent.super-agent.agent-router.fork.enabled:false}")
    private boolean forkEnabled;
    @Value("${buukle.agent.super-agent.agent-router.fork.max-per-agent:2}")
    private int forkMaxPerAgent;

    /**
     * Hot path: a producer (mention ingest / group router) just committed deliveries for one
     * (agent, thread). Coalesce bursts briefly, then dispatch that group directly instead of
     * scanning the whole table.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeliveryReady(AgentDeliveryReadyEvent event) {
        if (!superAgentEnabled || !enabled || event == null
                || event.agentId() == null || event.threadId() == null) {
            return;
        }
        String key = event.agentId() + ":" + event.threadId();
        kickTimers.compute(key, (id, existing) -> {
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            return kickTimer.schedule(() -> {
                kickTimers.remove(id);
                dispatchExecutor.execute(() ->
                        dispatchGroup(event.agentId(), event.threadId(), null));
            }, Math.max(0, kickDebounceMs), TimeUnit.MILLISECONDS);
        });
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

    private void processSessionBatch() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            deliveryMapper.reclaimStaleClaims(
                    LocalDateTime.now().minusSeconds(claimTimeoutSeconds));
            for (PendingDeliveryGroup group : deliveryMapper.selectPendingGroups(
                    Math.max(1, groupsPerScan))) {
                dispatchExecutor.execute(() ->
                        dispatchGroup(group.getAgentId(), group.getThreadId(), null));
            }
        } finally {
            scanning.set(false);
        }
    }

    /**
     * Dispatches one (agent, thread) group. The realtime kick and the compensation scan both call
     * this method; the group lock keeps the same conversation serial without blocking other groups.
     */
    private void dispatchGroup(Long agentId, Long threadId, List<Long> preferredIds) {
        AgentRouterRunPort port = agentRouterRunPortProvider.getIfAvailable();
        if (port == null) {
            return;
        }
        RLock lock = redissonClient.getLock(GROUP_LOCK_PREFIX + agentId + ":" + threadId);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, GROUP_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                metrics.increment(AgentMetrics.AGENT_ROUTER_GROUP_LOCK_SKIPPED);
                return;
            }
            List<AgentEventDelivery> deliveries = preferredIds == null || preferredIds.isEmpty()
                    ? deliveryMapper.selectPendingGroup(agentId, threadId, Math.max(1, batchPerGroup))
                    : deliveryMapper.selectBatchIds(preferredIds).stream()
                            .filter(row -> agentId.equals(row.getSuperAgentId()))
                            .filter(row -> AgentEventDeliveryStatus.PENDING.equals(row.getStatus()))
                            .toList();
            if (deliveries.isEmpty()) {
                return;
            }
            Set<Long> eventIds = deliveries.stream()
                    .map(AgentEventDelivery::getEventId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            Map<Long, AgentEvent> events = eventIds.isEmpty() ? Map.of()
                    : eventMapper.selectBatchIds(eventIds).stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    AgentEvent::getId, event -> event, (left, right) -> left));
            Map<Long, AgentSuperAgent> agents = superAgentMapper.selectBatchIds(Set.of(agentId))
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(
                            AgentSuperAgent::getId, agent -> agent, (left, right) -> left));

            List<DeliveryContext> contexts = new ArrayList<>();
            for (AgentEventDelivery delivery : deliveries) {
                AgentEvent event = events.get(delivery.getEventId());
                AgentSuperAgent agent = agents.get(agentId);
                if (event == null || agent == null) {
                    failUndeliverable(delivery, event == null ? "event missing" : "agent missing");
                    continue;
                }
                if (!threadId.equals(event.getThreadId())) {
                    failUndeliverable(delivery, "thread mismatch");
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
                contexts.add(new DeliveryContext(delivery, event, agent));
            }
            if (contexts.isEmpty()) {
                return;
            }

            List<DeliveryContext> claimed = new ArrayList<>();
            for (DeliveryContext context : contexts) {
                if (deliveryMapper.claim(context.delivery().getId()) > 0) {
                    context.delivery().setStatus(AgentEventDeliveryStatus.CLAIMED);
                    claimed.add(context);
                }
            }
            if (claimed.isEmpty()) {
                return;
            }
            DeliveryContext first = claimed.get(0);
            TenantContext.set(first.agent().getTenantId());
            Long routerSessionId = ensureRouterSession(first.agent(), threadId);
            if (routerSessionId == null) {
                releaseAll(claimed);
                return;
            }
            StringBuilder input = new StringBuilder(identityBlock(first.agent(), first.event()));
            input.append("[DELIVERIES]\n");
            for (DeliveryContext context : claimed) {
                input.append("deliveryId=").append(context.delivery().getId())
                        .append(", eventId=").append(context.event().getId())
                        .append(", decision=").append(context.delivery().getDecision())
                        .append(", reason=").append(context.delivery().getReason())
                        .append(", text=").append(summary(eventText(context.event())))
                        .append('\n');
            }
            input.append("[INSTRUCTION] Use recall_event for full text when needed. ")
                    .append("Reply once or dispatch one worker for the whole batch.");
            if (forkEnabled && runSpi.findActiveRun(routerSessionId) != null
                    && activeForkCount(routerSessionId) < Math.max(1, forkMaxPerAgent)) {
                startFork(routerSessionId, first, threadId, input.toString(), claimed, port);
                return;
            }
            AgentRouterRunResult result;
            try {
                result = port.start(new AgentRouterRunCommand(
                        routerSessionId, agentId, threadId, input.toString(), "WRITE"));
            } catch (Exception e) {
                releaseAll(claimed);
                metrics.increment(AgentMetrics.AGENT_ROUTER_START_FAILED);
                log.warn("Agent router start failed: agent={}, thread={}, error={}",
                        agentId, threadId, e.getMessage());
                return;
            }
            if (result.accepted()) {
                for (DeliveryContext context : claimed) {
                    context.delivery().setStatus(result.runId() == null
                            ? AgentEventDeliveryStatus.PROCESSED
                            : AgentEventDeliveryStatus.RUNNING);
                    context.delivery().setRouterRunId(result.runId());
                    context.delivery().setDeliveredAt(LocalDateTime.now());
                    deliveryMapper.updateById(context.delivery());
                }
                metrics.increment(AgentMetrics.AGENT_ROUTER_DELIVERY, AgentMetrics.TAG_STATUS,
                        AgentRouterRunStatus.ACCEPTED);
            } else {
                releaseAll(claimed);
                metrics.increment(AgentMetrics.AGENT_ROUTER_DELIVERY, AgentMetrics.TAG_STATUS,
                        AgentRouterRunStatus.REJECTED);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            TenantContext.clear();
        }
    }

    private long activeForkCount(Long parentSessionId) {
        return forkMapper.selectCount(Wrappers.<AgentRouterFork>lambdaQuery()
                .eq(AgentRouterFork::getParentSessionId, parentSessionId)
                .in(AgentRouterFork::getState, AgentRouterFork.STATE_RUNNING,
                        AgentRouterFork.STATE_COMPLETED, AgentRouterFork.STATE_WAITING_SEQ,
                        AgentRouterFork.STATE_MERGING));
    }

    /**
     * Runs the batch in a soft-forked session while the parent is busy. The reply is captured and
     * merged later in event-sequence order; it never posts to the group directly.
     */
    private void startFork(Long parentSessionId, DeliveryContext first, Long threadId,
                           String input, List<DeliveryContext> claimed,
                           AgentRouterRunPort port) {
        long seqFrom = claimed.stream()
                .mapToLong(context -> context.event().getSeq() == null ? 0L : context.event().getSeq())
                .min().orElse(0L);
        long seqTo = claimed.stream()
                .mapToLong(context -> context.event().getSeq() == null ? 0L : context.event().getSeq())
                .max().orElse(seqFrom);
        SessionVO parent = sessionSpi.getSession(parentSessionId);
        SessionVO child = createForkSession(first.agent(), threadId, parentSessionId, parent,
                seqFrom, seqTo);
        if (child == null || child.getId() == null) {
            releaseAll(claimed);
            return;
        }
        AgentRouterFork fork = new AgentRouterFork();
        fork.setParentSessionId(parentSessionId);
        fork.setChildSessionId(child.getId());
        fork.setThreadId(threadId);
        fork.setSuperAgentId(first.agent().getId());
        fork.setSeqFrom(seqFrom);
        fork.setSeqTo(seqTo);
        fork.setState(AgentRouterFork.STATE_RUNNING);
        fork.setStatus(AgentRouterFork.STATUS_ACTIVE);
        fork.setCreatedBy(parent == null ? null : parent.getCreatedBy());
        fork.setTenantId(first.agent().getTenantId());
        forkMapper.insert(fork);
        metrics.increment(AgentMetrics.AGENT_ROUTER_FORK_CREATED);

        String forkInput = "[FORK] parentSession=" + parentSessionId
                + ", seq=" + seqFrom + "-" + seqTo
                + "\n[PARENT_SUMMARY] " + forkSummary(parent)
                + "\n" + input;
        AgentRouterRunResult result;
        try {
            result = port.start(new AgentRouterRunCommand(
                    child.getId(), first.agent().getId(), threadId, forkInput, "WRITE"));
        } catch (Exception e) {
            failFork(fork, child.getId(), claimed, e.getMessage());
            return;
        }
        if (result == null || !result.accepted()) {
            failFork(fork, child.getId(), claimed,
                    result == null ? "fork start returned null" : result.message());
            return;
        }
        fork.setChildRunId(result.runId());
        forkMapper.updateById(fork);
        for (DeliveryContext context : claimed) {
            context.delivery().setStatus(result.runId() == null
                    ? AgentEventDeliveryStatus.PROCESSED
                    : AgentEventDeliveryStatus.RUNNING);
            context.delivery().setRouterRunId(result.runId());
            context.delivery().setDeliveredAt(LocalDateTime.now());
            deliveryMapper.updateById(context.delivery());
        }
        metrics.increment(AgentMetrics.AGENT_ROUTER_DELIVERY, AgentMetrics.TAG_STATUS, FORKED_STATUS_TAG);
    }

    private SessionVO createForkSession(AgentSuperAgent agent, Long threadId, Long parentSessionId,
                                        SessionVO parent, long seqFrom, long seqTo) {
        if (agent.getRouterInstanceId() == null) {
            return null;
        }
        SessionSurface previous = SessionSurfaceHolder.get();
        SessionSurfaceHolder.set(SessionSurface.INTERNAL);
        try {
            CreateSessionDTO dto = new CreateSessionDTO();
            dto.setAgentInstanceId(agent.getRouterInstanceId());
            dto.setTitle("Router fork " + seqFrom + "-" + seqTo);
            dto.setSessionType(SessionType.ROUTER);
            dto.setSuperAgentId(agent.getId());
            dto.setThreadId(threadId);
            dto.setCreatedBy(parent == null ? null : parent.getCreatedBy());
            dto.setParentSessionId(parentSessionId);
            dto.setForkKind(AgentRouterFork.KIND_ROUTER);
            dto.setForkState(AgentRouterFork.STATE_RUNNING);
            return sessionSpi.createSession(dto);
        } catch (Exception e) {
            log.warn("Router fork session creation failed: parent={}, error={}",
                    parentSessionId, e.getMessage());
            return null;
        } finally {
            if (previous == null) {
                SessionSurfaceHolder.clear();
            } else {
                SessionSurfaceHolder.set(previous);
            }
        }
    }

    private void failFork(AgentRouterFork fork, Long childSessionId,
                          List<DeliveryContext> claimed, String error) {
        fork.setState(AgentRouterFork.STATE_FAILED);
        fork.setMergeError(error == null ? null : error.substring(0, Math.min(500, error.length())));
        forkMapper.updateById(fork);
        sessionSpi.closeSession(childSessionId);
        releaseAll(claimed);
        metrics.increment(AgentMetrics.AGENT_ROUTER_START_FAILED);
        log.warn("Router fork start failed: parent={}, child={}, error={}",
                fork.getParentSessionId(), childSessionId, error);
    }

    private static String forkSummary(SessionVO parent) {
        if (parent == null || parent.getSummary() == null) {
            return "";
        }
        String summary = parent.getSummary();
        return summary.length() <= MAX_FORK_SUMMARY
                ? summary : summary.substring(0, MAX_FORK_SUMMARY);
    }

    private void releaseAll(List<DeliveryContext> claimed) {
        for (DeliveryContext context : claimed) {
            deliveryMapper.release(context.delivery().getId(),
                    LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS));
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
            dto.setCreatedBy(agentOwnerUsername(agent, thread));
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

    /**
     * A Router session belongs to the avatar's owner, not to the person who sent the group
     * message, otherwise the sender's Router tab would expose someone else's avatar session.
     */
    private String agentOwnerUsername(AgentSuperAgent agent, AgentThread thread) {
        if (agent.getOwnerUserId() != null) {
            UserSpi userSpi = userSpiProvider.getIfAvailable();
            if (userSpi != null) {
                UserVO owner = userSpi.getByUserId(agent.getOwnerUserId());
                if (owner != null && owner.getUsername() != null
                        && !owner.getUsername().isBlank()) {
                    return owner.getUsername();
                }
            }
        }
        if (agent.getCreatedBy() != null && !agent.getCreatedBy().isBlank()) {
            return agent.getCreatedBy();
        }
        return thread == null ? null : thread.getCreatedBy();
    }

    private static String routerSessionTitle(AgentSuperAgent agent, AgentThread thread) {        String suffix = thread == null || thread.getTitle() == null || thread.getTitle().isBlank()
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

    @PreDestroy
    void shutdownDispatchers() {
        kickTimer.shutdownNow();
        dispatchExecutor.shutdownNow();
    }
}
