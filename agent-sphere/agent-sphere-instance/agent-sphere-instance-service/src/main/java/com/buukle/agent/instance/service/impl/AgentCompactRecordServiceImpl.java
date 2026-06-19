package com.buukle.agent.instance.service.impl;

import com.buukle.agent.instance.domain.AgentCompactRecord;
import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;
import com.buukle.agent.instance.repository.AgentCompactRecordMapper;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class AgentCompactRecordServiceImpl implements AgentCompactRecordSpi {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final AgentCompactRecordMapper mapper;

    @Override
    public AgentCompactRecordVO createRecord(Long sessionId) {
        AgentCompactRecord record = new AgentCompactRecord();
        record.setSessionId(sessionId);
        record.setStatus("PENDING");
        mapper.insert(record);
        return toVO(record);
    }

    @Override
    public void updateCompleted(Long id, String summaryBefore, String summaryAfter, Long tokenCount, Long compactedUptoRunId) {
        AgentCompactRecord record = mapper.selectById(id);
        if (record == null) return;
        record.setStatus("COMPLETED");
        record.setSummaryBefore(summaryBefore);
        record.setSummaryAfter(summaryAfter);
        record.setTokenCount(tokenCount);
        record.setCompactedUptoRunId(compactedUptoRunId);
        mapper.updateById(record);
    }

    @Override
    public void updateFailed(Long id, String errorMessage) {
        AgentCompactRecord record = mapper.selectById(id);
        if (record == null) return;
        record.setStatus("FAILED");
        record.setErrorMessage(errorMessage);
        mapper.updateById(record);
    }

    @Override
    public AgentCompactRecordVO getLatestBySessionId(Long sessionId) {
        AgentCompactRecord r = mapper.selectOne(
            new LambdaQueryWrapper<AgentCompactRecord>()
                .eq(AgentCompactRecord::getSessionId, sessionId)
                .orderByDesc(AgentCompactRecord::getId)
                .last("LIMIT 1"));
        return r != null ? toVO(r) : null;
    }

    @Override
    public AgentCompactRecordVO getLatestCompleted(Long sessionId) {
        AgentCompactRecord r = mapper.selectOne(
            new LambdaQueryWrapper<AgentCompactRecord>()
                .eq(AgentCompactRecord::getSessionId, sessionId)
                .eq(AgentCompactRecord::getStatus, "COMPLETED")
                .orderByDesc(AgentCompactRecord::getId)
                .last("LIMIT 1"));
        return r != null ? toVO(r) : null;
    }

    private static AgentCompactRecordVO toVO(AgentCompactRecord r) {
        AgentCompactRecordVO vo = new AgentCompactRecordVO();
        vo.setId(r.getId());
        vo.setSessionId(r.getSessionId());
        vo.setStatus(r.getStatus());
        vo.setSummaryBefore(r.getSummaryBefore());
        vo.setSummaryAfter(r.getSummaryAfter());
        vo.setTokenCount(r.getTokenCount());
        vo.setCompactedUptoRunId(r.getCompactedUptoRunId());
        vo.setErrorMessage(r.getErrorMessage());
        if (r.getCreatedAt() != null) vo.setCreatedAt(r.getCreatedAt().format(DATE_FMT));
        if (r.getUpdatedAt() != null) vo.setUpdatedAt(r.getUpdatedAt().format(DATE_FMT));
        return vo;
    }
}
