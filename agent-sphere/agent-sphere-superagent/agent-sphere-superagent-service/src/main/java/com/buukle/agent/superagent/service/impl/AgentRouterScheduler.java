package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.channel.spi.OutboundFormat;
import com.buukle.agent.channel.spi.TaskCardPayload;
import com.buukle.agent.channel.spi.TaskCardType;
import com.buukle.agent.common.constant.ActorType;
import com.buukle.agent.common.constant.RunMode;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
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
import com.buukle.agent.superagent.domain.AgentOutboundMessage;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.dtvo.enums.AgentEventDeliveryStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentEventStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentOutboundMessageStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentRouterAction;
import com.buukle.agent.superagent.dtvo.enums.AgentRouterRunStatus;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.dtvo.enums.WorkItemStatus;
import com.buukle.agent.superagent.dtvo.vo.AgentOutboundPayloadVO;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentOutboundMessageMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.repository.AgentWorkItemMapper;
import com.buukle.agent.superagent.spi.AgentRouterPort;
import com.buukle.agent.superagent.spi.AgentRouterRequest;
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
import org.springframework.dao.DuplicateKeyException;
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
 * <p>This phase still uses the JSON decision protocol. Phase 1 replaces the
 * decision with a real ROUTER session run and tool calls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRouterScheduler {

    private static final int MAX_TITLE = 200;
    private static final int MAX_EVENT_SUMMARY = 200;
    private static final String MODE_SESSION = "session";
    private static final int RETRY_DELAY_SECONDS = 30;
    private static final long GROUP_LOCK_LEASE_SECONDS = 60;
    private static final String GROUP_LOCK_PREFIX = "superagent:agent-router:";
    private static final String DEFAULT_PROMPT = """
            You are the Agent Router of a digital employee.
            Decide how to handle the delivered message:
            1) REPLY: answer directly without tools or multi-step work.
            2) DISPATCH: delegate to a Worker when tools or multi-step work are required.
            3) OBSERVE: the message is not relevant to you.
            Output strict JSON only:
            {"action":"reply|dispatch|observe","reply":"...","goal":"...","reason":"..."}
            """;

    private final AgentEventDeliveryMapper deliveryMapper;
    private final AgentEventMapper eventMapper;
    private final AgentSuperAgentMapper superAgentMapper;
    private final AgentThreadMapper threadMapper;
    private final AgentWorkItemMapper workItemMapper;
    private final AgentOutboundMessageMapper outboundMapper;
    private final InstanceSpi instanceSpi;
    private final SessionSpi sessionSpi;
    private final UserSpi userSpi;
    private final ObjectProvider<AgentRouterPort> agentRouterPortProvider;
    private final ObjectProvider<AgentRouterRunPort> agentRouterRunPortProvider;
    private final AgentMetrics metrics;
    private final RedissonClient redissonClient;

    @Value("${buukle.agent.super-agent.enabled:false}")
    private boolean superAgentEnabled;
    @Value("${buukle.agent.super-agent.agent-router.enabled:false}")
    private boolean enabled;
    @Value("${buukle.agent.super-agent.agent-router.mode:session}")
    private String mode;
    @Value("${buukle.agent.super-agent.agent-router.batch-size:20}")
    private int batchSize;
    @Value("${buukle.agent.super-agent.agent-router.max-tokens:1000}")
    private int maxTokens;
    @Value("${buukle.agent.super-agent.trigger.speculation-enabled:false}")
    private boolean speculationEnabled;

    @Scheduled(fixedDelayString = "${buukle.agent.super-agent.agent-router.interval-ms:3000}")
    public void run() {
        if (!superAgentEnabled || !enabled) {
            return;
        }
        TenantUtil.stop();
        AuthContext.clear();
        TenantContext.clear();
        if (MODE_SESSION.equalsIgnoreCase(mode)
                && agentRouterRunPortProvider.getIfAvailable() != null) {
            processSessionBatch();
            return;
        }
        List<AgentEventDelivery> deliveries = deliveryMapper.selectPending(Math.max(1, batchSize));
        for (AgentEventDelivery delivery : deliveries) {
            if (deliveryMapper.claim(delivery.getId()) == 0) {
                continue;
            }
            delivery.setStatus(AgentEventDeliveryStatus.CLAIMED);
            try {
                consume(delivery);
            } catch (Exception e) {
                log.warn("Agent router consume failed: delivery={}, error={}",
                        delivery.getId(), e.getMessage());
                deliveryMapper.release(delivery.getId(),
                        LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS));
            } finally {
                TenantContext.clear();
                SuperAgentTraceContext.clear();
            }
        }
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
            RLock lock = redissonClient.getLock(GROUP_LOCK_PREFIX
                    + head.agent.getId() + ":" + head.event.getThreadId());
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
                            first.event.getThreadId(), input.toString(), RunMode.WRITE.name()));
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

    /** One Router session per thread; legacy threads fall back to the guardian session. */
    private Long ensureRouterSession(AgentSuperAgent agent, Long threadId) {
        if (threadId == null) {
            return null;
        }
        AgentThread thread = threadMapper.selectById(threadId);
        if (thread != null && thread.getRouterSessionId() != null) {
            return thread.getRouterSessionId();
        }
        if (agent.getRouterInstanceId() == null) {
            return agent.getRouterSessionId();
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
                return agent.getRouterSessionId();
            }
            if (thread != null) {
                thread.setRouterSessionId(session.getId());
                threadMapper.updateById(thread);
            }
            return session.getId();
        } catch (Exception e) {
            log.warn("Router session create failed: agent={}, thread={}, error={}",
                    agent.getId(), threadId, e.getMessage());
            return agent.getRouterSessionId();
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

    private void consume(AgentEventDelivery delivery) {
        AgentEvent event = eventMapper.selectById(delivery.getEventId());
        AgentSuperAgent agent = superAgentMapper.selectById(delivery.getSuperAgentId());
        if (event == null || agent == null || !SuperAgentStatus.ACTIVE.equals(agent.getStatus())) {
            markDelivered(delivery, "skipped: event or agent missing");
            return;
        }
        TenantContext.set(agent.getTenantId());
        SuperAgentTraceContext.put(event.getId(), event.getThreadId(), agent.getId());
        Long routerRunId = MODE_SESSION.equalsIgnoreCase(mode)
                ? runSession(delivery, event, agent) : null;
        if (routerRunId != null) {
            delivery.setStatus(AgentEventDeliveryStatus.RUNNING);
            delivery.setRouterRunId(routerRunId);
            delivery.setDeliveredAt(LocalDateTime.now());
            deliveryMapper.updateById(delivery);
            return;
        }
        AgentThread thread = event.getThreadId() == null ? null
                : threadMapper.selectById(event.getThreadId());
        AgentRouterDecision decision = decide(agent, thread, event);
        metrics.increment(AgentMetrics.AGENT_ROUTER_DECISION, "action", decision.action);

        switch (decision.action) {
            case AgentRouterAction.REPLY -> {
                if (!enqueueReply(delivery, event, agent, decision.reply)) {
                    createWorkItem(delivery, event, agent, eventText(event), WorkItemStatus.QUEUED);
                }
                markDelivered(delivery, "reply: " + decision.reason);
            }
            case AgentRouterAction.OBSERVE -> {
                if (speculationEnabled && event.getThreadId() != null) {
                    createWorkItem(delivery, event, agent, eventText(event), WorkItemStatus.SPECULATING);
                }
                markDelivered(delivery, "observe: " + decision.reason);
            }
            default -> {
                createWorkItem(delivery, event, agent,
                        decision.goal == null || decision.goal.isBlank()
                                ? eventText(event) : decision.goal, WorkItemStatus.QUEUED);
                markDelivered(delivery, "dispatch: " + decision.reason);
            }
        }
    }

    private Long runSession(AgentEventDelivery delivery, AgentEvent event, AgentSuperAgent agent) {
        AgentRouterRunPort port = agentRouterRunPortProvider.getIfAvailable();
        if (port == null || agent.getRouterSessionId() == null) {
            return null;
        }
        String input = "[DELIVERY] deliveryId=" + delivery.getId()
                + ", eventId=" + event.getId()
                + ", threadId=" + event.getThreadId()
                + ", decision=" + delivery.getDecision()
                + ", reason=" + delivery.getReason()
                + "\n[MESSAGE] " + eventText(event)
                + "\n[INSTRUCTION] Use tools. Call recall_event when the full message is needed. "
                + "Use send_message for a direct reply, dispatch_worker for execution, "
                + "or finish without tools when the message should be observed.";
        AgentRouterRunResult result = port.start(new AgentRouterRunCommand(
                agent.getRouterSessionId(), agent.getId(), event.getThreadId(), input,
                RunMode.WRITE.name()));
        if (!result.accepted()) {
            log.warn("Agent router session rejected: delivery={}, reason={}",
                    delivery.getId(), result.message());
            return null;
        }
        return result.runId();
    }

    private AgentRouterDecision decide(AgentSuperAgent agent, AgentThread thread, AgentEvent event) {
        AgentRouterPort port = agentRouterPortProvider.getIfAvailable();
        if (port == null || agent.getRouterInstanceId() == null) {
            return AgentRouterDecision.dispatch(null, "router unavailable");
        }
        try {
            String systemPrompt = resolveSystemPrompt(agent.getRouterInstanceId());
            String userPrompt = buildPrompt(agent, thread, event);
            String raw = port.decide(new AgentRouterRequest(
                    agent.getRouterInstanceId(), agent.getRouterSessionId(),
                    event.getThreadId(), agent.getId(), systemPrompt, userPrompt, maxTokens));
            return parse(raw);
        } catch (Exception e) {
            log.warn("Agent router LLM failed: agent={}, error={}", agent.getId(), e.getMessage());
            return AgentRouterDecision.dispatch(null, "router failed: " + e.getMessage());
        }
    }

    private String resolveSystemPrompt(Long instanceId) {
        InstanceVO instance = instanceSpi.getInstance(instanceId);
        if (instance == null || instance.getSystemPrompt() == null
                || instance.getSystemPrompt().isBlank()) {
            return DEFAULT_PROMPT;
        }
        return instance.getSystemPrompt();
    }

    private static String buildPrompt(AgentSuperAgent agent, AgentThread thread, AgentEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("[AGENT] id=").append(agent.getId())
                .append(", name=").append(agent.getName())
                .append(", description=").append(agent.getDescription() == null ? "" : agent.getDescription())
                .append('\n');
        if (thread != null) {
            sb.append("[THREAD] id=").append(thread.getId())
                    .append(", title=").append(thread.getTitle() == null ? "" : thread.getTitle())
                    .append(", summary=").append(thread.getSummary() == null ? "" : thread.getSummary())
                    .append('\n');
        }
        sb.append("[MESSAGE] ").append(eventText(event)).append('\n');
        sb.append("[OUTPUT] strict JSON only: ")
                .append("{\"action\":\"reply|dispatch|observe\",\"reply\":\"...\",")
                .append("\"goal\":\"...\",\"reason\":\"...\"}");
        return sb.toString();
    }

    private boolean enqueueReply(AgentEventDelivery delivery, AgentEvent event,
                                 AgentSuperAgent agent, String reply) {
        if (reply == null || reply.isBlank() || event.getThreadId() == null) {
            return false;
        }
        AgentOutboundMessage outbound = new AgentOutboundMessage();
        outbound.setSuperAgentId(agent.getId());
        outbound.setThreadId(event.getThreadId());
        outbound.setIdempotencyKey("agent-reply-" + delivery.getId());
        AgentOutboundPayloadVO payload = new AgentOutboundPayloadVO();
        payload.setText(reply);
        payload.setFormat(OutboundFormat.TEXT);
        outbound.setPayload(JsonUtils.toJson(payload));
        outbound.setStatus(AgentOutboundMessageStatus.PENDING);
        outbound.setAttempts(0);
        outbound.setCreatedBy(ownerUsername(agent.getOwnerUserId()));
        outbound.setActorType(ActorType.AGENT);
        outbound.setActorId(agent.getId());
        try {
            outboundMapper.insert(outbound);
            return true;
        } catch (DuplicateKeyException ignored) {
            return true;
        }
    }

    private void createWorkItem(AgentEventDelivery delivery, AgentEvent event,
                                AgentSuperAgent agent, String goal, String status) {
        Long existing = workItemMapper.selectCount(Wrappers.<AgentWorkItem>lambdaQuery()
                .eq(AgentWorkItem::getTriggerEventId, event.getId())
                .eq(AgentWorkItem::getSuperAgentId, agent.getId()));
        if (existing != null && existing > 0) {
            return;
        }
        AgentWorkItem item = new AgentWorkItem();
        item.setOwnerUserId(agent.getOwnerUserId());
        item.setSuperAgentId(agent.getId());
        item.setThreadId(event.getThreadId());
        item.setTriggerEventId(event.getId());
        item.setAttempt(1);
        item.setTitle(truncate(goal));
        item.setGoal(goal);
        item.setPriority(100);
        item.setStatus(status);
        item.setCreatedBy(ownerUsername(agent.getOwnerUserId()));
        item.setActorType(ActorType.AGENT);
        item.setActorId(agent.getId());
        try {
            workItemMapper.insert(item);
            enqueueTaskCard(item);
        } catch (DuplicateKeyException ignored) {
            // Another replica created the item.
        }
    }

    private void enqueueTaskCard(AgentWorkItem item) {
        if (item.getId() == null || item.getThreadId() == null) {
            return;
        }
        TaskCardPayload card = new TaskCardPayload();
        card.setCardType(TaskCardType.TASK);
        card.setWorkItemId(item.getId());
        card.setTitle(item.getTitle());
        card.setStatus(WorkItemStatus.QUEUED);
        card.setSuperAgentId(item.getSuperAgentId());

        AgentOutboundMessage outbound = new AgentOutboundMessage();
        outbound.setSuperAgentId(item.getSuperAgentId());
        outbound.setThreadId(item.getThreadId());
        outbound.setIdempotencyKey("work-item-" + item.getId() + "-" + attempt(item) + "-queued");
        AgentOutboundPayloadVO payload = new AgentOutboundPayloadVO();
        payload.setText(JsonUtils.toJson(card));
        payload.setFormat(OutboundFormat.CARD);
        outbound.setPayload(JsonUtils.toJson(payload));
        outbound.setStatus(AgentOutboundMessageStatus.PENDING);
        outbound.setAttempts(0);
        outbound.setCreatedBy(ownerUsername(item.getOwnerUserId()));
        outbound.setActorType(ActorType.AGENT);
        outbound.setActorId(item.getSuperAgentId());
        try {
            outboundMapper.insert(outbound);
        } catch (DuplicateKeyException ignored) {
            // Already enqueued.
        }
    }

    private void markDelivered(AgentEventDelivery delivery, String reason) {
        delivery.setDeliveredAt(LocalDateTime.now());
        delivery.setStatus(AgentEventDeliveryStatus.PROCESSED);
        if (reason != null) {
            delivery.setReason(truncate(reason));
        }
        deliveryMapper.updateById(delivery);
        Long pending = deliveryMapper.selectCount(Wrappers.<AgentEventDelivery>lambdaQuery()
                .eq(AgentEventDelivery::getEventId, delivery.getEventId())
                .in(AgentEventDelivery::getStatus,
                        AgentEventDeliveryStatus.PENDING,
                        AgentEventDeliveryStatus.CLAIMED,
                        AgentEventDeliveryStatus.RUNNING));
        if (pending == null || pending == 0) {
            AgentEvent event = eventMapper.selectById(delivery.getEventId());
            if (event != null) {
                event.setStatus(AgentEventStatus.PROCESSED);
                eventMapper.updateById(event);
            }
        }
    }

    private static AgentRouterDecision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AgentRouterDecision.dispatch(null, "empty router output");
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return AgentRouterDecision.dispatch(null, "router output is not JSON");
        }
        try {
            JsonNode root = JsonUtils.getMapper().readTree(text.substring(start, end + 1));
            String action = root.hasNonNull("action")
                    ? root.get("action").asText().toUpperCase() : AgentRouterAction.DISPATCH;
            String reply = root.hasNonNull("reply") ? root.get("reply").asText() : null;
            String goal = root.hasNonNull("goal") ? root.get("goal").asText() : null;
            String reason = root.hasNonNull("reason") ? root.get("reason").asText() : null;
            return switch (action) {
                case AgentRouterAction.REPLY ->
                        new AgentRouterDecision(AgentRouterAction.REPLY, reply, null, reason);
                case AgentRouterAction.OBSERVE ->
                        new AgentRouterDecision(AgentRouterAction.OBSERVE, null, null, reason);
                default -> new AgentRouterDecision(AgentRouterAction.DISPATCH, null, goal, reason);
            };
        } catch (Exception e) {
            return AgentRouterDecision.dispatch(null, "router JSON parse failed");
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

    private static String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "Untitled task";
        }
        return text.length() <= MAX_TITLE ? text : text.substring(0, MAX_TITLE);
    }

    private static String summary(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_EVENT_SUMMARY ? text : text.substring(0, MAX_EVENT_SUMMARY);
    }

    private static int attempt(AgentWorkItem item) {
        return item.getAttempt() == null ? 1 : item.getAttempt();
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

    private record AgentRouterDecision(String action, String reply, String goal, String reason) {
        private static AgentRouterDecision dispatch(String goal, String reason) {
            return new AgentRouterDecision(AgentRouterAction.DISPATCH, null, goal, reason);
        }
    }
}
