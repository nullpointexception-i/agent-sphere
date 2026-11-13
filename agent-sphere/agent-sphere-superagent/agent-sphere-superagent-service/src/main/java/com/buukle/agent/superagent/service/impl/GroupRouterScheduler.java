package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;


import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.constant.ActorType;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SuperAgentTraceContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.dtvo.GroupRouterDecision;
import com.buukle.agent.superagent.dtvo.enums.AgentEventDeliveryStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentEventStatus;
import com.buukle.agent.superagent.dtvo.enums.GroupRouterDecisionType;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.service.event.AgentDeliveryReadyEvent;
import com.buukle.agent.superagent.spi.GroupRouterRunCommand;
import com.buukle.agent.superagent.spi.GroupRouterRunPort;
import com.buukle.agent.superagent.spi.GroupRouterRunResult;
import com.buukle.agent.superagent.spi.ImEventMetaKeys;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Group-level dispatch: fetches unprocessed events in batches per thread, calls the Group Router, and
 * decides in one pass which digital employees to deliver to.
 *
 * <p>Threads are serialized with a Redis lock; the Group Router only dispatches, it does not execute tasks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupRouterScheduler {

    static final String LOCK_PREFIX = "superagent:group-router:";
    /** Covers the synchronous run wait (default 60s) plus processing; prevents lock expiry mid-batch. */
    static final long LOCK_LEASE_SECONDS = 120L;
    /** Rough per-event prompt overhead (id/seq/kind/separators) used by the batch budget. */
    private static final int EVENT_LINE_OVERHEAD = 120;
    /** Always allow at least one event, even when the fixed prompt is already large. */
    private static final int MIN_EVENT_BUDGET = 500;

    private final AgentEventMapper eventMapper;
    private final AgentThreadMapper threadMapper;
    private final AgentEventDeliveryMapper deliveryMapper;
    private final AgentSuperAgentMapper superAgentMapper;
    private final SessionSpi sessionSpi;
    private final InstanceSpi instanceSpi;
    private final RunSpi runSpi;
    private final UserSpi userSpi;
    private final GroupRouterRunPort groupRouterRunPort;
    private final RedissonClient redissonClient;
    private final AgentMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${buukle.agent.super-agent.enabled:false}")
    private boolean superAgentEnabled;
    @Value("${buukle.agent.super-agent.group-router.enabled:false}")
    private boolean enabled;
    @Value("${buukle.agent.super-agent.group-router.batch.max-events:20}")
    private int maxEvents;
    @Value("${buukle.agent.super-agent.group-router.batch.max-chars:12000}")
    private int maxChars;
    @Value("${buukle.agent.super-agent.group-router.batch.max-wait-ms:3000}")
    private long maxWaitMs;
    @Value("${buukle.agent.super-agent.group-router.summary.max-chars:2000}")
    private int summaryMaxChars;
    @Value("${buukle.agent.super-agent.group-router.retry.max-attempts:5}")
    private int retryMaxAttempts;
    @Value("${buukle.agent.super-agent.group-router.retry.base-backoff-ms:3000}")
    private long retryBaseBackoffMs;

    @Scheduled(fixedDelayString = "${buukle.agent.super-agent.group-router.batch.interval-ms:3000}")
    public void run() {
        if (!superAgentEnabled || !enabled) {
            return;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        AuthContext.clear();
        TenantContext.clear();
        List<Long> threadIds = eventMapper.selectPendingThreadIds(20);
        for (Long threadId : threadIds) {
            RLock lock = redissonClient.getLock(LOCK_PREFIX + threadId);
            boolean locked = false;
            try {
                locked = lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
                if (!locked) {
                    continue;
                }
                if (!batchReady(threadId)) {
                    continue;
                }
                processThread(threadId);
            } catch (Exception e) {
                log.warn("Group router failed for thread {}: {}", threadId, e.getMessage());
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
                TenantScopeHolder.clear();
                TenantContext.clear();
                SuperAgentTraceContext.clear();
            }
        }
    }

    /** Wait for a small batch window before calling the router. */
    private boolean batchReady(Long threadId) {
        long pending = eventMapper.countPending(threadId);
        if (pending >= Math.max(1, maxEvents)) {
            return true;
        }
        java.time.LocalDateTime oldest = eventMapper.selectOldestPendingReceivedAt(threadId);
        if (oldest == null) {
            return false;
        }
        return java.time.Duration.between(oldest, java.time.LocalDateTime.now()).toMillis() >= maxWaitMs;
    }

    /** Processes one batch immediately (real-time wake); the caller must hold the thread lock. */
    void processThreadNow(Long threadId) {
        processThread(threadId);
    }

    /** Used by the realtime debouncer to skip a wake when another path already consumed the events. */
    boolean hasPending(Long threadId) {
        return eventMapper.countPending(threadId) > 0;
    }

    private void processThread(Long threadId) {        AgentThread thread = threadMapper.selectById(threadId);
        if (thread == null) {
            return;
        }
        bindTenant(thread);
        SuperAgentTraceContext.put(null, threadId, null);
        List<AgentEvent> events = eventMapper.selectList(Wrappers.<AgentEvent>lambdaQuery()
                .eq(AgentEvent::getThreadId, threadId)
                .isNull(AgentEvent::getGroupProcessedAt)
                .orderByAsc(AgentEvent::getSeq)
                .last("LIMIT " + Math.max(1, maxEvents)));
        if (events.isEmpty()) {
            return;
        }
        Long sessionId = thread.getGroupRouterSessionId();
        if (sessionId == null) {
            log.warn("Group router session is missing for thread {}", threadId);
            markEvents(events, GroupRouterDecisionType.OBSERVE, "group router session missing", null);
            return;
        }
        SessionVO session = sessionSpi.getSession(sessionId);
        if (session == null || !SessionType.GROUP_ROUTER.equals(session.getSessionType())) {
            log.warn("Group router session row not found for thread {}", threadId);
            markEvents(events, GroupRouterDecisionType.OBSERVE,
                    "group router session not found", null);
            return;
        }
        // A router run is already in flight: leave events pending for the compensation scheduler.
        if (runSpi.findActiveRun(sessionId) != null) {
            log.debug("Group router is running for thread {}; leaving events for compensation", threadId);
            return;
        }
        InstanceVO instance = instanceSpi.findCurrentGroupRouter(thread.getTenantId());
        if (instance == null || instance.getModelRouteId() == null) {
            log.warn("Tenant current group router instance/route is missing for tenant {}",
                    thread.getTenantId());
            markEvents(events, GroupRouterDecisionType.OBSERVE,
                    "tenant current group router instance or route missing", null);
            return;
        }
        // Follow the tenant's current instance per delivery; no bulk rebinding of old sessions.
        if (!java.util.Objects.equals(session.getAgentInstanceId(), instance.getId())) {
            sessionSpi.updateAgentInstance(sessionId, instance.getId());
            log.info("Group router session {} follows current instance: {} -> {}",
                    sessionId, session.getAgentInstanceId(), instance.getId());
        }

        Set<Long> agentIds = extractAgentIds(events);
        if (agentIds.isEmpty()) {
            markEvents(events, GroupRouterDecisionType.OBSERVE, "no agents in group", null);
            return;
        }
        List<AgentSuperAgent> agents = superAgentMapper.selectBatchIds(agentIds).stream()
                .filter(a -> SuperAgentStatus.ACTIVE.equals(a.getStatus()))
                .toList();
        if (agents.isEmpty()) {
            markEvents(events, GroupRouterDecisionType.OBSERVE, "no active agents", null);
            return;
        }

        int candidateEvents = events.size();
        events = selectEventsWithinBudget(thread, events, agents);
        if (events.size() < candidateEvents) {
            log.info("Group router deferred {} events for thread {} due to char budget",
                    candidateEvents - events.size(), threadId);
            metrics.increment(AgentMetrics.GROUP_ROUTER_DEFERRED_EVENTS,
                    AgentMetrics.TAG_DEFERRED, String.valueOf(candidateEvents - events.size()));
        }

        String userPrompt = buildPrompt(thread, events, agents);
        GroupRouterRunResult run;
        try {
            run = groupRouterRunPort.route(new GroupRouterRunCommand(
                    sessionId, threadId, thread.getTenantId(), userPrompt));
        } catch (Exception e) {
            // The run port clears the caller's tenant context in its finally;
            // restore it before any persistence on the retry path.
            bindTenant(thread);
            log.warn("Group router run failed for thread {}: {}", threadId, e.getMessage());
            scheduleRetry(events);
            return;
        }
        // GroupRouterRunService clears TenantContext/TenantScopeHolder in finally.
        // Without rebinding here the delivery inserts below get tenant_id=0 and
        // become invisible to every tenant-scoped query (including dispatch).
        bindTenant(thread);
        if (run == null || !run.accepted()) {
            log.warn("Group router run rejected for thread {}: {}",
                    threadId, run == null ? "no result" : run.message());
            scheduleRetry(events);
            return;
        }
        String raw = run.reply();

        GroupRouterDecision decision = parseDecision(raw);
        if (decision == null) {
            decision = fallbackDecision(events, agentIds);
        }
        Set<Long> allowed = new HashSet<>(agentIds);
        Map<Long, String> deliverReasons = new HashMap<>();
        Map<Long, List<Long>> readyDeliveries = new HashMap<>();
        if (decision.getDeliver() != null) {
            for (GroupRouterDecision.Deliver deliver : decision.getDeliver()) {
                if (deliver == null) {
                    continue;
                }
                if (deliver.getAgentId() == null) {
                    log.warn("Group router deliver entry has no agentId: thread={}, raw={}",
                            threadId, abbreviate(raw));
                    continue;
                }
                if (!allowed.contains(deliver.getAgentId())) {
                    log.warn("Group router deliver target is not a candidate: thread={}, agent={}, allowed={}",
                            threadId, deliver.getAgentId(), allowed);
                    continue;
                }
                deliverReasons.put(deliver.getAgentId(), deliver.getReason());
            }
        }
        if (decision.getDeliver() != null && !decision.getDeliver().isEmpty()
                && deliverReasons.isEmpty()) {
            // The model named targets but none matched: without this line the
            // batch silently becomes OBSERVE and the local agent never wakes.
            log.warn("Group router decision matched no candidate: thread={}, allowed={}, raw={}",
                    threadId, allowed, abbreviate(raw));
        }
        log.info("Group router decision: thread={}, deliver={}, ignore={}, allowed={}",
                threadId,
                decision.getDeliver() == null ? List.of() : decision.getDeliver().stream()
                        .map(GroupRouterDecision.Deliver::getAgentId).toList(),
                decision.getIgnore() == null ? List.of() : decision.getIgnore().stream()
                        .map(GroupRouterDecision.Ignore::getAgentId).toList(),
                allowed);
        for (AgentEvent event : events) {
            if (decision.getIgnore() != null) {
                for (GroupRouterDecision.Ignore ignore : decision.getIgnore()) {
                    if (ignore != null && ignore.getAgentId() != null
                            && allowed.contains(ignore.getAgentId())
                            && !deliverReasons.containsKey(ignore.getAgentId())) {
                        insertDelivery(event, ignore.getAgentId(),
                                GroupRouterDecisionType.IGNORE, ignore.getReason());
                    }
                }
            }
        }

        for (AgentEvent event : events) {
            for (Map.Entry<Long, String> entry : deliverReasons.entrySet()) {
                Long deliveryId = insertDelivery(event, entry.getKey(),
                        GroupRouterDecisionType.RELEVANT, entry.getValue());
                if (deliveryId != null) {
                    readyDeliveries.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                            .add(deliveryId);
                }
            }
            String decisionCode = deliverReasons.isEmpty()
                    ? GroupRouterDecisionType.OBSERVE : GroupRouterDecisionType.RELEVANT;
            String reason = deliverReasons.isEmpty() ? "no matching agent"
                    : "delivered to " + deliverReasons.keySet();
            markEvent(event, decisionCode, reason);
        }
        metrics.increment(AgentMetrics.GROUP_ROUTER_BATCH, AgentMetrics.TAG_DECISION,
                deliverReasons.isEmpty()
                        ? GroupRouterDecisionType.OBSERVE : GroupRouterDecisionType.RELEVANT);
        if (decision.getSummary() != null && !decision.getSummary().isBlank()) {
            String summary = decision.getSummary();
            thread.setSummary(summary.length() <= summaryMaxChars
                    ? summary : summary.substring(0, Math.max(1, summaryMaxChars)));
            threadMapper.updateById(thread);
        }
        readyDeliveries.forEach((agentId, deliveryIds) ->
                eventPublisher.publishEvent(
                        new AgentDeliveryReadyEvent(agentId, threadId, deliveryIds)));
    }

    /**
     * Fits events into the prompt character budget. Events that do not fit stay unprocessed for the
     * next batch: the router must never decide on events it did not receive.
     */
    private List<AgentEvent> selectEventsWithinBudget(AgentThread thread, List<AgentEvent> events,
                                                      List<AgentSuperAgent> agents) {
        if (events.size() <= 1) {
            return events;
        }
        int overhead = buildPrompt(thread, List.of(), agents).length();
        int budget = Math.max(MIN_EVENT_BUDGET, maxChars - overhead);
        List<AgentEvent> selected = new ArrayList<>(events.size());
        int used = 0;
        for (AgentEvent event : events) {
            int cost = extractText(event).length() + EVENT_LINE_OVERHEAD;
            if (!selected.isEmpty() && used + cost > budget) {
                break;
            }
            selected.add(event);
            used += cost;
        }
        return selected.isEmpty() ? List.of(events.get(0)) : selected;
    }

    private String buildPrompt(AgentThread thread, List<AgentEvent> events,
                               List<AgentSuperAgent> agents) {
        StringBuilder sb = new StringBuilder();
        sb.append("[GROUP] id=").append(thread.getId())
                .append(", externalId=").append(thread.getExternalThreadId())
                .append(", title=").append(thread.getTitle() == null ? "" : thread.getTitle())
                .append("\n[SUMMARY] ").append(thread.getSummary() == null ? "" : thread.getSummary())
                .append("\n[THREAD_META] ").append(thread.getMeta() == null ? "" : thread.getMeta())
                .append("\n[AGENTS]\n");
        for (AgentSuperAgent agent : agents) {
            sb.append(agent.getId()).append(" | ").append(agent.getName())
                    .append(" | ").append(agent.getDescription() == null ? "" : agent.getDescription())
                    .append(" | type=").append(agent.getAgentType())
                    .append(" | status=").append(agent.getStatus())
                    .append('\n');
        }
        sb.append("[MESSAGES]\n");
        for (AgentEvent event : events) {
            sb.append("E").append(event.getId()).append(" seq=").append(event.getSeq())
                    .append(" | ").append(event.getKind()).append(" | ")
                    .append(extractText(event)).append('\n');
        }
        sb.append("[OUTPUT] Output JSON only: ")
                .append("{\"deliver\":[{\"agentId\":1,\"reason\":\"...\"}],")
                .append("\"ignore\":[],\"summary\":\"...\"}");
        return sb.toString();
    }

    private Set<Long> extractAgentIds(List<AgentEvent> events) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AgentEvent event : events) {
            JsonNode meta = rawMeta(event);
            if (meta == null) {
                continue;
            }
            JsonNode agentIds = meta.get(ImEventMetaKeys.AGENT_IDS);
            if (agentIds != null && agentIds.isArray()) {
                for (JsonNode id : agentIds) {
                    if (id.isNumber()) {
                        ids.add(id.asLong());
                    }
                }
            }
        }
        return ids;
    }

    private GroupRouterDecision fallbackDecision(List<AgentEvent> events, Set<Long> allowed) {
        GroupRouterDecision decision = new GroupRouterDecision();
        List<GroupRouterDecision.Deliver> delivers = new ArrayList<>();
        for (AgentEvent event : events) {
            JsonNode meta = rawMeta(event);
            if (meta == null) {
                continue;
            }
            JsonNode mentions = meta.get("mentions");
            if (mentions == null || !mentions.isArray()) {
                continue;
            }
            for (JsonNode mention : mentions) {
                if (mention.has("type") && "AGENT".equalsIgnoreCase(mention.get("type").asText())
                        && mention.has("id") && allowed.contains(mention.get("id").asLong())) {
                    GroupRouterDecision.Deliver deliver = new GroupRouterDecision.Deliver();
                    deliver.setAgentId(mention.get("id").asLong());
                    deliver.setReason("mentioned");
                    delivers.add(deliver);
                }
            }
        }
        decision.setDeliver(delivers);
        decision.setSummary(null);
        return decision;
    }

    private GroupRouterDecision parseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JsonUtils.parse(text.substring(start, end + 1), GroupRouterDecision.class);
        } catch (Exception e) {
            log.warn("Group router JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private Long insertDelivery(AgentEvent event, Long agentId, String decision, String reason) {
        AgentEventDelivery delivery = new AgentEventDelivery();
        delivery.setEventId(event.getId());
        delivery.setSuperAgentId(agentId);
        delivery.setDecision(decision);
        delivery.setReason(reason);
        delivery.setStatus(GroupRouterDecisionType.IGNORE.equalsIgnoreCase(decision)
                ? AgentEventDeliveryStatus.PROCESSED : AgentEventDeliveryStatus.PENDING);
        delivery.setAttempts(0);
        delivery.setActorType(ActorType.SYSTEM);
        AgentSuperAgent agent = superAgentMapper.selectById(agentId);
        if (agent != null) {
            delivery.setCreatedBy(ownerUsername(agent.getOwnerUserId()));
        }
        // delivered_at is written by AgentRouterScheduler after the Work Item is created
        try {
            deliveryMapper.insert(delivery);
            return delivery.getId();
        } catch (DuplicateKeyException ignored) {
            // The row already exists. Re-publish the wake when it is still
            // pending: a lost realtime wake must not strand the delivery until
            // the compensation scan, and a silent null here hides that state.
            AgentEventDelivery existing = deliveryMapper.selectOne(
                    Wrappers.<AgentEventDelivery>lambdaQuery()
                            .eq(AgentEventDelivery::getEventId, event.getId())
                            .eq(AgentEventDelivery::getSuperAgentId, agentId)
                            .last("LIMIT 1"));
            if (existing != null
                    && AgentEventDeliveryStatus.PENDING.equals(existing.getStatus())) {
                log.debug("Delivery exists; re-publishing wake: event={}, agent={}, delivery={}",
                        event.getId(), agentId, existing.getId());
                return existing.getId();
            }
            return null;
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static void bindTenant(AgentThread thread) {
        if (thread.getTenantId() == null) {
            TenantContext.clear();
            TenantScopeHolder.clear();
            return;
        }
        TenantContext.set(thread.getTenantId());
        TenantScopeHolder.start(TenantScope.tenantOnly(
                thread.getTenantId(), thread.getCreatedBy()));
    }

    private void markEvents(List<AgentEvent> events, String decision, String reason, String summary) {
        for (AgentEvent event : events) {
            markEvent(event, decision, reason);
        }
    }

    private void scheduleRetry(List<AgentEvent> events) {
        for (AgentEvent event : events) {
            int attempts = (event.getAttempts() == null ? 0 : event.getAttempts()) + 1;
            event.setAttempts(attempts);
            if (attempts >= Math.max(1, retryMaxAttempts)) {
                event.setGroupDecision(GroupRouterDecisionType.OBSERVE);
                event.setGroupReason("group router retries exhausted");
                event.setGroupProcessedAt(LocalDateTime.now());
                event.setNextRetryAt(null);
            } else {
                long delay = retryBaseBackoffMs * (1L << Math.min(attempts, 6));
                event.setNextRetryAt(LocalDateTime.now().plusNanos(delay * 1_000_000L));
            }
            eventMapper.updateById(event);
        }
    }

    private void markEvent(AgentEvent event, String decision, String reason) {
        event.setGroupDecision(decision);
        event.setGroupReason(reason);
        event.setGroupProcessedAt(LocalDateTime.now());
        event.setStatus(GroupRouterDecisionType.RELEVANT.equals(decision)
                || GroupRouterDecisionType.DIRECT.equals(decision)
                ? AgentEventStatus.DELIVERED : AgentEventStatus.PROCESSED);
        eventMapper.updateById(event);
    }

    private String ownerUsername(Long ownerUserId) {
        if (ownerUserId == null) {
            return null;
        }
        try {
            UserVO owner = userSpi.getByUserId(ownerUserId);
            return owner == null ? null : owner.getUsername();
        } catch (Exception e) {
            log.debug("Owner lookup failed: userId={}", ownerUserId);
            return null;
        }
    }

    private static String extractText(AgentEvent event) {
        try {
            JsonNode root = JsonUtils.getMapper().readTree(event.getPayload());
            JsonNode text = root == null ? null : root.get("text");
            return text == null ? "" : text.asText();
        } catch (Exception e) {
            return "";
        }
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
}
