package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.instance.domain.AgentToolCallRecord;
import com.buukle.agent.instance.dtvo.enums.ToolCallRecordStatus;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.repository.AgentToolCallRecordMapper;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentToolCallRecordServiceImpl implements AgentToolCallRecordSpi {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final AgentToolCallRecordMapper mapper;

    private static AgentToolCallRecordVO toVO(AgentToolCallRecord r) {
        AgentToolCallRecordVO vo = new AgentToolCallRecordVO();
        vo.setId(r.getId());
        vo.setStepId(r.getStepId());
        vo.setCallId(r.getCallId());
        vo.setRunId(r.getRunId());
        vo.setSessionId(r.getSessionId());
        vo.setToolName(r.getToolName());
        vo.setDisplayNameCn(r.getDisplayNameCn());
        vo.setDisplayNameEn(r.getDisplayNameEn());
        vo.setArgumentsJson(r.getArgumentsJson());
        vo.setCompressedArguments(r.getCompressedArguments());
        vo.setArtifact(r.getArtifact());
        vo.setCompressedArtifact(r.getCompressedArtifact());
        vo.setStatus(r.getStatus());
        vo.setErrorMessage(r.getErrorMessage());
        vo.setSubAgentRunId(r.getSubAgentRunId());
        if (r.getCreatedAt() != null) vo.setCreatedAt(r.getCreatedAt().format(DATE_FMT));
        if (r.getUpdatedAt() != null) vo.setUpdatedAt(r.getUpdatedAt().format(DATE_FMT));
        return vo;
    }

    @Override
    public AgentToolCallRecordVO createRecord(Long stepId, String callId, Long runId, Long sessionId,
                                              String toolName, String displayNameCn, String displayNameEn, String argumentsJson, Long subAgentRunId) {
        AgentToolCallRecord record = new AgentToolCallRecord();
        record.setStepId(stepId);
        record.setCallId(callId);
        record.setRunId(runId);
        record.setSessionId(sessionId);
        record.setToolName(toolName);
        record.setDisplayNameCn(displayNameCn);
        record.setDisplayNameEn(displayNameEn);
        record.setArgumentsJson(argumentsJson);
        record.setSubAgentRunId(subAgentRunId);
        record.setStatus(ToolCallRecordStatus.PENDING.name());
        mapper.insert(record);
        return toVO(record);
    }

    @Override
    public void updateStatus(Long id, String status, String artifact, String errorMessage) {
        AgentToolCallRecord record = mapper.selectById(id);
        if (record == null) return;
        record.setStatus(status);
        if (artifact != null) record.setArtifact(artifact);
        if (errorMessage != null) record.setErrorMessage(errorMessage);
        mapper.updateById(record);
    }

    @Override
    public void updateCompressedArguments(Long id, String compressedArguments) {
        AgentToolCallRecord r = new AgentToolCallRecord();
        r.setId(id);
        r.setCompressedArguments(compressedArguments);
        mapper.updateById(r);
    }

    @Override
    public void updateCompressedArtifact(Long id, String compressedArtifact) {
        AgentToolCallRecord r = new AgentToolCallRecord();
        r.setId(id);
        r.setCompressedArtifact(compressedArtifact);
        mapper.updateById(r);
    }

    @Override
    public AgentToolCallRecordVO getLatestByStepId(Long stepId) {
        AgentToolCallRecord r = mapper.selectOne(
                new LambdaQueryWrapper<AgentToolCallRecord>()
                        .eq(AgentToolCallRecord::getStepId, stepId)
                        .orderByDesc(AgentToolCallRecord::getId)
                        .last("LIMIT 1"));
        return r != null ? toVO(r) : null;
    }

    @Override
    public AgentToolCallRecordVO getLatestBySessionAndToolName(Long sessionId, String toolName) {
        AgentToolCallRecord r = mapper.selectOne(
                new LambdaQueryWrapper<AgentToolCallRecord>()
                        .eq(AgentToolCallRecord::getSessionId, sessionId)
                        .eq(AgentToolCallRecord::getToolName, toolName)
                        .eq(AgentToolCallRecord::getStatus, ToolCallRecordStatus.SUCCEEDED.name())
                        .orderByDesc(AgentToolCallRecord::getId)
                        .last("LIMIT 1"));
        return r != null ? toVO(r) : null;
    }

    @Override
    public List<AgentToolCallRecordVO> listBySessionId(Long sessionId, Long runId) {
        var query = new LambdaQueryWrapper<AgentToolCallRecord>()
                .eq(AgentToolCallRecord::getSessionId, sessionId);

        if (runId != null) {
            query.eq(AgentToolCallRecord::getRunId, runId);
        } else {
            Long latest = getLatestRunIdWithToolCalls(sessionId);
            if (latest != null) {
                query.eq(AgentToolCallRecord::getRunId, latest);
            }
        }

        query.orderByDesc(AgentToolCallRecord::getId);
        return mapper.selectList(query).stream().map(AgentToolCallRecordServiceImpl::toVO).toList();
    }

    @Override
    public List<AgentToolCallRecordVO> listByRunId(Long runId, int offset, int limit) {
        if (runId == null) {
            return List.of();
        }
        LambdaQueryWrapper<AgentToolCallRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentToolCallRecord::getRunId, runId)
                .orderByDesc(AgentToolCallRecord::getId)
                .last("LIMIT " + limit + " OFFSET " + offset);
        return mapper.selectList(wrapper).stream().map(AgentToolCallRecordServiceImpl::toVO).toList();
    }

    @Override
    public long countByRunId(Long runId) {
        if (runId == null) {
            return 0;
        }
        return mapper.selectCount(new LambdaQueryWrapper<AgentToolCallRecord>()
                .eq(AgentToolCallRecord::getRunId, runId));
    }

    private Long getLatestRunIdWithToolCalls(Long sessionId) {
        var w = new LambdaQueryWrapper<AgentToolCallRecord>()
                .eq(AgentToolCallRecord::getSessionId, sessionId)
                .orderByDesc(AgentToolCallRecord::getId)
                .last("LIMIT 1");
        AgentToolCallRecord r = mapper.selectOne(w);
        return r != null ? r.getRunId() : null;
    }
}
