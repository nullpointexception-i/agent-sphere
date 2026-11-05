package com.buukle.agent.superagent.service.impl;

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
import com.buukle.agent.superagent.spi.GroupRouterPort;
import com.buukle.agent.superagent.spi.GroupRouterRequest;
import com.buukle.agent.superagent.service.support.GroupRouterTemplate;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
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
 * 群级分派：按 thread 批量取未处理事件，调用 Group Router，一次决定投给哪些数字员工。
 *
 * <p>同一 thread 用 Redis 锁串行；Group Router 只做分派，不执行任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupRouterScheduler {

    private static final String LOCK_PREFIX = "superagent:group-router:";
    private static final String ROLE_GROUP_ROUTER = "GROUP_ROUTER";

    private final AgentEventMapper eventMapper;
    private final AgentThreadMapper threadMapper;
    private final AgentEventDeliveryMapper deliveryMapper;
    private final AgentSuperAgentMapper superAgentMapper;
    private final SessionSpi sessionSpi;
    private final InstanceSpi instanceSpi;
    private final UserSpi userSpi;
    private final GroupRouterPort groupRouterPort;
    private final RedissonClient redissonClient;
    private final AgentMetrics metrics;

    @Value("${buukle.agent.super-agent.enabled:false}")
    private boolean superAgentEnabled;
    @Value("${buukle.agent.super-agent.group-router.enabled:false}")
    private boolean enabled;
    @Value("${buukle.agent.super-agent.group-router.instance-id:0}")
    private Long groupRouterInstanceId;
    @Value("${buukle.agent.super-agent.group-router.batch.max-events:20}")
    private int maxEvents;
    @Value("${buukle.agent.super-agent.group-router.batch.max-chars:12000}")
    private int maxChars;
    @Value("${buukle.agent.super-agent.group-router.batch.max-tokens:2000}")
    private int maxTokens;
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
        Long routerInstanceId = resolveInstanceId();
        if (routerInstanceId == null) {
            return;
        }
        TenantUtil.stop();
        AuthContext.clear();
        TenantContext.clear();
        List<Long> threadIds = eventMapper.selectPendingThreadIds(20);
        for (Long threadId : threadIds) {
            RLock lock = redissonClient.getLock(LOCK_PREFIX + threadId);
            boolean locked = false;
            try {
                locked = lock.tryLock(0, 30, TimeUnit.SECONDS);
                if (!locked) {
                    continue;
                }
                if (!batchReady(threadId)) {
                    continue;
                }
                processThread(threadId, routerInstanceId);
            } catch (Exception e) {
                log.warn("Group router failed for thread {}: {}", threadId, e.getMessage());
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
                TenantContext.clear();
                SuperAgentTraceContext.clear();
            }
        }
    }

    /** 显式配置优先；否则回落启动时自动创建的共享 GROUP_ROUTER 实例。 */
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

    private Long resolveInstanceId() {
        if (groupRouterInstanceId != null && groupRouterInstanceId > 0) {
            return groupRouterInstanceId;
        }
        InstanceVO instance = instanceSpi.findByRole(ROLE_GROUP_ROUTER);
        return instance == null ? null : instance.getId();
    }

    private void processThread(Long threadId, Long routerInstanceId) {
        AgentThread thread = threadMapper.selectById(threadId);
        if (thread == null) {
            return;
        }
        TenantContext.set(thread.getTenantId());
        SuperAgentTraceContext.put(null, threadId, null);
        List<AgentEvent> events = eventMapper.selectList(Wrappers.<AgentEvent>lambdaQuery()
                .eq(AgentEvent::getThreadId, threadId)
                .isNull(AgentEvent::getGroupProcessedAt)
                .orderByAsc(AgentEvent::getSeq)
                .last("LIMIT " + Math.max(1, maxEvents)));
        if (events.isEmpty()) {
            return;
        }
        Long sessionId = ensureGroupSession(thread, routerInstanceId);
        if (sessionId == null) {
            return;
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

        String systemPrompt = resolveSystemPrompt(routerInstanceId);
        String userPrompt = buildPrompt(thread, events, agents);
        String raw;
        try {
            raw = groupRouterPort.classify(new GroupRouterRequest(
                    routerInstanceId, sessionId, threadId, systemPrompt, userPrompt, maxTokens));
        } catch (Exception e) {
            log.warn("Group router LLM failed for thread {}: {}", threadId, e.getMessage());
            scheduleRetry(events);
            return;
        }

        GroupRouterDecision decision = parseDecision(raw);
        if (decision == null) {
            decision = fallbackDecision(events, agentIds);
        }
        Set<Long> allowed = new HashSet<>(agentIds);
        Map<Long, String> deliverReasons = new HashMap<>();
        if (decision.getDeliver() != null) {
            for (GroupRouterDecision.Deliver deliver : decision.getDeliver()) {
                if (deliver != null && deliver.getAgentId() != null && allowed.contains(deliver.getAgentId())) {
                    deliverReasons.put(deliver.getAgentId(), deliver.getReason());
                }
            }
        }
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
                insertDelivery(event, entry.getKey(),
                        GroupRouterDecisionType.RELEVANT, entry.getValue());
            }
            String decisionCode = deliverReasons.isEmpty()
                    ? GroupRouterDecisionType.OBSERVE : GroupRouterDecisionType.RELEVANT;
            String reason = deliverReasons.isEmpty() ? "no matching agent"
                    : "delivered to " + deliverReasons.keySet();
            markEvent(event, decisionCode, reason);
        }
        metrics.increment(AgentMetrics.GROUP_ROUTER_BATCH, "decision",
                deliverReasons.isEmpty()
                        ? GroupRouterDecisionType.OBSERVE : GroupRouterDecisionType.RELEVANT);
        if (decision.getSummary() != null && !decision.getSummary().isBlank()) {
            String summary = decision.getSummary();
            thread.setSummary(summary.length() <= summaryMaxChars
                    ? summary : summary.substring(0, Math.max(1, summaryMaxChars)));
            threadMapper.updateById(thread);
        }
    }

    private Long ensureGroupSession(AgentThread thread, Long routerInstanceId) {
        if (thread.getGroupRouterSessionId() != null) {
            return thread.getGroupRouterSessionId();
        }
        CreateSessionDTO dto = new CreateSessionDTO();
        dto.setAgentInstanceId(routerInstanceId);
        dto.setTitle("Group Router " + thread.getExternalThreadId());
        dto.setSessionType(SessionType.GROUP_ROUTER);
        dto.setThreadId(thread.getId());
        dto.setCreatedBy(thread.getCreatedBy());
        SessionVO session = sessionSpi.createSession(dto);
        thread.setGroupRouterSessionId(session.getId());
        threadMapper.updateById(thread);
        return session.getId();
    }

    private String resolveSystemPrompt(Long routerInstanceId) {
        InstanceVO instance = instanceSpi.getInstance(routerInstanceId);
        if (instance == null || instance.getSystemPrompt() == null
                || instance.getSystemPrompt().isBlank()) {
            return GroupRouterTemplate.DEFAULT;
        }
        return instance.getSystemPrompt();
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
                    .append(" | status=").append(agent.getStatus())
                    .append('\n');
        }
        sb.append("[MESSAGES]\n");
        for (AgentEvent event : events) {
            sb.append("E").append(event.getId()).append(" seq=").append(event.getSeq())
                    .append(" | ").append(event.getKind()).append(" | ")
                    .append(extractText(event)).append('\n');
            if (sb.length() >= maxChars) {
                sb.append("[truncated]\n");
                break;
            }
        }
        sb.append("[OUTPUT] 只输出 JSON：")
                .append("{\"deliver\":[{\"agentId\":1,\"reason\":\"...\"}],")
                .append("\"ignore\":[],\"summary\":\"...\"}");
        return sb.length() > maxChars ? sb.substring(0, maxChars) : sb.toString();
    }

    private Set<Long> extractAgentIds(List<AgentEvent> events) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AgentEvent event : events) {
            JsonNode meta = rawMeta(event);
            if (meta == null) {
                continue;
            }
            JsonNode agentIds = meta.get("agentIds");
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

    private void insertDelivery(AgentEvent event, Long agentId, String decision, String reason) {
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
        // delivered_at 由 AgentRouterScheduler 在创建 Work Item 后写入
        try {
            deliveryMapper.insert(delivery);
        } catch (DuplicateKeyException ignored) {
            // 已投递过；幂等
        }
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
