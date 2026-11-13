package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.domain.AgentPendingClarification;
import com.buukle.agent.instance.dtvo.vo.ClarificationVO;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.spi.ClarificationSpi;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClarificationService implements ClarificationSpi {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentPendingClarificationMapper clarificationMapper;

    @Override
    public Long createPending(Long sessionId, Long runId, Long messageId, String title, String type,
                              boolean multiple, String optionsJson, String description, String clarificationId) {
        return createPending(sessionId, runId, messageId, title, type, multiple, optionsJson,
                description, clarificationId, null, null);
    }

    @Override
    public Long createPending(Long sessionId, Long runId, Long messageId, String title, String type,
                              boolean multiple, String optionsJson, String description, String clarificationId,
                              Long targetUserId, LocalDateTime expiresAt) {
        AgentPendingClarification entity = new AgentPendingClarification();
        entity.setSessionId(sessionId);
        entity.setRunId(runId);
        entity.setMessageId(messageId);
        entity.setClarificationId(clarificationId);
        entity.setTitle(title);
        entity.setType(type);
        entity.setMultiple(multiple);
        entity.setOptions(optionsJson);
        entity.setDescription(description);
        entity.setTargetUserId(targetUserId);
        entity.setExpiresAt(expiresAt);
        clarificationMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public Long createToolApprovalPending(Long sessionId, Long runId, String title, String optionsJson,
                                          String toolCallsJson, String clarificationId) {
        AgentPendingClarification entity = new AgentPendingClarification();
        entity.setSessionId(sessionId);
        entity.setRunId(runId);
        entity.setMessageId(runId);
        entity.setClarificationId(clarificationId);
        entity.setTitle(title);
        entity.setType(com.buukle.agent.runtime.kernel.constants.ChatClarification.TOOL_APPROVAL_TYPE);
        entity.setOptions(optionsJson);
        entity.setToolCallsJson(toolCallsJson);
        clarificationMapper.insert(entity);
        return entity.getId();
    }

    public String respondToClarification(Long runId, String response) {
        AgentPendingClarification pending = clarificationMapper.selectOne(
                new LambdaQueryWrapper<AgentPendingClarification>()
                        .eq(AgentPendingClarification::getRunId, runId)
                        .isNull(AgentPendingClarification::getUserResponse)
                        .orderByDesc(AgentPendingClarification::getCreatedAt)
                        .last("LIMIT 1"));
        if (pending == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "No pending clarification found for this run");
        }
        pending.setUserResponse(response);
        clarificationMapper.updateById(pending);
        return response;
    }

    @Override
    public boolean respondToClarificationByClarificationId(Long sessionId, String clarificationId, String response) {
        AgentPendingClarification pending = clarificationMapper.selectOne(
                new LambdaQueryWrapper<AgentPendingClarification>()
                        .eq(AgentPendingClarification::getSessionId, sessionId)
                        .eq(AgentPendingClarification::getClarificationId, clarificationId)
                        .isNull(AgentPendingClarification::getUserResponse)
                        .orderByDesc(AgentPendingClarification::getCreatedAt)
                        .last("LIMIT 1"));
        if (pending == null) {
            return false;
        }
        pending.setUserResponse(response);
        clarificationMapper.updateById(pending);
        return true;
    }

    @Override
    public List<ClarificationVO> listPendingByTargetUser(Long tenantId, Long targetUserId) {
        if (targetUserId == null) {
            return List.of();
        }
        List<AgentPendingClarification> rows = clarificationMapper.selectList(
                new LambdaQueryWrapper<AgentPendingClarification>()
                        .eq(tenantId != null, AgentPendingClarification::getTenantId, tenantId)
                        .eq(AgentPendingClarification::getTargetUserId, targetUserId)
                        .isNull(AgentPendingClarification::getUserResponse)
                        .orderByDesc(AgentPendingClarification::getCreatedAt));
        return rows.stream().map(this::toVO).toList();
    }

    @Override
    public ClarificationVO findPendingByClarificationId(String clarificationId) {
        if (clarificationId == null || clarificationId.isBlank()) {
            return null;
        }
        AgentPendingClarification pending = clarificationMapper.selectOne(
                new LambdaQueryWrapper<AgentPendingClarification>()
                        .eq(AgentPendingClarification::getClarificationId, clarificationId)
                        .isNull(AgentPendingClarification::getUserResponse)
                        .orderByDesc(AgentPendingClarification::getId)
                        .last("LIMIT 1"));
        return pending == null ? null : toVO(pending);
    }

    @Override
    public ClarificationVO findByClarificationId(String clarificationId) {
        if (clarificationId == null || clarificationId.isBlank()) {
            return null;
        }
        AgentPendingClarification pending = clarificationMapper.selectOne(
                new LambdaQueryWrapper<AgentPendingClarification>()
                        .eq(AgentPendingClarification::getClarificationId, clarificationId)
                        .orderByDesc(AgentPendingClarification::getId)
                        .last("LIMIT 1"));
        return pending == null ? null : toVO(pending);
    }

    @Override
    public boolean answerTargeted(Long id, Long targetUserId, String response, Long answeredBy) {
        if (id == null || targetUserId == null) {
            return false;
        }
        AgentPendingClarification update = new AgentPendingClarification();
        update.setUserResponse(response);
        update.setAnsweredBy(answeredBy);
        int updated = clarificationMapper.update(update,
                new LambdaQueryWrapper<AgentPendingClarification>()
                        .eq(AgentPendingClarification::getId, id)
                        .eq(AgentPendingClarification::getTargetUserId, targetUserId)
                        .isNull(AgentPendingClarification::getUserResponse));
        return updated > 0;
    }

    @Override
    public List<ClarificationVO> listExpired(LocalDateTime now, int limit) {
        if (now == null) {
            return List.of();
        }
        int capped = limit <= 0 ? 100 : limit;
        List<AgentPendingClarification> rows = clarificationMapper.selectList(
                new LambdaQueryWrapper<AgentPendingClarification>()
                        .isNotNull(AgentPendingClarification::getTargetUserId)
                        .isNotNull(AgentPendingClarification::getExpiresAt)
                        .lt(AgentPendingClarification::getExpiresAt, now)
                        .isNull(AgentPendingClarification::getUserResponse)
                        .orderByAsc(AgentPendingClarification::getExpiresAt)
                        .last("LIMIT " + capped));
        return rows.stream().map(this::toVO).toList();
    }

    @Override
    public void markExpired(Long id) {
        if (id == null) {
            return;
        }
        AgentPendingClarification update = new AgentPendingClarification();
        update.setUserResponse(ChatClarification.CLARIFICATION_RESPONSE_DISMISSED);
        clarificationMapper.update(update,
                new LambdaQueryWrapper<AgentPendingClarification>()
                        .eq(AgentPendingClarification::getId, id)
                        .isNull(AgentPendingClarification::getUserResponse));
    }

    @Override
    public void markAnsweredBy(String clarificationId, Long answeredBy) {
        if (clarificationId == null || answeredBy == null) {
            return;
        }
        AgentPendingClarification update = new AgentPendingClarification();
        update.setAnsweredBy(answeredBy);
        clarificationMapper.update(update,
                new LambdaQueryWrapper<AgentPendingClarification>()
                        .eq(AgentPendingClarification::getClarificationId, clarificationId)
                        .orderByDesc(AgentPendingClarification::getId)
                        .last("LIMIT 1"));
    }

    @Override
    public Map<Long, List<ClarificationVO>> mapByRunIdList(Collection<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) return Collections.emptyMap();

        List<AgentPendingClarification> pendingList = clarificationMapper.selectList(
                new LambdaQueryWrapper<AgentPendingClarification>()
                        .in(AgentPendingClarification::getRunId, runIds));

        return pendingList.stream().map(this::toVO).collect(Collectors.groupingBy(
                ClarificationVO::getRunId,
                Collectors.toList()));
    }

    private ClarificationVO toVO(AgentPendingClarification p) {
        ClarificationVO vo = new ClarificationVO();
        vo.setId(p.getId());
        vo.setClarificationId(p.getClarificationId());
        vo.setRunId(p.getRunId());
        vo.setSessionId(p.getSessionId());
        vo.setMessageId(p.getMessageId());
        vo.setTitle(p.getTitle());
        vo.setType(p.getType());
        vo.setMultiple(Boolean.TRUE.equals(p.getMultiple()));
        vo.setOptions(p.getOptions());
        vo.setDescription(p.getDescription());
        vo.setToolCallsJson(p.getToolCallsJson());
        vo.setUserResponse(p.getUserResponse());
        vo.setTargetUserId(p.getTargetUserId());
        vo.setAnsweredBy(p.getAnsweredBy());
        vo.setExpiresAt(p.getExpiresAt() != null ? p.getExpiresAt().format(DTF) : null);
        vo.setCreatedAt(p.getCreatedAt() != null ? p.getCreatedAt().format(DTF) : null);
        String status;
        if (ChatClarification.CLARIFICATION_RESPONSE_DISMISSED.equals(p.getUserResponse())) {
            status = "dismissed";
        } else if (p.getUserResponse() != null) {
            status = "responded";
        } else {
            status = "pending";
        }
        vo.setStatus(status);
        return vo;
    }
}
