package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.instance.domain.AgentLlmInteractionRecord;
import com.buukle.agent.instance.domain.AgentSubAgentRun;
import com.buukle.agent.instance.domain.AgentToolCallRecord;
import com.buukle.agent.instance.dtvo.enums.SkillExecutionStatus;
import com.buukle.agent.instance.dtvo.vo.AgentSubAgentRunVO;
import com.buukle.agent.instance.dtvo.vo.SubAgentTimelineItemVO;
import com.buukle.agent.instance.repository.AgentLlmInteractionRecordMapper;
import com.buukle.agent.instance.repository.AgentSubAgentRunMapper;
import com.buukle.agent.instance.repository.AgentToolCallRecordMapper;
import com.buukle.agent.instance.spi.AgentSubAgentRunSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentSubAgentRunServiceImpl implements AgentSubAgentRunSpi {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentSubAgentRunMapper mapper;
    private final AgentLlmInteractionRecordMapper interactionMapper;
    private final AgentToolCallRecordMapper toolCallMapper;

    private static AgentSubAgentRunVO toVO(AgentSubAgentRun r) {
        AgentSubAgentRunVO vo = new AgentSubAgentRunVO();
        vo.setId(r.getId());
        vo.setSessionId(r.getSessionId());
        vo.setRunId(r.getRunId());
        vo.setParentRunId(r.getParentRunId());
        vo.setParentToolCallId(r.getParentToolCallId());
        vo.setAgentType(r.getAgentType());
        vo.setAgentRef(r.getAgentRef());
        vo.setDisplayName(r.getDisplayName());
        vo.setStatus(r.getStatus());
        if (r.getStartedAt() != null) vo.setStartedAt(r.getStartedAt().format(DTF));
        if (r.getFinishedAt() != null) vo.setFinishedAt(r.getFinishedAt().format(DTF));
        if (r.getCreatedAt() != null) vo.setCreatedAt(r.getCreatedAt().format(DTF));
        return vo;
    }

    @Override
    public AgentSubAgentRunVO start(Long sessionId, Long runId, Long parentRunId, String parentToolCallId,
                                    String agentType, String agentRef, String displayName) {
        AgentSubAgentRun r = new AgentSubAgentRun();
        r.setSessionId(sessionId);
        r.setRunId(runId);
        r.setParentRunId(parentRunId);
        r.setParentToolCallId(parentToolCallId);
        r.setAgentType(agentType != null ? agentType : "SKILL");
        r.setAgentRef(agentRef != null ? agentRef : "");
        r.setDisplayName(displayName != null ? displayName : "");
        r.setStatus(SkillExecutionStatus.RUNNING.name());
        r.setStartedAt(LocalDateTime.now());
        mapper.insert(r);
        return toVO(r);
    }

    @Override
    public void finish(Long id, String status) {
        AgentSubAgentRun r = mapper.selectById(id);
        if (r == null) return;
        AgentSubAgentRun update = new AgentSubAgentRun();
        update.setId(id);
        update.setStatus(status);
        update.setFinishedAt(LocalDateTime.now());
        mapper.updateById(update);
    }

    @Override
    public AgentSubAgentRunVO getById(Long id) {
        AgentSubAgentRun r = mapper.selectById(id);
        return r == null ? null : toVO(r);
    }

    @Override
    public List<AgentSubAgentRunVO> listBySession(Long sessionId) {
        return mapper.selectList(new LambdaQueryWrapper<AgentSubAgentRun>()
                        .eq(AgentSubAgentRun::getSessionId, sessionId)
                        .orderByAsc(AgentSubAgentRun::getId))
                .stream().map(AgentSubAgentRunServiceImpl::toVO).toList();
    }

    @Override
    public List<AgentSubAgentRunVO> listByRun(Long runId) {
        return mapper.selectList(new LambdaQueryWrapper<AgentSubAgentRun>()
                        .eq(AgentSubAgentRun::getRunId, runId)
                        .orderByAsc(AgentSubAgentRun::getId))
                .stream().map(AgentSubAgentRunServiceImpl::toVO).toList();
    }

    @Override
    public List<SubAgentTimelineItemVO> timeline(Long subAgentRunId) {
        List<AgentLlmInteractionRecord> interactions = interactionMapper.selectList(
                new LambdaQueryWrapper<AgentLlmInteractionRecord>()
                        .eq(AgentLlmInteractionRecord::getSubAgentRunId, subAgentRunId));
        List<AgentToolCallRecord> toolCalls = toolCallMapper.selectList(
                new LambdaQueryWrapper<AgentToolCallRecord>()
                        .eq(AgentToolCallRecord::getSubAgentRunId, subAgentRunId));

        List<SubAgentTimelineItemVO> items = new ArrayList<>();
        for (AgentLlmInteractionRecord i : interactions) {
            SubAgentTimelineItemVO item = new SubAgentTimelineItemVO();
            item.setActivityType("llm_interaction");
            item.setInteractionId(i.getId());
            item.setInteractionType(i.getInteractionType());
            item.setModelName(i.getModelName());
            item.setReasoning(i.getReasoning());
            item.setReply(i.getReplyContent() != null ? i.getReplyContent() : i.getResponseBody());
            item.setSuccess(i.getSuccess());
            item.setCreatedAt(i.getCreatedAt() != null ? i.getCreatedAt().format(DTF) : null);
            items.add(item);
        }
        for (AgentToolCallRecord t : toolCalls) {
            SubAgentTimelineItemVO item = new SubAgentTimelineItemVO();
            item.setActivityType("tool_call");
            item.setStepId(t.getStepId());
            item.setToolName(t.getToolName());
            item.setDisplayNameCn(t.getDisplayNameCn());
            item.setDisplayNameEn(t.getDisplayNameEn());
            item.setArgumentsJson(t.getArgumentsJson());
            item.setArtifact(t.getArtifact());
            item.setToolStatus(t.getStatus());
            item.setToolErrorMessage(t.getErrorMessage());
            item.setCreatedAt(t.getCreatedAt() != null ? t.getCreatedAt().format(DTF) : null);
            items.add(item);
        }
        items.sort(Comparator.comparing(SubAgentTimelineItemVO::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return items;
    }
}