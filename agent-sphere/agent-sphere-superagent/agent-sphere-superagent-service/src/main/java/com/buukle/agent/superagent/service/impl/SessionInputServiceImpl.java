package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantScopeHolder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.channel.spi.OutboundFormat;
import com.buukle.agent.channel.spi.TaskCardPayload;
import com.buukle.agent.channel.spi.TaskCardType;
import com.buukle.agent.common.constant.ActorType;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentOutboundMessage;
import com.buukle.agent.superagent.domain.AgentSessionInput;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.enums.AgentOutboundMessageStatus;
import com.buukle.agent.superagent.dtvo.vo.AgentOutboundPayloadVO;
import com.buukle.agent.superagent.dtvo.vo.SessionInputVO;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentOutboundMessageMapper;
import com.buukle.agent.superagent.repository.AgentSessionInputMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.event.AgentOutboundWakePublisher;
import com.buukle.agent.superagent.spi.RouterInputResumePort;
import com.buukle.agent.superagent.spi.SessionInputCommand;
import com.buukle.agent.superagent.spi.SessionInputResolveCommand;
import com.buukle.agent.superagent.spi.SessionInputSpi;
import com.buukle.agent.superagent.spi.SessionInputStatus;
import com.buukle.agent.superagent.spi.SessionInputType;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Projects Router parked inputs (clarification / L2 tool approval) into the IM DM.
 *
 * <p>PENDING creates an {@code agent_session_input} row plus an authoritative
 * CARD message in the DM via the outbox. Plain-text replies to the DM consume a
 * pending clarification and resume the parked run through
 * {@link RouterInputResumePort}; approval decisions go through the same port.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionInputServiceImpl implements SessionInputSpi {

    private static final int MAX_TITLE = 500;

    private final AgentSessionInputMapper sessionInputMapper;
    private final AgentEventMapper eventMapper;
    private final AgentOutboundMessageMapper outboundMapper;
    private final AgentOutboundWakePublisher outboundWakePublisher;
    private final AgentSuperAgentMapper superAgentMapper;
    private final UserSpi userSpi;
    private final AgentMetrics metrics;
    private final ObjectProvider<RouterInputResumePort> resumePortProvider;

    @Override
    public void projectPending(SessionInputCommand command) {
        if (command == null || !validForProjection(command)) {
            return;
        }
        AgentSuperAgent agent = superAgentMapper.selectById(command.superAgentId());
        if (agent == null) {
            log.warn("Router input projection skipped: agent missing, superAgentId={}",
                    command.superAgentId());
            return;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        try {
            TenantContext.set(agent.getTenantId());
            String owner = ownerUsername(agent.getOwnerUserId());
            AgentSessionInput input = new AgentSessionInput();
            input.setSuperAgentId(command.superAgentId());
            input.setThreadId(command.threadId());
            input.setSessionId(command.sessionId());
            input.setRunId(command.runId());
            input.setInputType(command.inputType());
            input.setInputId(command.inputId());
            input.setTitle(truncate(command.title(), MAX_TITLE));
            input.setDescription(command.description());
            input.setOptions(command.optionsJson());
            input.setMultiple(command.multiple() != null && command.multiple());
            input.setToolCallsJson(command.toolCallsJson());
            input.setStatus(SessionInputStatus.PENDING);
            input.setRequestedBy(latestActorId(command.threadId()));
            input.setCreatedBy(owner);
            try {
                sessionInputMapper.insert(input);
                metrics.increment(AgentMetrics.ROUTER_INPUT_PROJECTED, "inputType", command.inputType());
            } catch (DuplicateKeyException ignored) {
                // Error-type/input-type already projected: keep the first row.
                return;
            }
            enqueueCard(input, owner);
        } catch (Exception e) {
            log.warn("Router input projection failed: inputId={}, error={}", command.inputId(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    public void projectResolved(SessionInputResolveCommand command) {
        if (command == null || command.inputType() == null || command.inputId() == null) {
            return;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        try {
            AgentSessionInput input = sessionInputMapper.selectOne(Wrappers.<AgentSessionInput>lambdaQuery()
                    .eq(AgentSessionInput::getInputType, command.inputType())
                    .eq(AgentSessionInput::getInputId, command.inputId())
                    .eq(AgentSessionInput::getStatus, SessionInputStatus.PENDING)
                    .last(LIMIT_ONE));
            if (input == null) {
                return;
            }
            // The card-update outbound row must carry the input's tenant; otherwise the
            // audit handler stamps SYSTEM (0) and delivery cannot resolve the conversation.
            if (input.getTenantId() != null) {
                TenantContext.set(input.getTenantId());
            }
            markResolved(input, command.status());
            updateCard(input, ownerUsername(resolveOwner(input)));
        } catch (Exception e) {
            log.warn("Router input resolution failed: inputId={}, error={}", command.inputId(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    public void projectRunTerminal(Long runId) {
        if (runId == null) {
            return;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        try {
            var pending = sessionInputMapper.selectList(Wrappers.<AgentSessionInput>lambdaQuery()
                    .eq(AgentSessionInput::getRunId, runId)
                    .eq(AgentSessionInput::getStatus, SessionInputStatus.PENDING));
            for (AgentSessionInput input : pending) {
                if (input.getTenantId() != null) {
                    TenantContext.set(input.getTenantId());
                }
                markResolved(input, SessionInputStatus.CANCELLED);
                updateCard(input, ownerUsername(resolveOwner(input)));
                metrics.increment(AgentMetrics.ROUTER_INPUT_CANCELLED, "inputType", input.getInputType());
            }
        } catch (Exception e) {
            log.warn("Router input run-terminal cleanup failed: runId={}, error={}", runId, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    public SessionInputVO findPendingByThread(Long threadId, String inputType) {
        if (threadId == null) {
            return null;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        try {
            AgentSessionInput input = sessionInputMapper.selectOne(Wrappers.<AgentSessionInput>lambdaQuery()
                    .eq(AgentSessionInput::getThreadId, threadId)
                    .eq(AgentSessionInput::getInputType, inputType)
                    .eq(AgentSessionInput::getStatus, SessionInputStatus.PENDING)
                    .orderByDesc(AgentSessionInput::getId)
                    .last(LIMIT_ONE));
            return input == null ? null : toVO(input);
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    public SessionInputVO findPendingByInput(Long threadId, String inputId) {
        if (threadId == null || inputId == null || inputId.isBlank()) {
            return null;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
        try {
            AgentSessionInput input = selectPending(threadId, inputId, null);
            return input == null ? null : toVO(input);
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    public SessionInputVO findPendingByThreadAndAgent(Long threadId, Long superAgentId) {
        if (threadId == null || superAgentId == null) {
            return null;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
        try {
            AgentSessionInput input = selectPending(threadId, null, superAgentId);
            return input == null ? null : toVO(input);
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    public List<SessionInputVO> findPendingClarifications(Long threadId) {
        if (threadId == null) {
            return List.of();
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
        try {
            return sessionInputMapper.selectList(Wrappers.<AgentSessionInput>lambdaQuery()
                            .eq(AgentSessionInput::getThreadId, threadId)
                            .eq(AgentSessionInput::getInputType, SessionInputType.CLARIFICATION)
                            .eq(AgentSessionInput::getStatus, SessionInputStatus.PENDING)
                            .orderByAsc(AgentSessionInput::getId))
                    .stream()
                    .map(SessionInputServiceImpl::toVO)
                    .toList();
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    public boolean answerRouterClarification(Long threadId, String response) {
        return answerRouterClarification(threadId, null, response, AuthContext.getUsername());
    }

    @Override
    public boolean answerRouterClarification(
            Long threadId, String inputId, String response, String responder) {
        if (threadId == null || response == null || response.isBlank()) {
            return false;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
        try {
            AgentSessionInput input = selectPending(threadId, inputId, null);
            if (input == null) {
                return false;
            }
            RouterInputResumePort port = resumePortProvider.getIfAvailable();
            if (port == null) {
                return false;
            }
            // Persist the answer before resuming: the synchronous RESPONDED event
            // re-projects the card and must already see it.
            String answerJson = JsonUtils.toJson(Map.of("answer", response));
            AgentSessionInput answerPatch = new AgentSessionInput();
            answerPatch.setId(input.getId());
            answerPatch.setDecisionsJson(answerJson);
            if (responder != null && !responder.isBlank()) {
                answerPatch.setRespondedBy(responder);
            }
            sessionInputMapper.updateById(answerPatch);
            input.setDecisionsJson(answerJson);
            if (responder != null && !responder.isBlank()) {
                input.setRespondedBy(responder);
            }
            port.answerClarification(input.getSessionId(), input.getRunId(), input.getInputId(), response);
            // The resume path publishes the RESPONDED event synchronously, and the
            // bridge's projectResolved already marked the row + updated the card.
            // Re-resolving here would enqueue a duplicate ANSWERED card outbound,
            // whose duplicate-key statement aborts the caller's transaction —
            // PostgreSQL then turns the commit into a silent rollback (the 200
            // response still returns while every write in the request vanishes).
            AgentSessionInput current = sessionInputMapper.selectById(input.getId());
            if (current == null
                    || !SessionInputStatus.PENDING.equals(current.getStatus())) {
                metrics.increment(AgentMetrics.ROUTER_INPUT_ANSWERED,
                        "inputType", SessionInputType.CLARIFICATION);
                return true;
            }
            markResolved(input, SessionInputStatus.ANSWERED, responder);
            updateCard(input, null);
            metrics.increment(AgentMetrics.ROUTER_INPUT_ANSWERED, "inputType", SessionInputType.CLARIFICATION);
            return true;
        } catch (Exception e) {
            log.warn("Router clarification answer failed: threadId={}, inputId={}, error={}",
                    threadId, inputId, e.getMessage());
            return false;
        }
    }

    private AgentSessionInput selectPending(Long threadId, String inputId, Long superAgentId) {
        return sessionInputMapper.selectOne(Wrappers.<AgentSessionInput>lambdaQuery()
                .eq(AgentSessionInput::getThreadId, threadId)
                .eq(AgentSessionInput::getInputType, SessionInputType.CLARIFICATION)
                .eq(AgentSessionInput::getStatus, SessionInputStatus.PENDING)
                .eq(inputId != null && !inputId.isBlank(), AgentSessionInput::getInputId, inputId)
                .eq(superAgentId != null, AgentSessionInput::getSuperAgentId, superAgentId)
                .orderByDesc(AgentSessionInput::getId)
                .last(LIMIT_ONE));
    }

    private Long latestActorId(Long threadId) {
        if (threadId == null) {
            return null;
        }
        AgentEvent event = eventMapper.selectOne(Wrappers.<AgentEvent>lambdaQuery()
                .eq(AgentEvent::getThreadId, threadId)
                .orderByDesc(AgentEvent::getSeq)
                .last(LIMIT_ONE));
        return event == null ? null : event.getActorId();
    }

    @Override
    public void resolveRouterApproval(Long threadId, String inputId, String decisionsJson) {
        if (threadId == null || inputId == null || inputId.isBlank()
                || decisionsJson == null || decisionsJson.isBlank()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "inputId and decisions are required");
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
        try {
            AgentSessionInput input = sessionInputMapper.selectOne(Wrappers.<AgentSessionInput>lambdaQuery()
                    .eq(AgentSessionInput::getThreadId, threadId)
                    .eq(AgentSessionInput::getInputType, SessionInputType.APPROVAL)
                    .eq(AgentSessionInput::getInputId, inputId)
                    .eq(AgentSessionInput::getStatus, SessionInputStatus.PENDING)
                    .last(LIMIT_ONE));
            if (input == null) {
                throw new BizException(CommonErrorCode.PARAM_INVALID, "Pending approval not found");
            }
            // Approval cards are visible to the whole group, but only the avatar
            // owner (or a platform super admin) may authorize the tool calls.
            if (input.getTenantId() != null) {
                TenantContext.set(input.getTenantId());
            }
            AgentSuperAgent owner = superAgentMapper.selectById(input.getSuperAgentId());
            if (owner == null
                    || (!AuthContext.isSuperAdmin()
                        && !java.util.Objects.equals(AuthContext.getUserId(), owner.getOwnerUserId()))) {
                throw new BizException(CommonErrorCode.FORBIDDEN);
            }
            RouterInputResumePort port = resumePortProvider.getIfAvailable();
            if (port == null) {
                throw new BizException(CommonErrorCode.PARAM_INVALID, "Approval resume is unavailable");
            }
            // Persist the decisions before resuming: the synchronous RESPONDED event
            // re-projects the card and must already see which option was chosen.
            AgentSessionInput decisionPatch = new AgentSessionInput();
            decisionPatch.setId(input.getId());
            decisionPatch.setDecisionsJson(decisionsJson);
            sessionInputMapper.updateById(decisionPatch);
            input.setDecisionsJson(decisionsJson);
            port.resolveApproval(input.getSessionId(), input.getRunId(), inputId,
                    decisionsJson, AuthContext.getUsername());
            // Mirror answerRouterClarification: the resume path already resolved the
            // synchronously published event; a duplicate ANSWERED card outbound would
            // abort the caller's transaction via the swallowed duplicate key.
            AgentSessionInput current = sessionInputMapper.selectById(input.getId());
            if (current == null
                    || !SessionInputStatus.PENDING.equals(current.getStatus())) {
                metrics.increment(AgentMetrics.ROUTER_INPUT_ANSWERED,
                        "inputType", SessionInputType.APPROVAL);
                return;
            }
            markResolved(input, SessionInputStatus.ANSWERED, null);
            updateCard(input, null);
            metrics.increment(AgentMetrics.ROUTER_INPUT_ANSWERED, "inputType", SessionInputType.APPROVAL);
        } finally {
            TenantContext.clear();
        }
    }

    // ------------------------------------------------------------------

    private void enqueueCard(AgentSessionInput input, String owner) {
        AgentSuperAgent agent = superAgentMapper.selectById(input.getSuperAgentId());
        if (agent == null) {
            return;
        }
        Long outboundId = null;
        try {
            AgentOutboundMessage outbound = new AgentOutboundMessage();
            outbound.setSuperAgentId(agent.getId());
            outbound.setThreadId(input.getThreadId());
            outbound.setIdempotencyKey(cardKey(input));
            // Same check-first guard as updateCard: never trip the idempotency
            // unique index inside a caller's transaction.
            AgentOutboundMessage existing = outboundMapper.selectOne(
                    Wrappers.<AgentOutboundMessage>lambdaQuery()
                            .eq(AgentOutboundMessage::getIdempotencyKey, outbound.getIdempotencyKey())
                            .last(LIMIT_ONE));
            if (existing != null) {
                return;
            }
            AgentOutboundPayloadVO payload = new AgentOutboundPayloadVO();
            payload.setText(JsonUtils.toJson(cardPayload(input, input.getStatus(), agent.getId())));
            payload.setFormat(OutboundFormat.CARD);
            outbound.setPayload(JsonUtils.toJson(payload));
            outbound.setStatus(AgentOutboundMessageStatus.PENDING);
            outbound.setAttempts(0);
            outbound.setCreatedBy(owner);
            outbound.setActorType(ActorType.AGENT);
            outbound.setActorId(agent.getId());
            outboundMapper.insert(outbound);
            outboundId = outbound.getId();
            outboundWakePublisher.publish(outbound.getId());
        } catch (DuplicateKeyException ignored) {
            // Card already queued.
        } catch (Exception e) {
            metrics.increment(AgentMetrics.ROUTER_INPUT_CARD_FAILED, "inputType", input.getInputType());
            log.warn("Router input card enqueue failed: inputId={}, error={}", input.getInputId(), e.getMessage());
        } finally {
            if (outboundId != null) {
                AgentSessionInput patch = new AgentSessionInput();
                patch.setId(input.getId());
                patch.setCardOutboundId(outboundId);
                sessionInputMapper.updateById(patch);
                input.setCardOutboundId(outboundId);
            }
            TenantContext.clear();
        }
    }

    /** Re-enqueues the card with the new status so the DM keeps a truthful record. */
    private void updateCard(AgentSessionInput input, String owner) {
        if (input.getCardOutboundId() == null) {
            return;
        }
        AgentOutboundMessage cardOutbound = outboundMapper.selectById(input.getCardOutboundId());
        if (cardOutbound == null || cardOutbound.getExternalMessageId() == null) {
            return;
        }
        Long cardMessageId;
        try {
            cardMessageId = Long.valueOf(cardOutbound.getExternalMessageId());
        } catch (NumberFormatException e) {
            return;
        }
        try {
            AgentSessionInput current = sessionInputMapper.selectById(input.getId());
            if (current == null) {
                return;
            }
            if (current.getTenantId() != null) {
                TenantContext.set(current.getTenantId());
            }
            Long threadId = current.getThreadId();
            AgentSuperAgent agent = superAgentMapper.selectById(current.getSuperAgentId());
            if (agent == null) {
                return;
            }
            AgentOutboundMessage outbound = new AgentOutboundMessage();
            outbound.setSuperAgentId(agent.getId());
            outbound.setThreadId(threadId);
            outbound.setIdempotencyKey(cardKey(current) + "-" + current.getStatus());
            // Check-first: re-running updateCard in the same transaction must not
            // hit the idempotency unique index — a swallowed DuplicateKeyException
            // aborts the PostgreSQL transaction and silently rolls back the commit.
            AgentOutboundMessage existing = outboundMapper.selectOne(
                    Wrappers.<AgentOutboundMessage>lambdaQuery()
                            .eq(AgentOutboundMessage::getIdempotencyKey, outbound.getIdempotencyKey())
                            .last(LIMIT_ONE));
            if (existing != null) {
                return;
            }
            AgentOutboundPayloadVO payload = new AgentOutboundPayloadVO();
            payload.setText(JsonUtils.toJson(cardPayload(current, current.getStatus(), agent.getId())));
            payload.setFormat(OutboundFormat.CARD);
            payload.setTargetMessageId(cardMessageId);
            outbound.setPayload(JsonUtils.toJson(payload));
            outbound.setStatus(AgentOutboundMessageStatus.PENDING);
            outbound.setAttempts(0);
            outbound.setCreatedBy(owner);
            outbound.setActorType(ActorType.AGENT);
            outbound.setActorId(agent.getId());
            outboundMapper.insert(outbound);
            outboundWakePublisher.publish(outbound.getId());
        } catch (DuplicateKeyException ignored) {
            // Status update already projected.
        } catch (Exception e) {
            metrics.increment(AgentMetrics.ROUTER_INPUT_CARD_FAILED, "inputType", input.getInputType());
            log.warn("Router input card update failed: inputId={}, error={}", input.getInputId(), e.getMessage());
        }
    }

    private void markResolved(AgentSessionInput input, String status) {
        markResolved(input, status, null);
    }

    private void markResolved(AgentSessionInput input, String status, String respondedBy) {
        AgentSessionInput patch = new AgentSessionInput();
        patch.setId(input.getId());
        patch.setStatus(status);
        patch.setRespondedBy(respondedBy);
        patch.setRespondedAt(LocalDateTime.now());
        sessionInputMapper.updateById(patch);
        input.setStatus(status);
    }

    private TaskCardPayload cardPayload(AgentSessionInput input, String status, Long superAgentId) {
        TaskCardPayload card = new TaskCardPayload();
        card.setCardType(cardTypeOf(input.getInputType()));
        card.setTitle(input.getTitle());
        card.setStatus(status);
        card.setSummary(input.getDescription() == null || input.getDescription().isBlank()
                ? input.getTitle() : input.getDescription());
        card.setSuperAgentId(superAgentId);
        card.setInputType(input.getInputType());
        card.setInputId(input.getInputId());
        card.setSessionId(input.getSessionId());
        card.setRunId(input.getRunId());
        card.setDescription(input.getDescription());
        card.setMultiple(input.getMultiple());
        card.setOptions(isClarification(input) ? input.getOptions() : null);
        card.setToolCallsJson(isApproval(input) ? input.getToolCallsJson() : null);
        card.setDecisionsJson(input.getDecisionsJson());
        card.setRespondedBy(input.getRespondedBy());
        return card;
    }

    private static String cardTypeOf(String inputType) {
        return SessionInputType.APPROVAL.equals(inputType)
                ? TaskCardType.ROUTER_APPROVAL : TaskCardType.ROUTER_CLARIFICATION;
    }

    private static String cardKey(AgentSessionInput input) {
        return "router-input-" + input.getInputType().toLowerCase() + "-" + input.getInputId();
    }

    private static boolean validForProjection(SessionInputCommand command) {
        return command.superAgentId() != null && command.threadId() != null
                && command.sessionId() != null && command.runId() != null
                && command.inputId() != null && !command.inputId().isBlank()
                && (SessionInputType.CLARIFICATION.equals(command.inputType())
                || SessionInputType.APPROVAL.equals(command.inputType()));
    }

    private Long resolveOwner(AgentSessionInput input) {
        AgentSuperAgent agent = superAgentMapper.selectById(input.getSuperAgentId());
        return agent == null ? null : agent.getOwnerUserId();
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

    private static SessionInputVO toVO(AgentSessionInput input) {
        SessionInputVO vo = new SessionInputVO();
        vo.setId(input.getId());
        vo.setSuperAgentId(input.getSuperAgentId());
        vo.setThreadId(input.getThreadId());
        vo.setSessionId(input.getSessionId());
        vo.setRunId(input.getRunId());
        vo.setInputType(input.getInputType());
        vo.setInputId(input.getInputId());
        vo.setTitle(input.getTitle());
        vo.setDescription(input.getDescription());
        vo.setOptions(input.getOptions());
        vo.setMultiple(input.getMultiple());
        vo.setToolCallsJson(input.getToolCallsJson());
        vo.setStatus(input.getStatus());
        vo.setRequestedBy(input.getRequestedBy());
        return vo;
    }

    private boolean isClarification(AgentSessionInput input) {
        return SessionInputType.CLARIFICATION.equals(input.getInputType());
    }

    private boolean isApproval(AgentSessionInput input) {
        return SessionInputType.APPROVAL.equals(input.getInputType());
    }

    private static final String LIMIT_ONE = "LIMIT 1";

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
