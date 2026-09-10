package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.instance.domain.AgentLlmInteractionRecord;
import com.buukle.agent.instance.domain.vo.SessionUsageVO;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;
import com.buukle.agent.instance.repository.AgentLlmInteractionRecordMapper;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentLlmInteractionRecordServiceImpl implements AgentLlmInteractionRecordSpi {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentLlmInteractionRecordMapper mapper;

    @Override
    public Long createRecord(AgentLlmInteractionRecordVO vo) {
        AgentLlmInteractionRecord record = new AgentLlmInteractionRecord();
        record.setRunId(vo.getRunId());
        record.setSessionId(vo.getSessionId());
        record.setInteractionType(vo.getInteractionType());
        record.setModelName(vo.getModelName());
        record.setRequestBody(vo.getRequestBody());
        record.setResponseBody(vo.getResponseBody());
        record.setHttpStatus(vo.getHttpStatus());
        record.setDurationMs(vo.getDurationMs());
        record.setErrorMessage(vo.getErrorMessage());
        record.setSuccess(vo.getSuccess());
        record.setReasoning(vo.getReasoning());
        record.setReplyContent(vo.getReplyContent());
        record.setSubAgentRunId(vo.getSubAgentRunId());
        record.setUsage(vo.getUsage());
        record.setPromptTokens(vo.getPromptTokens());
        record.setCompletionTokens(vo.getCompletionTokens());
        record.setTotalTokens(vo.getTotalTokens());
        record.setCacheHitTokens(vo.getCacheHitTokens());
        record.setCacheMissTokens(vo.getCacheMissTokens());
        if (vo.getCreatedBy() != null) {
            record.setCreatedBy(vo.getCreatedBy());
        }
        mapper.insert(record);
        return record.getId();
    }

    @Override
    public List<AgentLlmInteractionRecordVO> listByRunId(Long runId, int offset, int limit) {
        LambdaQueryWrapper<AgentLlmInteractionRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentLlmInteractionRecord::getRunId, runId)
                .orderByDesc(AgentLlmInteractionRecord::getId)
                .last("LIMIT " + limit + " OFFSET " + offset);
        return mapper.selectList(wrapper).stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public long countByRunId(Long runId) {
        return mapper.selectCount(new LambdaQueryWrapper<AgentLlmInteractionRecord>()
                .eq(AgentLlmInteractionRecord::getRunId, runId));
    }

    @Override
    public AgentLlmInteractionRecordVO getById(Long id) {
        AgentLlmInteractionRecord record = mapper.selectById(id);
        return record == null ? null : toVO(record);
    }

    @Override
    public SessionUsageVO usageSummary(Long sessionId) {
        SessionUsageVO vo = mapper.sumUsageBySession(sessionId);
        if (vo == null) {
            vo = new SessionUsageVO();
            vo.setSessionId(sessionId);
            vo.setRunCount(0L);
            vo.setInteractionCount(0L);
            vo.setPromptTokens(0L);
            vo.setCompletionTokens(0L);
            vo.setTotalTokens(0L);
            vo.setCacheHitTokens(0L);
            vo.setCacheMissTokens(0L);
        }
        Long total = vo.getTotalTokens() != null ? vo.getTotalTokens() : 0L;
        Long hit = vo.getCacheHitTokens() != null ? vo.getCacheHitTokens() : 0L;
        Long miss = vo.getCacheMissTokens() != null ? vo.getCacheMissTokens() : 0L;
        long denom = hit + miss;
        if (denom > 0) {
            vo.setCacheHitRate(Math.round(hit * 10000.0 / denom) / 100.0);
        }
        return vo;
    }

    private AgentLlmInteractionRecordVO toVO(AgentLlmInteractionRecord record) {
        AgentLlmInteractionRecordVO vo = new AgentLlmInteractionRecordVO();
        vo.setId(record.getId());
        vo.setRunId(record.getRunId());
        vo.setSessionId(record.getSessionId());
        vo.setInteractionType(record.getInteractionType());
        vo.setModelName(record.getModelName());
        vo.setRequestBody(record.getRequestBody());
        vo.setResponseBody(record.getResponseBody());
        vo.setHttpStatus(record.getHttpStatus());
        vo.setDurationMs(record.getDurationMs());
        vo.setErrorMessage(record.getErrorMessage());
        vo.setSuccess(record.getSuccess());
        vo.setReasoning(record.getReasoning());
        vo.setReplyContent(record.getReplyContent());
        vo.setSubAgentRunId(record.getSubAgentRunId());
        vo.setUsage(record.getUsage());
        vo.setPromptTokens(record.getPromptTokens());
        vo.setCompletionTokens(record.getCompletionTokens());
        vo.setTotalTokens(record.getTotalTokens());
        vo.setCacheHitTokens(record.getCacheHitTokens());
        vo.setCacheMissTokens(record.getCacheMissTokens());
        if (record.getCreatedAt() != null) {
            vo.setCreatedAt(record.getCreatedAt().format(DTF));
        }
        return vo;
    }
}
