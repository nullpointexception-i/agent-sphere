package com.buukle.agent.superagent.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.channel.spi.OutboundFormat;
import com.buukle.agent.channel.spi.TaskCardPayload;
import com.buukle.agent.channel.spi.TaskCardType;
import com.buukle.agent.common.constant.ActorType;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentOutboundMessage;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.domain.AgentThreadSubscription;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.dtvo.enums.AgentOutboundMessageStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentThreadState;
import com.buukle.agent.superagent.dtvo.enums.AgentThreadSubscriptionMode;
import com.buukle.agent.superagent.dtvo.enums.AgentThreadSubscriptionStatus;
import com.buukle.agent.superagent.dtvo.enums.WorkItemStatus;
import com.buukle.agent.superagent.dtvo.vo.AgentOutboundPayloadVO;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentOutboundMessageMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.repository.AgentThreadSubscriptionMapper;
import com.buukle.agent.superagent.repository.AgentWorkItemMapper;
import com.buukle.agent.superagent.service.impl.WorkItemCardProjector;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Side effects used by Super-Agent router tools. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRouterToolService {

    private static final int MAX_TITLE = 200;

    private final SessionSpi sessionSpi;
    private final RunSpi runSpi;
    private final UserSpi userSpi;
    private final AgentSuperAgentMapper superAgentMapper;
    private final AgentWorkItemMapper workItemMapper;
    private final AgentOutboundMessageMapper outboundMapper;
    private final AgentEventMapper eventMapper;
    private final AgentThreadMapper threadMapper;
    private final AgentThreadSubscriptionMapper subscriptionMapper;
    private final WorkItemCardProjector cardProjector;

    @Value("${buukle.agent.super-agent.reply.im-max-words:100}")
    private int imMaxWords;

    public AgentRouterToolResult dispatch(Long sessionId, Long runId, String goal, Integer priority) {
        RouterContext ctx = resolve(sessionId);
        if (ctx == null) {
            return AgentRouterToolResult.failed("router session not found");
        }
        if (goal == null || goal.isBlank()) {
            return AgentRouterToolResult.failed("goal is required");
        }
        AgentWorkItem item = new AgentWorkItem();
        item.setOwnerUserId(ctx.agent.getOwnerUserId());
        item.setSuperAgentId(ctx.agent.getId());
        item.setThreadId(currentThreadId(ctx.session, runId));
        item.setAttempt(1);
        item.setTitle(truncate(goal));
        item.setGoal(goal);
        item.setPriority(priority == null ? 100 : priority);
        item.setStatus(WorkItemStatus.QUEUED);
        item.setCreatedBy(ctx.ownerUsername);
        item.setActorType(ActorType.AGENT);
        item.setActorId(ctx.agent.getId());
        workItemMapper.insert(item);
        cardProjector.projectQueued(item);
        TenantContext.clear();
        return AgentRouterToolResult.ok("queued", item.getId());
    }

    public AgentRouterToolResult sendMessage(Long sessionId, Long runId, String callId,
                                             String text, String format,
                                             String cardType, String cardTitle,
                                             String cardStatus, String cardSummary,
                                             Long workItemId) {
        RouterContext ctx = resolve(sessionId);
        if (ctx == null) {
            return AgentRouterToolResult.failed("router session not found");
        }
        if (text == null || text.isBlank()) {
            TenantContext.clear();
            return AgentRouterToolResult.failed("text is required");
        }
        text = limitReply(text);
        String normalizedFormat = OutboundFormat.CARD.equalsIgnoreCase(format)
                ? OutboundFormat.CARD : OutboundFormat.TEXT;
        String payloadText = text;
        if (OutboundFormat.CARD.equals(normalizedFormat)) {
            TaskCardPayload card = new TaskCardPayload();
            card.setCardType(cardType == null ? TaskCardType.TASK : cardType);
            card.setTitle(cardTitle);
            card.setStatus(cardStatus);
            card.setSummary(cardSummary == null ? text : cardSummary);
            card.setSuperAgentId(ctx.agent.getId());
            card.setWorkItemId(workItemId);
            payloadText = JsonUtils.toJson(card);
        }
        AgentOutboundMessage outbound = new AgentOutboundMessage();
        outbound.setSuperAgentId(ctx.agent.getId());
        outbound.setThreadId(currentThreadId(ctx.session, runId));
        outbound.setIdempotencyKey("router-" + sessionId + "-" + runId + "-" + callId);
        AgentOutboundPayloadVO payload = new AgentOutboundPayloadVO();
        payload.setText(payloadText);
        payload.setFormat(normalizedFormat);
        outbound.setPayload(JsonUtils.toJson(payload));
        outbound.setStatus(AgentOutboundMessageStatus.PENDING);
        outbound.setAttempts(0);
        outbound.setCreatedBy(ctx.ownerUsername);
        outbound.setActorType(ActorType.AGENT);
        outbound.setActorId(ctx.agent.getId());
        outboundMapper.insert(outbound);
        TenantContext.clear();
        return AgentRouterToolResult.ok("queued", outbound.getId());
    }

    public AgentRouterToolResult recall(Long sessionId, Long runId, Long eventId) {
        if (eventId == null) {
            return AgentRouterToolResult.failed("eventId is required");
        }
        AgentEvent event = eventMapper.selectById(eventId);
        if (event == null) {
            return AgentRouterToolResult.failed("event not found");
        }
        RouterContext ctx = resolve(sessionId);
        if (ctx == null) {
            return AgentRouterToolResult.failed("router session not found");
        }
        Long threadId = currentThreadId(ctx.session, runId);
        if (threadId != null && event.getThreadId() != null
                && !threadId.equals(event.getThreadId())) {
            TenantContext.clear();
            return AgentRouterToolResult.failed("event does not belong to current thread");
        }
        try {
            JsonNode root = JsonUtils.getMapper().readTree(event.getPayload());
            JsonNode text = root == null ? null : root.get("text");
            TenantContext.clear();
            return AgentRouterToolResult.ok(text == null ? event.getPayload() : text.asText(), eventId);
        } catch (Exception e) {
            TenantContext.clear();
            return AgentRouterToolResult.ok(event.getPayload(), eventId);
        }
    }

    public AgentRouterToolResult subscribeThread(Long sessionId, Long runId, String mode) {
        RouterContext ctx = resolve(sessionId);
        if (ctx == null) {
            return AgentRouterToolResult.failed("router session not found");
        }
        AgentThreadSubscription subscription = new AgentThreadSubscription();
        subscription.setThreadId(currentThreadId(ctx.session, runId));
        subscription.setSuperAgentId(ctx.agent.getId());
        subscription.setMode(mode == null || mode.isBlank()
                ? AgentThreadSubscriptionMode.PASS_THROUGH : mode);
        subscription.setStatus(AgentThreadSubscriptionStatus.ACTIVE);
        subscription.setCreatedBy(ctx.ownerUsername);
        try {
            subscriptionMapper.insert(subscription);
            TenantContext.clear();
            return AgentRouterToolResult.ok("subscribed", subscription.getId());
        } catch (DuplicateKeyException e) {
            TenantContext.clear();
            return AgentRouterToolResult.ok("already subscribed", null);
        }
    }

    public AgentRouterToolResult closeThread(Long sessionId, Long runId, String reason) {
        RouterContext ctx = resolve(sessionId);
        if (ctx == null) {
            return AgentRouterToolResult.failed("router session not found");
        }
        AgentThread thread = threadMapper.selectById(currentThreadId(ctx.session, runId));
        if (thread == null) {
            return AgentRouterToolResult.failed("thread not found");
        }
        thread.setState(AgentThreadState.CLOSED);
        thread.setRemark(truncateReason(reason));
        threadMapper.updateById(thread);
        TenantContext.clear();
        return AgentRouterToolResult.ok("closed", thread.getId());
    }

    private Long currentThreadId(SessionVO session, Long runId) {
        if (runId != null) {
            try {
                RunVO run = runSpi.getRun(runId);
                if (run != null && run.getThreadId() != null) {
                    return run.getThreadId();
                }
            } catch (Exception e) {
                log.debug("Run thread lookup failed: runId={}", runId);
            }
        }
        return session.getThreadId();
    }

    private RouterContext resolve(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        SessionVO session = sessionSpi.getSession(sessionId);
        if (session == null || !SessionType.ROUTER.equals(session.getSessionType())
                || session.getSuperAgentId() == null) {
            return null;
        }
        AgentSuperAgent agent = superAgentMapper.selectById(session.getSuperAgentId());
        if (agent == null) {
            return null;
        }
        TenantContext.set(agent.getTenantId());
        return new RouterContext(session, agent, ownerUsername(agent.getOwnerUserId()));
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

    private String limitReply(String text) {
        int maxChars = Math.max(1, imMaxWords) * 4;
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private static String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "Untitled task";
        }
        return text.length() <= MAX_TITLE ? text : text.substring(0, MAX_TITLE);
    }

    private static String truncateReason(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_TITLE ? reason : reason.substring(0, MAX_TITLE);
    }

    private record RouterContext(SessionVO session, AgentSuperAgent agent, String ownerUsername) {
    }
}
