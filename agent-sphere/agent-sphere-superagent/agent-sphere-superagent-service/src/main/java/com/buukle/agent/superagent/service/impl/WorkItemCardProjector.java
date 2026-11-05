package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.channel.spi.OutboundFormat;
import com.buukle.agent.channel.spi.TaskCardPayload;
import com.buukle.agent.channel.spi.TaskCardType;
import com.buukle.agent.common.constant.ActorType;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentOutboundMessage;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.dtvo.enums.AgentOutboundMessageStatus;
import com.buukle.agent.superagent.dtvo.vo.AgentOutboundPayloadVO;
import com.buukle.agent.superagent.repository.AgentOutboundMessageMapper;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Deterministic task-card projection from Work Item state. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkItemCardProjector {

    private static final int MAX_ID_BYTES = 128;

    private final AgentOutboundMessageMapper outboundMapper;
    private final UserSpi userSpi;

    public void projectQueued(AgentWorkItem item) {
        if (item.getThreadId() == null) {
            return;
        }
        TaskCardPayload card = new TaskCardPayload();
        card.setCardType(TaskCardType.TASK);
        card.setWorkItemId(item.getId());
        card.setTitle(item.getTitle());
        card.setStatus(item.getStatus());
        card.setSuperAgentId(item.getSuperAgentId());
        enqueue(item, card, "work-item-" + item.getId() + "-" + attempt(item) + "-queued");
    }

    public void projectTerminal(AgentWorkItem item) {
        String summary = item.getSummary();
        if (summary == null || summary.isBlank() || item.getThreadId() == null) {
            return;
        }
        TaskCardPayload card = new TaskCardPayload();
        card.setCardType(TaskCardType.TASK_RESULT);
        card.setWorkItemId(item.getId());
        card.setTitle(item.getTitle());
        card.setStatus(item.getStatus());
        card.setSummary(summary);
        card.setSuperAgentId(item.getSuperAgentId());

        AgentOutboundMessage outbound = new AgentOutboundMessage();
        outbound.setSuperAgentId(item.getSuperAgentId());
        outbound.setThreadId(item.getThreadId());
        outbound.setIdempotencyKey("work-item-" + item.getId() + "-" + attempt(item) + "-completed");
        AgentOutboundPayloadVO payload = new AgentOutboundPayloadVO();
        payload.setText(JsonUtils.toJson(card));
        payload.setFormat(OutboundFormat.CARD);
        payload.setTargetMessageId(queuedCardMessageId(item));
        outbound.setPayload(JsonUtils.toJson(payload));
        outbound.setStatus(AgentOutboundMessageStatus.PENDING);
        outbound.setAttempts(0);
        outbound.setCreatedBy(ownerUsername(item.getOwnerUserId()));
        outbound.setActorType(ActorType.AGENT);
        outbound.setActorId(item.getSuperAgentId());
        try {
            outboundMapper.insert(outbound);
        } catch (DuplicateKeyException ignored) {
            // Already projected.
        }
    }

    private void enqueue(AgentWorkItem item, TaskCardPayload card, String key) {
        AgentOutboundMessage outbound = new AgentOutboundMessage();
        outbound.setSuperAgentId(item.getSuperAgentId());
        outbound.setThreadId(item.getThreadId());
        outbound.setIdempotencyKey(key);
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
            // Already projected.
        }
    }

    private Long queuedCardMessageId(AgentWorkItem item) {
        AgentOutboundMessage queued = outboundMapper.selectOne(Wrappers.<AgentOutboundMessage>lambdaQuery()
                .eq(AgentOutboundMessage::getIdempotencyKey,
                        "work-item-" + item.getId() + "-" + attempt(item) + "-queued")
                .last("LIMIT 1"));
        if (queued == null || queued.getExternalMessageId() == null) {
            return null;
        }
        try {
            return Long.valueOf(queued.getExternalMessageId());
        } catch (NumberFormatException e) {
            return null;
        }
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

    private static int attempt(AgentWorkItem item) {
        return item.getAttempt() == null ? 1 : item.getAttempt();
    }
}
