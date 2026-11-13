package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.channel.spi.AgentStreamEvent;
import com.buukle.agent.channel.spi.AgentStreamPhase;
import com.buukle.agent.channel.spi.AgentStreamPort;
import com.buukle.agent.channel.spi.AgentStreamStatus;
import com.buukle.agent.channel.spi.OutboundFormat;
import com.buukle.agent.common.constant.ActorType;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentLocalSession;
import com.buukle.agent.superagent.domain.AgentLocalTask;
import com.buukle.agent.superagent.domain.AgentOutboundMessage;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.dto.LocalTaskChunkDTO;
import com.buukle.agent.superagent.dtvo.dto.LocalTaskCompleteDTO;
import com.buukle.agent.superagent.dtvo.dto.LocalTaskFailDTO;
import com.buukle.agent.superagent.dtvo.enums.AgentOutboundMessageStatus;
import com.buukle.agent.superagent.dtvo.enums.LocalSessionStatus;
import com.buukle.agent.superagent.dtvo.enums.LocalTaskStatus;
import com.buukle.agent.superagent.dtvo.vo.AgentOutboundPayloadVO;
import com.buukle.agent.superagent.dtvo.vo.LocalTaskAckVO;
import com.buukle.agent.superagent.dtvo.vo.LocalTaskPayloadVO;
import com.buukle.agent.superagent.dtvo.vo.LocalTaskVO;
import com.buukle.agent.superagent.repository.AgentLocalSessionMapper;
import com.buukle.agent.superagent.repository.AgentLocalTaskDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentLocalTaskMapper;
import com.buukle.agent.superagent.repository.AgentOutboundMessageMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.LocalTaskService;
import com.buukle.agent.superagent.service.event.AgentOutboundWakePublisher;
import com.buukle.agent.superagent.spi.LocalTaskBusinessTypes;
import com.buukle.agent.superagent.spi.ProjectMentionTaskCompletedEvent;
import com.buukle.agent.superagent.spi.ProjectMentionTaskFailedEvent;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalTaskServiceImpl implements LocalTaskService {

    private static final String IDEMPOTENCY_PREFIX = "local-";
    private static final String ERROR_CODE_CANCELLED = "CANCELLED";
    private static final int MAX_REPLY_CHARS = 8_000;
    private static final int MAX_ERROR_CHARS = 1_000;

    private final AgentLocalTaskMapper taskMapper;
    private final AgentLocalTaskDeliveryMapper taskDeliveryMapper;
    private final AgentLocalSessionMapper sessionMapper;
    private final AgentSuperAgentMapper superAgentMapper;
    private final AgentOutboundMessageMapper outboundMapper;
    private final AgentOutboundWakePublisher outboundWakePublisher;
    private final UserSpi userSpi;
    private final ObjectProvider<AgentStreamPort> streamPortProvider;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${buukle.agent.super-agent.local.task-timeout-seconds:600}")
    private long taskTimeoutSeconds;

    @Override
    @Transactional
    public LocalTaskAckVO ack(Long runtimeId, Long taskId) {
        AgentLocalTask task = requireTask(runtimeId, taskId);
        if (LocalTaskStatus.SUCCEEDED.equals(task.getStatus())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "task already completed");
        }
        if (!LocalTaskStatus.PENDING.equals(task.getStatus())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "task is not pending");
        }

        String leaseToken = UUID.randomUUID().toString();
        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(Math.max(30, taskTimeoutSeconds));
        int updated = taskMapper.claim(taskId, runtimeId, leaseToken, leaseUntil);
        if (updated == 0) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "task already claimed");
        }

        task.setStatus(LocalTaskStatus.RUNNING);
        task.setLeaseToken(leaseToken);
        task.setLeaseUntil(leaseUntil);
        task.setStartedAt(LocalDateTime.now());

        LocalTaskAckVO vo = new LocalTaskAckVO();
        vo.setTask(payload(task));
        vo.setLeaseToken(leaseToken);
        return vo;
    }

    @Override
    public void chunk(Long runtimeId, Long taskId, LocalTaskChunkDTO dto) {
        AgentLocalTask task = requireLease(runtimeId, taskId, dto.getLeaseToken());
        String delta = dto.getDelta();
        if (delta == null || delta.isEmpty()) return;

        String phase = AgentStreamPhase.THINKING.equalsIgnoreCase(dto.getPhase())
                ? AgentStreamPhase.THINKING : AgentStreamPhase.REPLY;
        publishStream(task, phase, delta, null, null);
        taskMapper.touchSeq(taskId, runtimeId, dto.getSeq() == null ? 0L : dto.getSeq());
    }

    @Override
    @Transactional
    public void complete(Long runtimeId, Long taskId, LocalTaskCompleteDTO dto) {
        AgentLocalTask task = requireTask(runtimeId, taskId);
        if (LocalTaskStatus.SUCCEEDED.equals(task.getStatus())) {
            return;
        }
        requireLease(task, dto.getLeaseToken());
        String text = dto.getText() == null ? "" : dto.getText().trim();
        if (text.isEmpty()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "reply text is required");
        }
        if (text.length() > MAX_REPLY_CHARS) {
            text = text.substring(0, MAX_REPLY_CHARS);
        }

        AgentSuperAgent agent = superAgentMapper.selectById(task.getSuperAgentId());
        if (agent == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "avatar not found");
        }
        boolean projectMention = LocalTaskBusinessTypes.PROJECT_MENTION
                .equals(task.getBusinessType());
        AgentOutboundMessage outbound = projectMention ? null : enqueueReply(task, agent, text);
        int updated = taskMapper.complete(taskId, runtimeId, dto.getLeaseToken(),
                dto.getSessionRef(), outbound == null ? null : outbound.getId());
        if (updated == 0) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "task lease lost");
        }
        taskDeliveryMapper.markProcessed(taskId);
        upsertSession(task, dto.getSessionRef());
        publishStream(task, AgentStreamPhase.STATUS, "", AgentStreamStatus.COMPLETED, null);
        if (projectMention && task.getBusinessId() != null) {
            eventPublisher.publishEvent(new ProjectMentionTaskCompletedEvent(
                    task.getTenantId(), task.getId(), task.getBusinessId(),
                    agent.getId(), agent.getName(), text));
        }
    }

    @Override
    @Transactional
    public void fail(Long runtimeId, Long taskId, LocalTaskFailDTO dto) {
        AgentLocalTask task = requireTask(runtimeId, taskId);
        if (isTerminal(task.getStatus())) {
            return;
        }

        boolean cancelled = ERROR_CODE_CANCELLED.equalsIgnoreCase(dto.getErrorCode());
        String status = cancelled ? LocalTaskStatus.CANCELLED : LocalTaskStatus.FAILED;
        String errorCode = dto.getErrorCode() == null || dto.getErrorCode().isBlank()
                ? (cancelled ? ERROR_CODE_CANCELLED : "LOCAL_TASK_FAILED")
                : dto.getErrorCode();
        String errorMessage = truncate(dto.getErrorMessage(), MAX_ERROR_CHARS);

        taskMapper.fail(taskId, runtimeId, status, errorCode, errorMessage);
        taskDeliveryMapper.markFailed(taskId, truncate(errorCode + ": " + errorMessage, 500));
        publishStream(task, AgentStreamPhase.STATUS, "",
                cancelled ? AgentStreamStatus.CANCELLED : AgentStreamStatus.FAILED, errorMessage);
        if (LocalTaskBusinessTypes.PROJECT_MENTION.equals(task.getBusinessType())
                && task.getBusinessId() != null) {
            eventPublisher.publishEvent(new ProjectMentionTaskFailedEvent(
                    task.getTenantId(), task.getId(), task.getBusinessId(),
                    task.getSuperAgentId(),
                    errorMessage == null || errorMessage.isBlank() ? errorCode : errorMessage));
        }
    }

    @Override
    public void cancel(Long runtimeId, Long taskId) {
        LocalTaskFailDTO dto = new LocalTaskFailDTO();
        dto.setErrorCode(ERROR_CODE_CANCELLED);
        dto.setErrorMessage("cancelled by user");
        fail(runtimeId, taskId, dto);
    }

    @Override
    public List<LocalTaskVO> list(Long runtimeId, List<String> statuses) {
        var query = Wrappers.<AgentLocalTask>lambdaQuery()
                .eq(AgentLocalTask::getRuntimeId, runtimeId)
                .orderByDesc(AgentLocalTask::getId)
                .last("LIMIT 100");
        if (statuses != null && !statuses.isEmpty()) {
            query.in(AgentLocalTask::getStatus, statuses);
        }
        return taskMapper.selectList(query).stream().map(this::toVO).toList();
    }

    private AgentLocalTask requireTask(Long runtimeId, Long taskId) {
        AgentLocalTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "task not found");
        }
        if (!Objects.equals(runtimeId, task.getRuntimeId())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        return task;
    }

    private AgentLocalTask requireLease(Long runtimeId, Long taskId, String leaseToken) {
        AgentLocalTask task = requireTask(runtimeId, taskId);
        requireLease(task, leaseToken);
        return task;
    }

    private void requireLease(AgentLocalTask task, String leaseToken) {
        if (!LocalTaskStatus.RUNNING.equals(task.getStatus())
                && !LocalTaskStatus.CLAIMED.equals(task.getStatus())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "task is not running");
        }
        if (leaseToken == null || !Objects.equals(leaseToken, task.getLeaseToken())) {
            throw new BizException(CommonErrorCode.FORBIDDEN, "task lease mismatch");
        }
    }

    private LocalTaskPayloadVO payload(AgentLocalTask task) {
        if (task.getInputPayload() != null && !task.getInputPayload().isBlank()) {
            try {
                LocalTaskPayloadVO parsed = JsonUtils.parse(
                        task.getInputPayload(), LocalTaskPayloadVO.class);
                if (parsed != null) {
                    parsed.setTaskId(task.getId());
                    if (parsed.getSessionRef() == null) {
                        parsed.setSessionRef(task.getSessionRef());
                    }
                    return parsed;
                }
            } catch (Exception e) {
                log.warn("Local task payload parse failed: task={}, error={}",
                        task.getId(), e.getMessage());
            }
        }

        LocalTaskPayloadVO vo = new LocalTaskPayloadVO();
        vo.setTaskId(task.getId());
        vo.setAgentId(task.getSuperAgentId());
        vo.setSessionRef(task.getSessionRef());
        LocalTaskPayloadVO.ThreadInfo thread = new LocalTaskPayloadVO.ThreadInfo();
        thread.setId(task.getThreadId());
        vo.setThread(thread);
        vo.setMessages(List.of());
        LocalTaskPayloadVO.Reply reply = new LocalTaskPayloadVO.Reply();
        reply.setMaxChars(4_000);
        reply.setFormat(OutboundFormat.TEXT);
        vo.setReply(reply);
        AgentSuperAgent agent = superAgentMapper.selectById(task.getSuperAgentId());
        if (agent != null) {
            vo.setAgentName(agent.getName());
            LocalTaskPayloadVO.Persona persona = new LocalTaskPayloadVO.Persona();
            persona.setPosition(agent.getPositionNameCn());
            persona.setDescription(agent.getDescription());
            vo.setPersona(persona);
        }
        return vo;
    }

    private AgentOutboundMessage enqueueReply(AgentLocalTask task, AgentSuperAgent agent,
                                              String text) {
        String idempotencyKey = IDEMPOTENCY_PREFIX + task.getId();
        AgentOutboundMessage existing = outboundMapper.selectOne(
                Wrappers.<AgentOutboundMessage>lambdaQuery()
                        .eq(AgentOutboundMessage::getIdempotencyKey, idempotencyKey)
                        .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }

        AgentOutboundMessage outbound = new AgentOutboundMessage();
        outbound.setSuperAgentId(task.getSuperAgentId());
        outbound.setThreadId(task.getThreadId());
        outbound.setIdempotencyKey(idempotencyKey);
        AgentOutboundPayloadVO payload = new AgentOutboundPayloadVO();
        payload.setText(text);
        payload.setFormat(OutboundFormat.TEXT);
        outbound.setPayload(JsonUtils.toJson(payload));
        outbound.setStatus(AgentOutboundMessageStatus.PENDING);
        outbound.setAttempts(0);
        outbound.setCreatedBy(ownerUsername(agent));
        outbound.setActorType(ActorType.AGENT);
        outbound.setActorId(task.getSuperAgentId());

        try {
            outboundMapper.insert(outbound);
            outboundWakePublisher.publish(outbound.getId());
            return outbound;
        } catch (DuplicateKeyException e) {
            return outboundMapper.selectOne(Wrappers.<AgentOutboundMessage>lambdaQuery()
                    .eq(AgentOutboundMessage::getIdempotencyKey, idempotencyKey)
                    .last("LIMIT 1"));
        }
    }

    private void upsertSession(AgentLocalTask task, String sessionRef) {
        if (sessionRef == null || sessionRef.isBlank()) return;
        AgentLocalSession session = sessionMapper.selectOne(
                Wrappers.<AgentLocalSession>lambdaQuery()
                        .eq(AgentLocalSession::getSuperAgentId, task.getSuperAgentId())
                        .eq(AgentLocalSession::getThreadId, task.getThreadId())
                        .last("LIMIT 1"));
        if (session == null) {
            session = new AgentLocalSession();
            session.setSuperAgentId(task.getSuperAgentId());
            session.setThreadId(task.getThreadId());
            session.setRuntimeId(task.getRuntimeId());
            session.setSessionRef(sessionRef);
            session.setLastSeq(task.getLastSeq());
            session.setStatus(LocalSessionStatus.ACTIVE);
            sessionMapper.insert(session);
        } else {
            session.setRuntimeId(task.getRuntimeId());
            session.setSessionRef(sessionRef);
            session.setLastSeq(task.getLastSeq());
            sessionMapper.updateById(session);
        }
    }

    private void publishStream(AgentLocalTask task, String phase, String delta,
                               String streamStatus, String errorMessage) {
        AgentStreamPort port = streamPortProvider.getIfAvailable();
        if (port == null) return;
        try {
            port.publish(new AgentStreamEvent(
                    task.getThreadId(),
                    null,
                    task.getId(),
                    task.getSuperAgentId(),
                    phase,
                    delta == null ? "" : delta,
                    streamStatus,
                    null,
                    null,
                    errorMessage,
                    task.getLastSeq(),
                    System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("Local task stream publish failed: task={}, error={}",
                    task.getId(), e.getMessage());
        }
    }

    private String ownerUsername(AgentSuperAgent agent) {
        if (agent.getOwnerUserId() == null) return null;
        try {
            UserVO owner = userSpi.getByUserId(agent.getOwnerUserId());
            return owner == null ? null : owner.getUsername();
        } catch (Exception e) {
            return null;
        }
    }

    private LocalTaskVO toVO(AgentLocalTask task) {
        LocalTaskVO vo = new LocalTaskVO();
        vo.setTaskId(task.getId());
        vo.setAgentId(task.getSuperAgentId());
        vo.setThreadId(task.getThreadId());
        vo.setStatus(task.getStatus());
        vo.setSessionRef(task.getSessionRef());
        vo.setErrorCode(task.getErrorCode());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setCreatedAt(task.getCreatedAt());
        vo.setStartedAt(task.getStartedAt());
        vo.setFinishedAt(task.getFinishedAt());
        return vo;
    }

    private static boolean isTerminal(String status) {
        return LocalTaskStatus.SUCCEEDED.equals(status)
                || LocalTaskStatus.FAILED.equals(status)
                || LocalTaskStatus.CANCELLED.equals(status)
                || LocalTaskStatus.EXPIRED.equals(status);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
