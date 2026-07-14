package com.buukle.agent.instance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.domain.AgentPendingClarification;
import com.buukle.agent.instance.dtvo.vo.ClarificationVO;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.spi.ClarificationSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClarificationService implements ClarificationSpi {

    private final AgentPendingClarificationMapper clarificationMapper;
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public Long createPending(Long sessionId, Long runId, Long messageId, String title, String type, String optionsJson, String clarificationId) {
        AgentPendingClarification entity = new AgentPendingClarification();
        entity.setSessionId(sessionId);
        entity.setRunId(runId);
        entity.setMessageId(messageId);
        entity.setClarificationId(clarificationId);
        entity.setTitle(title);
        entity.setType(type);
        entity.setOptions(optionsJson);
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(30));
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
        if (pending.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Clarification request has expired");
        }
        pending.setUserResponse(response);
        clarificationMapper.updateById(pending);
        return response;
    }

    @Override
    public Map<Long, List<ClarificationVO>> mapByRunIdList(Collection<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) return Collections.emptyMap();

        List<AgentPendingClarification> pendingList = clarificationMapper.selectList(
                new LambdaQueryWrapper<AgentPendingClarification>()
                        .in(AgentPendingClarification::getRunId, runIds));

        return pendingList.stream().map(p -> {
            ClarificationVO vo = new ClarificationVO();
            vo.setClarificationId(p.getClarificationId());
            vo.setRunId(p.getRunId());
            vo.setSessionId(p.getSessionId());
            vo.setMessageId(p.getMessageId());
            vo.setTitle(p.getTitle());
            vo.setType(p.getType());
            vo.setOptions(p.getOptions());
            vo.setUserResponse(p.getUserResponse());
            vo.setExpiresAt(p.getExpiresAt() != null ? p.getExpiresAt().format(DTF) : null);
            String status;
            if (p.getUserResponse() != null) {
                status = "responded";
            } else if (p.getExpiresAt() != null && p.getExpiresAt().isBefore(LocalDateTime.now())) {
                status = "expired";
            } else {
                status = "pending";
            }
            vo.setStatus(status);
            return vo;
        }).collect(Collectors.groupingBy(
                ClarificationVO::getRunId,
                Collectors.toList()));
    }
}
