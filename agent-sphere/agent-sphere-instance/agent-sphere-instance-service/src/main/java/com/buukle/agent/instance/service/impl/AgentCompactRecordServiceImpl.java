package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.buukle.agent.instance.domain.AgentCompactRecord;
import com.buukle.agent.instance.dtvo.vo.AgentCompactRecordVO;
import com.buukle.agent.instance.repository.AgentCompactRecordMapper;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class AgentCompactRecordServiceImpl implements AgentCompactRecordSpi {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STALE_ERROR = "compaction record timed out";
    private final AgentCompactRecordMapper mapper;

    private static AgentCompactRecordVO toVO(AgentCompactRecord r) {
        AgentCompactRecordVO vo = new AgentCompactRecordVO();
        vo.setId(r.getId());
        vo.setSessionId(r.getSessionId());
        vo.setRunId(r.getRunId());
        vo.setStatus(r.getStatus());
        vo.setSummaryBefore(r.getSummaryBefore());
        vo.setSummaryAfter(r.getSummaryAfter());
        vo.setTokenCount(r.getTokenCount());
        vo.setCompactedUptoRunId(r.getCompactedUptoRunId());
        vo.setTailJson(r.getTailJson());
        vo.setErrorMessage(r.getErrorMessage());
        if (r.getCreatedAt() != null) vo.setCreatedAt(r.getCreatedAt().format(DATE_FMT));
        if (r.getUpdatedAt() != null) vo.setUpdatedAt(r.getUpdatedAt().format(DATE_FMT));
        return vo;
    }

    @Override
    public AgentCompactRecordVO createRecord(Long sessionId) {
        AgentCompactRecord record = new AgentCompactRecord();
        record.setSessionId(sessionId);
        record.setStatus(STATUS_PENDING);
        mapper.insert(record);
        return toVO(record);
    }

    @Override
    public AgentCompactRecordVO createRunRecord(Long sessionId, Long runId) {
        AgentCompactRecord record = new AgentCompactRecord();
        record.setSessionId(sessionId);
        record.setRunId(runId);
        record.setStatus(STATUS_PENDING);
        mapper.insert(record);
        return toVO(record);
    }

    @Override
    public void updateRunning(Long id) {
        AgentCompactRecord record = mapper.selectById(id);
        if (record == null) return;
        record.setStatus(STATUS_RUNNING);
        mapper.updateById(record);
    }

    @Override
    public void updateCompleted(Long id, String summaryBefore, String summaryAfter, Long tokenCount, Long compactedUptoRunId) {
        AgentCompactRecord record = mapper.selectById(id);
        if (record == null) return;
        record.setStatus(STATUS_COMPLETED);
        record.setSummaryBefore(summaryBefore);
        record.setSummaryAfter(summaryAfter);
        record.setTokenCount(tokenCount);
        record.setCompactedUptoRunId(compactedUptoRunId);
        mapper.updateById(record);
    }

    @Override
    public void completeRunCompact(Long id, String summaryBefore, String summaryAfter, Long tokenCount, String tailJson) {
        AgentCompactRecord record = mapper.selectById(id);
        if (record == null) return;
        record.setStatus(STATUS_COMPLETED);
        record.setSummaryBefore(summaryBefore);
        record.setSummaryAfter(summaryAfter);
        record.setTokenCount(tokenCount);
        record.setTailJson(tailJson);
        mapper.updateById(record);
    }

    @Override
    public void updateRunTail(Long runId, String tailJson) {
        if (runId == null) return;
        AgentCompactRecord latest = mapper.selectOne(new LambdaQueryWrapper<AgentCompactRecord>()
                .eq(AgentCompactRecord::getRunId, runId)
                .eq(AgentCompactRecord::getStatus, STATUS_COMPLETED)
                .orderByDesc(AgentCompactRecord::getId)
                .last("LIMIT 1"));
        if (latest == null) return;
        mapper.update(null, new LambdaUpdateWrapper<AgentCompactRecord>()
                .eq(AgentCompactRecord::getId, latest.getId())
                .set(AgentCompactRecord::getTailJson, tailJson)
                .set(AgentCompactRecord::getUpdatedAt, java.time.LocalDateTime.now()));
    }

    @Override
    public void updateFailed(Long id, String errorMessage) {
        AgentCompactRecord record = mapper.selectById(id);
        if (record == null) return;
        record.setStatus(STATUS_FAILED);
        record.setErrorMessage(errorMessage);
        mapper.updateById(record);
    }

    @Override
    public int failStalePending(java.time.LocalDateTime olderThan) {
        if (olderThan == null) return 0;
        return mapper.update(null, new LambdaUpdateWrapper<AgentCompactRecord>()
                .in(AgentCompactRecord::getStatus, STATUS_PENDING, STATUS_RUNNING)
                .lt(AgentCompactRecord::getCreatedAt, olderThan)
                .set(AgentCompactRecord::getStatus, STATUS_FAILED)
                .set(AgentCompactRecord::getErrorMessage, STALE_ERROR)
                .set(AgentCompactRecord::getUpdatedAt, java.time.LocalDateTime.now()));
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
                        // run-local 记录也有 session_id，必须只取 session 级，否则会误推进 HistoryLoader 游标
                        .isNull(AgentCompactRecord::getRunId)
                        .eq(AgentCompactRecord::getStatus, STATUS_COMPLETED)
                        .orderByDesc(AgentCompactRecord::getId)
                        .last("LIMIT 1"));
        return r != null ? toVO(r) : null;
    }

    @Override
    public AgentCompactRecordVO getLatestCompletedByRunId(Long runId) {
        if (runId == null) return null;
        AgentCompactRecord r = mapper.selectOne(
                new LambdaQueryWrapper<AgentCompactRecord>()
                        .eq(AgentCompactRecord::getRunId, runId)
                        .eq(AgentCompactRecord::getStatus, STATUS_COMPLETED)
                        .orderByDesc(AgentCompactRecord::getId)
                        .last("LIMIT 1"));
        return r != null ? toVO(r) : null;
    }
}
