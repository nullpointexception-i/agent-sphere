package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.channel.spi.EventIngestPort;
import com.buukle.agent.channel.spi.IngestResult;
import com.buukle.agent.channel.spi.NormalizedEvent;
import com.buukle.agent.common.constant.ActorType;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.domain.AgentThreadSubscription;
import com.buukle.agent.superagent.dtvo.enums.AgentEventDeliveryStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentEventStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentThreadState;
import com.buukle.agent.superagent.dtvo.enums.AgentThreadSubscriptionStatus;
import com.buukle.agent.superagent.dtvo.enums.GroupRouterDecisionType;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.repository.AgentThreadSubscriptionMapper;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 统一事件入口：IM / Webhook 的 NormalizedEvent 先落 agent_thread + agent_event。
 *
 * <p>只负责持久化与幂等；相关性判断由 Group Router 后续消费。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventIngestService implements EventIngestPort {

    private final AgentThreadMapper threadMapper;
    private final AgentEventMapper eventMapper;
    private final AgentEventDeliveryMapper deliveryMapper;
    private final AgentThreadSubscriptionMapper subscriptionMapper;
    private final AgentMetrics metrics;

    @Override
    @Transactional
    public IngestResult ingest(NormalizedEvent event) {
        if (event == null || event.externalThreadId() == null || event.externalThreadId().isBlank()) {
            return IngestResult.rejected();
        }
        AgentThread thread = findOrCreateThread(event);
        Long seq = threadMapper.nextEventSeq(thread.getId());
        if (seq == null) {
            return IngestResult.rejected();
        }
        String dedupeKey = dedupeKey(event);
        AgentEvent row = new AgentEvent();
        row.setThreadId(thread.getId());
        row.setSeq(seq);
        row.setKind(event.kind());
        row.setExternalId(event.externalEventId());
        row.setDedupeKey(dedupeKey);
        row.setPayload(JsonUtils.toJson(event));
        row.setStatus(AgentEventStatus.RECEIVED);
        row.setReceivedAt(LocalDateTime.now());
        row.setActorType(ActorType.USER);
        row.setActorId(parseActorId(event.author()));
        try {
            eventMapper.insert(row);
            metrics.increment(AgentMetrics.EVENT_INGESTED);
            passThroughSubscriptions(row);
            directDeliverMentions(row, event);
            return IngestResult.accepted(row.getId(), thread.getId());
        } catch (DuplicateKeyException e) {
            AgentEvent existing = eventMapper.selectOne(Wrappers.<AgentEvent>lambdaQuery()
                    .eq(AgentEvent::getDedupeKey, dedupeKey)
                    .last("LIMIT 1"));
            return existing == null
                    ? IngestResult.rejected()
                    : IngestResult.duplicate(existing.getId(), thread.getId());
        }
    }

    /** 直接 @Agent 的事件不进入群级批次，立即写 DIRECT 投递并标记已处理。 */
    /** Direct pass-through for agents subscribed to this thread. */
    private void passThroughSubscriptions(AgentEvent row) {
        List<AgentThreadSubscription> subscriptions = subscriptionMapper.selectList(
                Wrappers.<AgentThreadSubscription>lambdaQuery()
                        .eq(AgentThreadSubscription::getThreadId, row.getThreadId())
                        .eq(AgentThreadSubscription::getStatus, AgentThreadSubscriptionStatus.ACTIVE));
        for (AgentThreadSubscription subscription : subscriptions) {
            AgentEventDelivery delivery = new AgentEventDelivery();
            delivery.setEventId(row.getId());
            delivery.setSuperAgentId(subscription.getSuperAgentId());
            delivery.setDecision(GroupRouterDecisionType.RELEVANT);
            delivery.setReason("thread subscription");
            delivery.setStatus(AgentEventDeliveryStatus.PENDING);
            delivery.setAttempts(0);
            delivery.setActorType(ActorType.SYSTEM);
            try {
                deliveryMapper.insert(delivery);
            } catch (DuplicateKeyException ignored) {
                // Already delivered.
            }
        }
        Set<Long> groupAgents = agentIds(row.getPayload());
        Set<Long> subscribed = subscriptions.stream()
                .map(AgentThreadSubscription::getSuperAgentId)
                .collect(java.util.stream.Collectors.toSet());
        if (!groupAgents.isEmpty() && subscribed.containsAll(groupAgents)) {
            row.setGroupDecision(GroupRouterDecisionType.RELEVANT);
            row.setGroupReason("thread subscription covers all agents");
            row.setGroupProcessedAt(LocalDateTime.now());
            row.setStatus(AgentEventStatus.DELIVERED);
            eventMapper.updateById(row);
        }
    }

    private static Set<Long> agentIds(String payload) {
        try {
            JsonNode root = JsonUtils.getMapper().readTree(payload);
            JsonNode raw = root == null ? null : root.get("rawMeta");
            if (raw == null || !raw.isTextual()) {
                return Set.of();
            }
            JsonNode meta = JsonUtils.getMapper().readTree(raw.asText());
            JsonNode ids = meta == null ? null : meta.get("agentIds");
            if (ids == null || !ids.isArray()) {
                return Set.of();
            }
            Set<Long> result = new java.util.HashSet<>();
            for (JsonNode id : ids) {
                if (id.isNumber()) {
                    result.add(id.asLong());
                }
            }
            return result;
        } catch (Exception e) {
            return Set.of();
        }
    }

    private void directDeliverMentions(AgentEvent row, NormalizedEvent event) {
        List<Long> agentIds = mentionedAgentIds(event.rawMeta());
        if (agentIds.isEmpty()) {
            return;
        }
        for (Long agentId : agentIds) {
            AgentEventDelivery delivery = new AgentEventDelivery();
            delivery.setEventId(row.getId());
            delivery.setSuperAgentId(agentId);
            delivery.setDecision(GroupRouterDecisionType.DIRECT);
            delivery.setReason("mentioned");
            delivery.setStatus(AgentEventDeliveryStatus.PENDING);
            delivery.setAttempts(0);
            delivery.setActorType(ActorType.SYSTEM);
            // delivered_at 由 AgentRouterScheduler 在创建 Work Item 后写入
            delivery.setDeliveredAt(null);
            try {
                deliveryMapper.insert(delivery);
            } catch (DuplicateKeyException ignored) {
                // already delivered
            }
        }
        row.setGroupDecision(GroupRouterDecisionType.DIRECT);
        row.setGroupReason("mentioned agents: " + agentIds);
        row.setGroupProcessedAt(LocalDateTime.now());
        row.setStatus(AgentEventStatus.DELIVERED);
        eventMapper.updateById(row);
    }

    private static List<Long> mentionedAgentIds(String rawMeta) {
        if (rawMeta == null || rawMeta.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = JsonUtils.getMapper().readTree(rawMeta);
            JsonNode mentions = root == null ? null : root.get("mentions");
            if (mentions == null || !mentions.isArray()) {
                return List.of();
            }
            List<Long> ids = new java.util.ArrayList<>();
            for (JsonNode mention : mentions) {
                if (mention.has("type") && "AGENT".equalsIgnoreCase(mention.get("type").asText())
                        && mention.has("id") && mention.get("id").isNumber()) {
                    ids.add(mention.get("id").asLong());
                }
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    private AgentThread findOrCreateThread(NormalizedEvent event) {
        AgentThread thread = threadMapper.selectOne(Wrappers.<AgentThread>lambdaQuery()
                .eq(AgentThread::getExternalThreadId, event.externalThreadId())
                .eq(event.channelBindingId() != null, AgentThread::getChannelBindingId, event.channelBindingId())
                .isNull(event.channelBindingId() == null, AgentThread::getChannelBindingId)
                .last("LIMIT 1"));
        if (thread != null) {
            if (thread.getTitle() == null) {
                String title = rawField(event.rawMeta(), "conversationTitle");
                if (title != null) {
                    thread.setTitle(title);
                    threadMapper.updateById(thread);
                }
            }
            return thread;
        }
        thread = new AgentThread();
        thread.setExternalThreadId(event.externalThreadId());
        thread.setChannelBindingId(event.channelBindingId());
        thread.setKind(event.channelType());
        thread.setTitle(rawField(event.rawMeta(), "conversationTitle"));
        thread.setMeta(event.rawMeta());
        thread.setEventSeq(0L);
        thread.setState(AgentThreadState.OPEN);
        thread.setActorType(ActorType.USER);
        thread.setActorId(parseActorId(event.author()));
        threadMapper.insert(thread);
        return thread;
    }

    private static Long parseActorId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String rawField(String rawMeta, String field) {
        if (rawMeta == null || rawMeta.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JsonUtils.getMapper().readTree(rawMeta);
            JsonNode value = root == null ? null : root.get(field);
            return value == null || value.isNull() ? null : value.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static String dedupeKey(NormalizedEvent event) {
        if (event.externalEventId() != null && !event.externalEventId().isBlank()) {
            return event.channelType() + ":" + event.externalThreadId() + ":" + event.externalEventId();
        }
        String text = event.text() == null ? "" : event.text();
        return event.channelType() + ":" + event.externalThreadId() + ":hash:"
                + Integer.toHexString(text.hashCode());
    }
}
