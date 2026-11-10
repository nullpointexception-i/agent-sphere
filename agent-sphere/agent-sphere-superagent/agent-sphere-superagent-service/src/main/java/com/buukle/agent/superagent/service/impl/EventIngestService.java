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
import com.buukle.agent.superagent.service.event.AgentDeliveryCreatedEvent;
import com.buukle.agent.superagent.service.event.AgentDeliveryReadyEvent;
import com.buukle.agent.superagent.service.event.GroupRouterWakeEvent;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Unified event entry point: NormalizedEvent from IM / Webhook is first persisted to agent_thread + agent_event.
 *
 * <p>Only handles persistence and idempotency; relevance decisions are left to the Group Router downstream.
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
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public IngestResult ingest(NormalizedEvent event) {
        if (event == null || event.externalThreadId() == null || event.externalThreadId().isBlank()) {
            return IngestResult.rejected();
        }
        AgentThread thread = findThread(event);
        if (thread == null) {
            log.warn("IM/channel event has no pre-created thread: channel={}, externalThreadId={}",
                    event.channelType(), event.externalThreadId());
            return IngestResult.rejected();
        }
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
            if (isDirectDm(event.rawMeta())) {
                directDeliverAgents(row, agentIds(row.getPayload()));
            } else {
                passThroughSubscriptions(row);
                directDeliverMentions(row, event);
            }
            eventPublisher.publishEvent(new AgentDeliveryCreatedEvent(thread.getId()));
            if (row.getGroupProcessedAt() == null) {
                // Realtime Group Router wake; the scheduled batch remains the compensation path.
                eventPublisher.publishEvent(new GroupRouterWakeEvent(thread.getId()));
            }
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

    /** Direct @Agent events skip the group batch: write a DIRECT delivery immediately and mark it processed. */
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
                eventPublisher.publishEvent(new AgentDeliveryReadyEvent(
                        subscription.getSuperAgentId(), row.getThreadId(),
                        List.of(delivery.getId())));
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

    /** DM messages are delivered straight to the agent participants. */
    private static boolean isDirectDm(String rawMeta) {
        if (rawMeta == null || rawMeta.isBlank()) {
            return false;
        }
        try {
            JsonNode root = JsonUtils.getMapper().readTree(rawMeta);
            JsonNode type = root == null ? null : root.get("conversationType");
            return type != null && type.isTextual() && "DM".equalsIgnoreCase(type.asText());
        } catch (Exception e) {
            return false;
        }
    }

    private void directDeliverAgents(AgentEvent row, Set<Long> agentIds) {
        if (agentIds.isEmpty()) {
            row.setGroupDecision(GroupRouterDecisionType.OBSERVE);
            row.setGroupReason("no agents in dm");
            row.setGroupProcessedAt(LocalDateTime.now());
            row.setStatus(AgentEventStatus.DELIVERED);
            eventMapper.updateById(row);
            return;
        }
        for (Long agentId : agentIds) {
            if (agentId == null) {
                continue;
            }
            AgentEventDelivery delivery = new AgentEventDelivery();
            delivery.setEventId(row.getId());
            delivery.setSuperAgentId(agentId);
            delivery.setDecision(GroupRouterDecisionType.DIRECT);
            delivery.setReason("dm participant");
            delivery.setStatus(AgentEventDeliveryStatus.PENDING);
            delivery.setAttempts(0);
            delivery.setActorType(ActorType.SYSTEM);
            try {
                deliveryMapper.insert(delivery);
                eventPublisher.publishEvent(new AgentDeliveryReadyEvent(
                        agentId, row.getThreadId(), List.of(delivery.getId())));
            } catch (DuplicateKeyException ignored) {
                // Already delivered.
            }
        }
        row.setGroupDecision(GroupRouterDecisionType.DIRECT);
        row.setGroupReason("dm participant agents: " + agentIds);
        row.setGroupProcessedAt(LocalDateTime.now());
        row.setStatus(AgentEventStatus.DELIVERED);
        eventMapper.updateById(row);
    }

    private static Set<Long> agentIds(String payload) {        try {
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
            // delivered_at is written by AgentRouterScheduler after the Work Item is created
            delivery.setDeliveredAt(null);
            try {
                deliveryMapper.insert(delivery);
                eventPublisher.publishEvent(new AgentDeliveryReadyEvent(
                        agentId, row.getThreadId(), List.of(delivery.getId())));
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

    private AgentThread findThread(NormalizedEvent event) {
        return threadMapper.selectOne(Wrappers.<AgentThread>lambdaQuery()
                .eq(AgentThread::getExternalThreadId, event.externalThreadId())
                .eq(event.channelBindingId() != null, AgentThread::getChannelBindingId, event.channelBindingId())
                .isNull(event.channelBindingId() == null, AgentThread::getChannelBindingId)
                .last("LIMIT 1"));
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
